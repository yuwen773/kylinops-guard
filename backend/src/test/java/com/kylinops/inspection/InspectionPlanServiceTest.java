package com.kylinops.inspection;

import com.kylinops.inspection.model.InspectionExecutionStatus;
import com.kylinops.inspection.model.InspectionNotificationPolicy;
import com.kylinops.inspection.model.InspectionScheduleType;
import com.kylinops.inspection.model.InspectionTemplateType;
import com.kylinops.inspection.model.InspectionTriggerType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1-02 Plan 02 — Task 6 计划生命周期服务测试。
 *
 * <p>覆盖 {@link InspectionPlanService} 的契约:</p>
 * <ul>
 *   <li>createPlan: 新建 plan 默认 enabled=false, version=0, nextRunAt=null</li>
 *   <li>updatePlan: @Version 乐观锁,旧 version 提交冲突 → 409 InspectionValidationException</li>
 *   <li>enablePlan: 调 InspectionScheduleCalculator.nextRun 算出 nextRunAt(UTC Instant → LocalDateTime)</li>
 *   <li>disablePlan: 清空 nextRunAt(避免被调度器误拉取)</li>
 *   <li>deletePlan: 物理删除 plan,executions 仍可按 executionId 查出</li>
 *   <li>deleteRunningPlan: 有 RUNNING execution 时拒绝删除 → 抛异常</li>
 * </ul>
 *
 * <p><b>测试设置:</b></p>
 * <ul>
 *   <li>{@code @SpringBootTest} — 真实 PlanService + PlanRepository + ExecutionRepository,无需 mock
 *   <li>REQUIRES_NEW 事务模板确保 plan 立即可见,后续 Service 读到一致状态
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("P1-02 T6 — InspectionPlanService")
class InspectionPlanServiceTest {

    @Autowired
    private InspectionPlanService planService;

    @Autowired
    private InspectionPlanRepository planRepository;

    @Autowired
    private InspectionExecutionRepository executionRepository;

    @Autowired
    private PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    @BeforeEach
    void cleanDb() {
        this.tx = new TransactionTemplate(txManager);
        // REQUIRES_NEW 保证清理立即可见
        this.tx.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(s -> {
            executionRepository.deleteAllInBatch();
            planRepository.deleteAllInBatch();
        });
    }

    // ───────── 1. createPlan ─────────

    @Test
    @DisplayName("createPlan → enabled=false, version=0, nextRunAt=null")
    void createPlan_defaultsToDisabledWithZeroVersion() {
        CreatePlanInput input = sampleInput("create-1", InspectionScheduleType.DAILY);

        InspectionPlan plan = planService.createPlan(input);

        assertThat(plan).isNotNull();
        assertThat(plan.getPlanId())
                .as("planId 必须生成且非空")
                .isNotBlank()
                .hasSize(36); // UUID
        assertThat(plan.isEnabled())
                .as("新建 plan 默认 enabled=false(设计 §5.1)")
                .isFalse();
        assertThat(plan.getVersion())
                .as("新建 plan version=0(初始乐观锁版本)")
                .isEqualTo(0L);
        assertThat(plan.getNextRunAt())
                .as("新建 plan nextRunAt 必须为 null,待 enable 时才计算")
                .isNull();
        assertThat(plan.getLastRunAt())
                .as("新建 plan lastRunAt 为 null")
                .isNull();
        assertThat(plan.getTemplateType()).isEqualTo(InspectionTemplateType.HEALTH);
        assertThat(plan.getNotificationPolicy()).isEqualTo(InspectionNotificationPolicy.ON_ABNORMAL);
    }

    // ───────── 2. updatePlan 乐观锁冲突 ─────────

    @Test
    @DisplayName("updatePlan 用旧 version 提交 → 抛 InspectionValidationException(409)")
    void updatePlan_optimisticLockConflictOnStaleVersion() {
        InspectionPlan plan = planService.createPlan(sampleInput("update-1",
                InspectionScheduleType.DAILY));

        // 第一次读出(managed,version=0)
        InspectionPlan reader1 = planRepository.findByPlanId(plan.getPlanId()).orElseThrow();
        long versionSeen = reader1.getVersion();
        assertThat(versionSeen).isEqualTo(0L);

        // 第二次提交更新,version 递增到 1
        InspectionPlan reader2 = planRepository.findByPlanId(plan.getPlanId()).orElseThrow();
        reader2.setDescription("first update");
        planService.updatePlan(reader2);

        // reader1 仍持有 version=0 的 stale snapshot;基于它再次 update 必须冲突
        reader1.setDescription("stale update");
        assertThatThrownBy(() -> planService.updatePlan(reader1))
                .as("基于旧 version 的更新必须抛 409 异常")
                .isInstanceOf(com.kylinops.common.BusinessException.class)
                .extracting(t -> ((com.kylinops.common.BusinessException) t).getCode())
                .isEqualTo(409);
    }

