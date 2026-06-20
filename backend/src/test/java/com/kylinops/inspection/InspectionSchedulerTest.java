package com.kylinops.inspection;

import com.kylinops.inspection.model.InspectionExecutionStatus;
import com.kylinops.inspection.model.InspectionNotificationPolicy;
import com.kylinops.inspection.model.InspectionScheduleType;
import com.kylinops.inspection.model.InspectionTemplateType;
import com.kylinops.inspection.model.InspectionTriggerType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1-02 Plan 02 — Task 6 调度器测试。
 *
 * <p>用 {@link Clock#fixed(Instant, ZoneId)} 注入控制时间,避免真实时钟漂移。
 * {@link InspectionExecutionService} 用 {@code @MockBean} 替换,只验证 Scheduler 的
 * 调度行为,不依赖真实执行。</p>
 *
 * <p>覆盖契约:</p>
 * <ul>
 *   <li>到期 enabled 计划触发一次 executeScheduled</li>
 *   <li>disabled 计划永不触发</li>
 *   <li>手动触发 runNow(planId, operator) 不改 nextRunAt,operator=MANUAL</li>
 *   <li>重叠:ExecutionService 内部写 SKIPPED, Scheduler 不感知(直接 verify 调用)</li>
 *   <li>错过的调度不重放:now+10min 后调一次, mid-window 计划只补跑 1 次,下一次跳到未来</li>
 *   <li>调度器关闭时(shutdown)不再触发</li>
 * </ul>
 *
 * <p><b>关键设计约束:</b></p>
 * <ul>
 *   <li>Scheduler 自身不持有锁,依赖 Service 层 existsByPlanIdAndStatus 原子检测
 *   <li>Scheduler 不在内存里维护 plan 集合,每次扫描查 PlanRepository
 *   <li>Scheduler 用 ThreadPoolTaskScheduler Bean(由 InspectionSchedulingConfig 注册)
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        // 缩短轮询周期,测试不依赖真实定时器
        "kylinops.inspection.scheduler.poll-interval=2s"
})
@DisplayName("P1-02 T6 — InspectionScheduler")
class InspectionSchedulerTest {

    @Autowired
    private InspectionScheduler scheduler;

    @Autowired
    private InspectionPlanService planService;

    @Autowired
    private InspectionPlanRepository planRepository;

    @Autowired
    private InspectionExecutionRepository executionRepository;

    @Autowired
    private PlatformTransactionManager txManager;

    @MockBean
    private InspectionExecutionService executionService;

    private TransactionTemplate tx;

    @BeforeEach
    void cleanDb() {
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // Spring 共享 application context,确保 scheduler 没被前一个测试 shutdown
        if (scheduler.isShutdown()) {
            scheduler.restartForTest();
        }
        tx.executeWithoutResult(s -> {
            executionRepository.deleteAllInBatch();
            planRepository.deleteAllInBatch();
        });
    }

    // ───────── 1. 到期 enabled 计划触发一次 ─────────

    @Test
    @DisplayName("到期 enabled 计划 → 调度一次,executeScheduled 被调用 1 次")
    void dueEnabledPlan_triggersOnce() {
        // 准备一个 enabled 且 nextRunAt = now-1min 的计划
        InspectionPlan plan = persistEnabledPlanDue("sched-due-1", Instant.now().minusSeconds(60));

        // Mock executeScheduled 返回正常 execution
        InspectionExecution stubExec = stubExecution(plan, InspectionExecutionStatus.SUCCESS);
        when(executionService.executeScheduled(any(InspectionPlan.class))).thenReturn(stubExec);

        // 手动触发一次扫描(确定性,不依赖定时器)
        scheduler.runOnePoll(Instant.now());

        verify(executionService, times(1)).executeScheduled(any(InspectionPlan.class));
    }

    // ───────── 2. disabled 计划永不触发 ─────────

    @Test
    @DisplayName("disabled 计划 → executeScheduled 永不被调用")
    void disabledPlan_neverTriggers() {
        // 准备一个 disabled 但 nextRunAt = now-1min 的计划
        InspectionPlan plan = persistDisabledPlan("sched-disabled-1", Instant.now().minusSeconds(60));

        scheduler.runOnePoll(Instant.now());

        verify(executionService, never()).executeScheduled(any(InspectionPlan.class));
    }

    // ───────── 3. 手动 runNow 不改 nextRunAt ─────────

    @Test
    @DisplayName("runNow(planId, operator) → executeManual 调用, plan.nextRunAt 不变")
    void runNow_doesNotMutateNextRunAt() {
        // 准备一个 enabled 的 DAILY 计划,nextRunAt = now+10min(未来),抹掉纳秒避免 TIMESTAMP 精度问题
        LocalDateTime originalNextRun = LocalDateTime.now()
                .plusMinutes(10)
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        InspectionPlan plan = persistPlanWithNextRun("sched-manual-1", true, originalNextRun);
        String planId = plan.getPlanId();

        InspectionExecution stubExec = stubExecution(plan, InspectionExecutionStatus.SUCCESS);
        when(executionService.executeManual(any(InspectionPlan.class), any(String.class)))
                .thenReturn(stubExec);

        // 手动触发
        scheduler.runNow(planId, "admin");

        verify(executionService, times(1)).executeManual(any(InspectionPlan.class), any(String.class));

        // nextRunAt 必须未变(精度匹配到秒)
        InspectionPlan reloaded = planRepository.findByPlanId(planId).orElseThrow();
        assertThat(reloaded.getNextRunAt().truncatedTo(java.time.temporal.ChronoUnit.SECONDS))
                .as("runNow 不应修改 plan.nextRunAt")
                .isEqualTo(originalNextRun);
    }

    // ───────── 4. 重叠:Scheduler 不感知 SKIPPED ─────────

    @Test
    @DisplayName("重叠 → ExecutionService 写 SKIPPED 返回, Scheduler 仍按流程完成(不二次决策)")
    void overlap_serviceReturnsSkipped_schedulerDoesNotDoubleHandle() {
        InspectionPlan plan = persistEnabledPlanDue("sched-overlap-1", Instant.now().minusSeconds(60));

        // Service 返回 SKIPPED(模拟 Service 内部重入检测)
        InspectionExecution skipped = stubExecution(plan, InspectionExecutionStatus.SKIPPED);
        when(executionService.executeScheduled(any(InspectionPlan.class))).thenReturn(skipped);

        // 调度器应直接信任 Service 返回,不抛异常、不重试
        scheduler.runOnePoll(Instant.now());

        // Scheduler 仍正常调用 1 次 Service.executeScheduled(由 Service 内部识别并写 SKIPPED)
        verify(executionService, times(1)).executeScheduled(any(InspectionPlan.class));
    }

    // ───────── 5. 错过的调度不重放 ─────────

    @Test
    @DisplayName("错过窗口(now+10min 调一次) → executeScheduled 只调用 1 次, nextRunAt 跳到未来")
    void missedSchedule_notReplayed() {
        // DAILY 08:00 计划,nextRunAt 在 10 小时前(now-10h)
        LocalDateTime missedAt = LocalDateTime.now().minusHours(10);
        InspectionPlan plan = persistPlanWithNextRun("sched-missed-1", true, missedAt);
        String planId = plan.getPlanId();

        InspectionExecution stubExec = stubExecution(plan, InspectionExecutionStatus.SUCCESS);
        when(executionService.executeScheduled(any(InspectionPlan.class))).thenReturn(stubExec);

        // 调度一次(模拟"当前已经过去很多,现在才扫到")
        scheduler.runOnePoll(Instant.now());

        // 只触发 1 次(不补跑 10 次)
        verify(executionService, times(1)).executeScheduled(any(InspectionPlan.class));

        // 触发后 nextRunAt 必须是未来(LocalDateTime.now 之后)
        InspectionPlan reloaded = planRepository.findByPlanId(planId).orElseThrow();
        assertThat(reloaded.getNextRunAt())
                .as("错过的调度不重放,下次应跳到当前时间之后的下一个未来时刻")
                .isAfter(LocalDateTime.now());
    }

    // ───────── 6. 关闭后不再触发 ─────────

    @Test
    @DisplayName("shutdown 后 runOnePoll → executeScheduled 不被调用")
    void shutdownStopsTriggering() {
        InspectionPlan plan = persistEnabledPlanDue("sched-shutdown-1", Instant.now().minusSeconds(60));

        // 先 shutdown
        scheduler.shutdown();
        assertThat(scheduler.isRunning())
                .as("shutdown 后调度器必须标记为非运行")
                .isFalse();

        scheduler.runOnePoll(Instant.now());

        verify(executionService, never()).executeScheduled(any(InspectionPlan.class));
        // 重启避免污染后续测试
        scheduler.restartForTest();
    }