    @Test
    @DisplayName("updatePlan 用当前 version 提交 → 正常通过, version 递增")
    void updatePlan_normalVersionIncrements() {
        InspectionPlan plan = planService.createPlan(sampleInput("update-2",
                InspectionScheduleType.DAILY));

        InspectionPlan reloaded = planRepository.findByPlanId(plan.getPlanId()).orElseThrow();
        reloaded.setDescription("updated-desc");
        InspectionPlan updated = planService.updatePlan(reloaded);

        assertThat(updated.getVersion())
                .as("乐观锁版本号应递增 1")
                .isEqualTo(1L);
        assertThat(updated.getDescription()).isEqualTo("updated-desc");
    }

    // ───────── 3. enablePlan ─────────

    @Test
    @DisplayName("enablePlan → enabled=true, nextRunAt 按 InspectionScheduleCalculator 计算")
    void enablePlan_computesNextRunAtFromCalculator() {
        CreatePlanInput input = sampleInput("enable-1", InspectionScheduleType.DAILY);
        input.localTime = java.time.LocalTime.of(8, 0);
        input.timezone = "Asia/Shanghai";
        InspectionPlan plan = planService.createPlan(input);

        assertThat(plan.isEnabled()).isFalse();
        assertThat(plan.getNextRunAt()).isNull();

        InspectionPlan enabled = planService.enablePlan(plan.getPlanId());

        assertThat(enabled.isEnabled())
                .as("enable 后 enabled=true")
                .isTrue();
        assertThat(enabled.getNextRunAt())
                .as("enable 后 nextRunAt 必须非空(由 InspectionScheduleCalculator 计算)")
                .isNotNull();

        // 验证 nextRunAt 确实是 DAILY 08:00 Asia/Shanghai 的下一个未来时刻(UTC)
        java.time.Instant now = java.time.Instant.now();
        java.time.Instant expectedInstant = new InspectionScheduleCalculator().nextRun(
                InspectionScheduleType.DAILY,
                new InspectionScheduleConfig(input.localTime, null, null),
                ZoneId.of(input.timezone),
                now);
        LocalDateTime expectedNextLocal = LocalDateTime.ofInstant(expectedInstant, ZoneId.of("UTC"));
        assertThat(enabled.getNextRunAt())
                .as("nextRunAt 必须等于 InspectionScheduleCalculator 计算结果")
                .isCloseTo(expectedNextLocal, within(2)); // 容忍 2 秒测试运行开销
    }

    @Test
    @DisplayName("enablePlan 不存在的 plan → 抛异常")
    void enablePlan_notFoundThrows() {
        assertThatThrownBy(() -> planService.enablePlan("non-existent-plan-id"))
                .as("不存在的 plan 启用必须抛异常")
                .isInstanceOfAny(InspectionValidationException.class,
                        java.util.NoSuchElementException.class);
    }

    // ───────── 4. disablePlan ─────────

    @Test
    @DisplayName("disablePlan → enabled=false, nextRunAt 清空(调度器不再拉取)")
    void disablePlan_clearsNextRunAt() {
        CreatePlanInput input = sampleInput("disable-1", InspectionScheduleType.DAILY);
        input.localTime = java.time.LocalTime.of(8, 0);
        input.timezone = "Asia/Shanghai";
        InspectionPlan plan = planService.createPlan(input);

        planService.enablePlan(plan.getPlanId());
        assertThat(planRepository.findByPlanId(plan.getPlanId()).orElseThrow().getNextRunAt())
                .as("enable 后 nextRunAt 应非空")
                .isNotNull();

        InspectionPlan disabled = planService.disablePlan(plan.getPlanId());

        assertThat(disabled.isEnabled())
                .as("disable 后 enabled=false")
                .isFalse();
        assertThat(disabled.getNextRunAt())
                .as("disable 后 nextRunAt 必须清空,避免调度器误拉取")
                .isNull();
    }

    // ───────── 5. deletePlan 保留 execution ─────────