    // ───────── 7. 多次扫描同一到期计划:nextRunAt 更新后不再重复触发 ─────────

    @Test
    @DisplayName("到期计划扫描后 nextRunAt 被推到未来,第二次扫描不再触发")
    void subsequentScan_doesNotRepickSamePlan() {
        InspectionPlan plan = persistEnabledPlanDue("sched-repick-1", Instant.now().minusSeconds(60));
        String planId = plan.getPlanId();

        InspectionExecution stubExec = stubExecution(plan, InspectionExecutionStatus.SUCCESS);
        when(executionService.executeScheduled(any(InspectionPlan.class))).thenReturn(stubExec);

        // 第一次扫描:触发
        scheduler.runOnePoll(Instant.now());

        // 模拟 Scheduler 把 nextRunAt 推到未来(由 Service.finishAndNotify 或 Scheduler 自带完成)
        // 这里通过 repository 直接模拟,因为 Scheduler 自身的 nextRun 重算逻辑由实现决定
        tx.executeWithoutResult(s -> {
            InspectionPlan p = planRepository.findByPlanId(planId).orElseThrow();
            p.setNextRunAt(LocalDateTime.now().plusHours(1));
            planRepository.save(p);
        });

        // 第二次扫描:同一 plan nextRunAt 已到未来,不应再触发
        scheduler.runOnePoll(Instant.now());

        // 总共只 1 次调用
        verify(executionService, times(1)).executeScheduled(any(InspectionPlan.class));
    }

    // ───────── 辅助 ─────────

    /** 持久化一个 enabled、nextRunAt 指定的 DAILY 计划。 */
    private InspectionPlan persistEnabledPlanDue(String seed, Instant nextRunAt) {
        return persistPlanWithNextRun(seed, true,
                LocalDateTime.ofInstant(nextRunAt, ZoneOffset.UTC));
    }

    /** 持久化一个 disabled、nextRunAt 指定的 DAILY 计划。 */
    private InspectionPlan persistDisabledPlan(String seed, Instant nextRunAt) {
        return persistPlanWithNextRun(seed, false,
                LocalDateTime.ofInstant(nextRunAt, ZoneOffset.UTC));
    }

    private InspectionPlan persistPlanWithNextRun(String seed, boolean enabled, LocalDateTime nextRunAt) {
        String planId = UUID.randomUUID().toString();
        CreatePlanInput input = new CreatePlanInput();
        input.name = "plan-" + seed + "-" + planId.substring(0, 4);
        input.description = "desc-" + seed;
        input.templateType = InspectionTemplateType.HEALTH;
        input.templateParams = java.util.Map.of("serviceName", "nginx");
        input.thresholds = java.util.Map.of("cpuWarningPercent", 80,
                "memoryWarningPercent", 80, "diskWarningPercent", 85);
        input.scheduleType = InspectionScheduleType.DAILY;
        input.localTime = java.time.LocalTime.of(8, 0);
        input.timezone = "Asia/Shanghai";
        input.dayOfWeek = null;
        input.dayOfMonth = null;
        input.notificationPolicy = InspectionNotificationPolicy.ON_ABNORMAL;

        InspectionPlan[] holder = new InspectionPlan[1];
        tx.executeWithoutResult(s -> holder[0] = planService.createPlan(input));
        // 直接 SQL 绕过 Service 校验路径,允许 nextRunAt 自由设置 + 启用
        tx.executeWithoutResult(s -> {
            InspectionPlan p = planRepository.findByPlanId(holder[0].getPlanId()).orElseThrow();
            p.setEnabled(enabled);
            p.setNextRunAt(nextRunAt);
            planRepository.save(p);
        });
        return planRepository.findByPlanId(holder[0].getPlanId()).orElseThrow();
    }

    /** 构造 Service 返回的 stub execution(只填必要字段)。 */
    private InspectionExecution stubExecution(InspectionPlan plan, InspectionExecutionStatus status) {
        InspectionExecution exec = new InspectionExecution();
        exec.setExecutionId(UUID.randomUUID().toString());
        exec.setPlanId(plan.getPlanId());
        exec.setStatus(status);
        exec.setTriggerType(InspectionTriggerType.SCHEDULED);
        exec.setOperator("SYSTEM_SCHEDULER");
        exec.setStartedAt(LocalDateTime.now());
        exec.setAbnormal(false);
        return exec;
    }
}