    @Test
    @DisplayName("deletePlan → 物理删除 plan, executions 仍可按 executionId 查出")
    void deletePlan_preservesExecutions() {
        InspectionPlan plan = planService.createPlan(sampleInput("delete-1",
                InspectionScheduleType.DAILY));
        String planId = plan.getPlanId();

        // 准备 1 条 execution
        String executionId = UUID.randomUUID().toString();
        tx.executeWithoutResult(s -> {
            InspectionExecution exec = new InspectionExecution();
            exec.setExecutionId(executionId);
            exec.setPlanId(planId);
            exec.setPlanSnapshotJson("{\"name\":\"snapshot\"}");
            exec.setStatus(InspectionExecutionStatus.SUCCESS);
            exec.setTriggerType(InspectionTriggerType.SCHEDULED);
            exec.setOperator("SYSTEM_SCHEDULER");
            exec.setStartedAt(LocalDateTime.now());
            exec.setFinishedAt(LocalDateTime.now());
            exec.setAbnormal(false);
            executionRepository.save(exec);
        });

        planService.deletePlan(planId);

        assertThat(planRepository.findByPlanId(planId))
                .as("plan 必须物理删除")
                .isEmpty();
        assertThat(executionRepository.findByExecutionId(executionId))
                .as("execution 历史保留,executionId 仍能查出")
                .isPresent();
        assertThat(executionRepository.findByExecutionId(executionId).orElseThrow().getPlanId())
                .as("execution.planId 保留原 planId(无 FK 级联)")
                .isEqualTo(planId);
    }

    // ───────── 6. deleteRunningPlan 拒绝 ─────────

    @Test
    @DisplayName("deletePlan 时存在 RUNNING execution → 抛 InspectionValidationException(409)")
    void deletePlan_runningExecutionRejected() {
        InspectionPlan plan = planService.createPlan(sampleInput("delete-running-1",
                InspectionScheduleType.DAILY));
        String planId = plan.getPlanId();

        // 准备 1 条 RUNNING execution
        String executionId = UUID.randomUUID().toString();
        tx.executeWithoutResult(s -> {
            InspectionExecution exec = new InspectionExecution();
            exec.setExecutionId(executionId);
            exec.setPlanId(planId);
            exec.setPlanSnapshotJson("{\"name\":\"running\"}");
            exec.setStatus(InspectionExecutionStatus.RUNNING);
            exec.setTriggerType(InspectionTriggerType.SCHEDULED);
            exec.setOperator("SYSTEM_SCHEDULER");
            exec.setStartedAt(LocalDateTime.now());
            exec.setAbnormal(false);
            executionRepository.save(exec);
        });

        assertThatThrownBy(() -> planService.deletePlan(planId))
                .as("存在 RUNNING execution 时必须拒绝删除")
                .isInstanceOf(com.kylinops.common.BusinessException.class)
                .extracting(t -> ((com.kylinops.common.BusinessException) t).getCode())
                .isEqualTo(409);

        // plan 必须仍在(未删除)
        assertThat(planRepository.findByPlanId(planId))
                .as("拒绝删除后 plan 必须仍在")
                .isPresent();
    }

    // ───────── 7. 不存在的 plan delete/get → 抛异常 ─────────

    @Test
    @DisplayName("getPlan 不存在 → 抛异常")
    void getPlan_notFoundThrows() {
        assertThatThrownBy(() -> planService.getPlan("no-such-plan"))
                .as("getPlan 不存在必须抛异常")
                .isInstanceOfAny(InspectionValidationException.class,
                        java.util.NoSuchElementException.class);
    }

    // ───────── 辅助 ─────────

    /** 构造最小可用的 create 输入。 */
    private CreatePlanInput sampleInput(String seed, InspectionScheduleType scheduleType) {
        CreatePlanInput input = new CreatePlanInput();
        input.name = "plan-" + seed + "-" + UUID.randomUUID().toString().substring(0, 4);
        input.description = "desc-" + seed;
        input.templateType = InspectionTemplateType.HEALTH;
        input.templateParams = java.util.Map.of("serviceName", "nginx");
        input.thresholds = java.util.Map.of(
                "cpuWarningPercent", 80,
                "memoryWarningPercent", 80,
                "diskWarningPercent", 85);
        input.scheduleType = scheduleType;
        input.localTime = java.time.LocalTime.of(8, 0);
        input.timezone = "Asia/Shanghai";
        input.dayOfWeek = (scheduleType == InspectionScheduleType.WEEKLY)
                ? java.time.DayOfWeek.MONDAY : null;
        input.dayOfMonth = (scheduleType == InspectionScheduleType.MONTHLY) ? 1 : null;
        input.notificationPolicy = InspectionNotificationPolicy.ON_ABNORMAL;
        return input;
    }

    private static org.assertj.core.data.TemporalUnitOffset within(int seconds) {
        return new org.assertj.core.data.TemporalUnitWithinOffset(seconds,
                java.time.temporal.ChronoUnit.SECONDS);
    }
}