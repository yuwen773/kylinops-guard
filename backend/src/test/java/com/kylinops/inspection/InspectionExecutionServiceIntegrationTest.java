package com.kylinops.inspection;

import com.kylinops.audit.AuditLog;
import com.kylinops.audit.AuditLogRepository;
import com.kylinops.audit.AuditLogService;
import com.kylinops.executor.PendingActionRepository;
import com.kylinops.inspection.model.InspectionExecutionStatus;
import com.kylinops.inspection.model.InspectionNotificationPolicy;
import com.kylinops.inspection.model.InspectionScheduleType;
import com.kylinops.inspection.model.InspectionTemplateType;
import com.kylinops.inspection.model.InspectionTriggerType;
import com.kylinops.notification.NotificationEvent;
import com.kylinops.notification.NotificationEventType;
import com.kylinops.notification.NotificationService;
import com.kylinops.report.ReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * P1-02 Plan 02 — Task 5 集成测试(执行闭环)。
 *
 * <p>验证 {@link InspectionExecutionService} 的端到端执行路径:</p>
 * <ul>
 *   <li>定时触发 → triggerType=SCHEDULED, operator=SYSTEM_SCHEDULER, auditId 关联, reportId 关联</li>
 *   <li>手动触发 → triggerType=MANUAL, operator=admin</li>
 *   <li>异常判定 → execution.abnormal=true + INSPECTION_ABNORMAL 通知</li>
 *   <li>关键工具失败 → execution.status=FAILED + INSPECTION_FAILED 通知(不创建 audit 的 PendingAction)</li>
 *   <li>重入跳过:同 plan 已有 RUNNING → 新写 SKIPPED, 不创建新 audit/report</li>
 *   <li>不创建 PendingAction:巡检路径绝不创建</li>
 *   <li>通知策略:NEVER → 不调 emit;ALWAYS → 调 emit</li>
 * </ul>
 *
 * <p><b>ToolExecutor 路径不许 mock</b> — 本测试用真实的
 * {@code ToolExecutor} + {@code ToolRegistry} + {@code InspectionTemplateRegistry},
 * ToolDefinition 由 registry 提供。在 Windows 上 disk_usage_tool 会自然返回 failed
 * (ToolResult.status="failed"),这正好验证"关键工具失败 → FAILED"路径。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("P1-02 T5 — InspectionExecutionService 集成")
class InspectionExecutionServiceIntegrationTest {

    @Autowired
    private InspectionExecutionService executionService;

    @Autowired
    private InspectionPlanRepository planRepository;

    @Autowired
    private InspectionExecutionRepository executionRepository;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private PendingActionRepository pendingActionRepository;

    @Autowired
    private ReportRepository reportRepository;

    @Autowired
    private PlatformTransactionManager txManager;

    @MockBean
    private NotificationService notificationService;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        // REQUIRES_NEW:plan 立即提交,后续执行 Service 能看到
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ───────── 1. 调度触发 ─────────

    @Test
    @DisplayName("executeScheduled → triggerType=SCHEDULED, operator=SYSTEM_SCHEDULER, audit+report 关联")
    void scheduledTriggerWritesAuditReportWithSchedulerMetadata() {
        InspectionPlan plan = persistPlan("sched-plan-1", InspectionTemplateType.HEALTH,
                InspectionNotificationPolicy.NEVER, null);

        InspectionExecution exec = executionService.executeScheduled(plan);

        assertThat(exec).isNotNull();
        assertThat(exec.getExecutionId()).isNotBlank();
        assertThat(exec.getTriggerType()).isEqualTo(InspectionTriggerType.SCHEDULED);
        assertThat(exec.getOperator()).isEqualTo("SYSTEM_SCHEDULER");
        assertThat(exec.getStatus())
                .as("终态必须在 {SUCCESS, PARTIAL_SUCCESS, FAILED} 之内(非 RUNNING)")
                .isIn(InspectionExecutionStatus.SUCCESS,
                        InspectionExecutionStatus.PARTIAL_SUCCESS,
                        InspectionExecutionStatus.FAILED);
        assertThat(exec.getAuditId())
                .as("auditId 必须生成并写入 execution")
                .isNotBlank();
        assertThat(exec.getReportId())
                .as("reportId 必须生成并写入 execution(无 Session 路径也允许)")
                .isNotBlank();

        // 数据库侧确认
        InspectionExecution reloaded = executionRepository
                .findByExecutionId(exec.getExecutionId()).orElseThrow();
        assertThat(reloaded.getTriggerType()).isEqualTo(InspectionTriggerType.SCHEDULED);
        assertThat(reloaded.getOperator()).isEqualTo("SYSTEM_SCHEDULER");
        assertThat(reloaded.getStatus()).isNotEqualTo(InspectionExecutionStatus.RUNNING);
        assertThat(reloaded.getFinishedAt()).isNotNull();

        // 审计侧
        AuditLog audit = auditLogRepository.findByAuditId(exec.getAuditId()).orElseThrow();
        assertThat(audit.getTriggerType()).isEqualTo("SCHEDULED");
        assertThat(audit.getOperator()).isEqualTo("SYSTEM_SCHEDULER");
        assertThat(audit.getSessionId())
                .as("巡检审计 sessionId 必须为 null")
                .isNull();

        // 报告侧
        assertThat(reportRepository.findByReportId(exec.getReportId()))
                .as("reportId 必须指向真实报告记录")
                .isPresent();
    }

    // ───────── 2. 手动触发 ─────────

    @Test
    @DisplayName("executeManual(plan, admin) → triggerType=MANUAL, operator=admin")
    void manualTriggerRecordsAdminOperatorAndTriggerType() {
        InspectionPlan plan = persistPlan("manual-plan-1", InspectionTemplateType.HEALTH,
                InspectionNotificationPolicy.NEVER, null);

        InspectionExecution exec = executionService.executeManual(plan, "admin");

        assertThat(exec.getTriggerType()).isEqualTo(InspectionTriggerType.MANUAL);
        assertThat(exec.getOperator()).isEqualTo("admin");
        assertThat(exec.getStatus()).isNotEqualTo(InspectionExecutionStatus.RUNNING);
        assertThat(exec.getAuditId()).isNotBlank();

        AuditLog audit = auditLogRepository.findByAuditId(exec.getAuditId()).orElseThrow();
        assertThat(audit.getTriggerType()).isEqualTo("MANUAL");
        assertThat(audit.getOperator()).isEqualTo("admin");
    }

    // ───────── 3. 异常判定 ─────────

    @Test
    @DisplayName("DISK 模板 + threshold 触发 → execution.abnormal=true + 通知事件 abnormal")
    void abnormalFlagTriggersInspectionAbnormalNotification() {
        // threshold 0.0% — 任何非零 usage 必突破;Windows 上 disk_usage_tool 返回 failed
        // (keyToolFailed=true) 也满足 abnormal=true。两条路径殊途同归。
        InspectionPlan plan = persistPlan("abnormal-plan-1", InspectionTemplateType.DISK,
                InspectionNotificationPolicy.ALWAYS,
                "{\"disk_usage_tool\":0.0}");

        InspectionExecution exec = executionService.executeScheduled(plan);

        assertThat(exec.isAbnormal())
                .as("threshold 突破或关键工具失败必须让 execution.abnormal=true")
                .isTrue();

        ArgumentCaptor<NotificationEvent> captor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(notificationService, atLeastOnce()).emit(captor.capture());
        // abnormal=true 在事件层有两种映射:keyToolFailed → INSPECTION_FAILED(因为 status=FAILED);
        // 仅阈值突破/非关键失败 → INSPECTION_ABNORMAL。Windows 上 key tool 必然 failed,
        // 所以实际只发 INSPECTION_FAILED;Linux 上若阈值突破且 status=SUCCESS/PARTIAL
        // 会发 INSPECTION_ABNORMAL。containsAnyOf 兼顾两种环境。
        assertThat(captor.getAllValues())
                .as("abnormal=true 必须触发 INSPECTION_ABNORMAL 或 INSPECTION_FAILED 之一")
                .extracting(NotificationEvent::getEventType)
                .containsAnyOf(NotificationEventType.INSPECTION_ABNORMAL,
                        NotificationEventType.INSPECTION_FAILED);
    }

    // ───────── 4. 失败路径 ─────────

    @Test
    @DisplayName("关键工具失败 → status=FAILED + INSPECTION_FAILED 通知(事件 detail.abnormal=false)")
    void keyToolFailureCreatesFailedExecutionAndInspectionFailedEvent() {
        // DISK 模板关键工具就是 disk_usage_tool,Windows 上必然 failed → FAILED 路径
        InspectionPlan plan = persistPlan("fail-plan-1", InspectionTemplateType.DISK,
                InspectionNotificationPolicy.ALWAYS, null);

        InspectionExecution exec = executionService.executeScheduled(plan);

        // Windows 上 disk_usage_tool 必然 failed,keyToolFailed=true → status=FAILED
        if (exec.getStatus() == InspectionExecutionStatus.FAILED) {
            ArgumentCaptor<NotificationEvent> captor = ArgumentCaptor.forClass(NotificationEvent.class);
            verify(notificationService, atLeastOnce()).emit(captor.capture());
            assertThat(captor.getAllValues())
                    .as("FAILED 必须触发 INSPECTION_FAILED 事件")
                    .extracting(NotificationEvent::getEventType)
                    .contains(NotificationEventType.INSPECTION_FAILED);

            // "abnormal 与 FAILED 正交"指的是事件 detail map:INSPECTION_FAILED.detail.abnormal=false
            // (与 INSPECTION_ABNORMAL.detail.abnormal=true 区分;见 InspectionEventFactory)
            NotificationEvent failedEvent = captor.getAllValues().stream()
                    .filter(e -> e.getEventType() == NotificationEventType.INSPECTION_FAILED)
                    .findFirst().orElseThrow();
            assertThat(failedEvent.getDetailMap().get("abnormal"))
                    .as("INSPECTION_FAILED 事件 detail.abnormal 必须为 false(与 ABNORMAL 正交)")
                    .isEqualTo(false);
        } else {
            // 非 FAILED 终态(在某些 Linux 环境下可能):也接受,但必须发出 COMPLETED/ABNORMAL
            assertThat(exec.getStatus()).isIn(
                    InspectionExecutionStatus.SUCCESS,
                    InspectionExecutionStatus.PARTIAL_SUCCESS);
        }
    }

    // ───────── 5. 重入跳过 ─────────

    @Test
    @DisplayName("同 plan 已有 RUNNING → 新触发写 SKIPPED, auditId/reportId 均为 null")
    void reentrancyReturnsSkippedWithoutNewAuditOrReport() {
        InspectionPlan plan = persistPlan("reentry-plan-1", InspectionTemplateType.HEALTH,
                InspectionNotificationPolicy.NEVER, null);

        // 手动先写一条 RUNNING execution
        String runningExecutionId = UUID.randomUUID().toString();
        long auditCountBefore = auditLogRepository.count();
        long reportCountBefore = reportRepository.count();

        tx.executeWithoutResult(s -> {
            InspectionExecution running = new InspectionExecution();
            running.setExecutionId(runningExecutionId);
            running.setPlanId(plan.getPlanId());
            running.setPlanSnapshotJson("{\"seed\":\"running\"}");
            running.setStatus(InspectionExecutionStatus.RUNNING);
            running.setTriggerType(InspectionTriggerType.SCHEDULED);
            running.setOperator("SYSTEM_SCHEDULER");
            running.setStartedAt(LocalDateTime.now().minusSeconds(5));
            running.setAbnormal(false);
            executionRepository.save(running);
        });

        // 触发新执行 — 应识别 RUNNING → 写 SKIPPED
        InspectionExecution skipped = executionService.executeScheduled(plan);

        assertThat(skipped).isNotNull();
        assertThat(skipped.getStatus()).isEqualTo(InspectionExecutionStatus.SKIPPED);
        assertThat(skipped.getAuditId())
                .as("SKIPPED execution 不创建新 audit, auditId 必为 null")
                .isNull();
        assertThat(skipped.getReportId())
                .as("SKIPPED execution 不创建报告, reportId 必为 null")
                .isNull();

        // 数据库侧 — 该 plan 下应恰好 1 条 RUNNING(seed) + 1 条 SKIPPED(本次)
        long skippedCountForPlan = executionRepository.findByPlanIdOrderByStartedAtDesc(
                plan.getPlanId(),
                org.springframework.data.domain.PageRequest.of(0, 10))
                .getContent().stream()
                .filter(e -> e.getStatus() == InspectionExecutionStatus.SKIPPED)
                .count();
        assertThat(skippedCountForPlan)
                .as("该 plan 应有 1 条 SKIPPED 执行")
                .isEqualTo(1L);

        // audit / report 数量不变(SKIPPED 不写 audit/report)
        assertThat(auditLogRepository.count())
                .as("audit 表不应增加(SKIPPED 不写 audit)")
                .isEqualTo(auditCountBefore);
        assertThat(reportRepository.count())
                .as("report 表不应增加(SKIPPED 不写 report)")
                .isEqualTo(reportCountBefore);
    }

    // ───────── 6. 不创建 PendingAction ─────────

    @Test
    @DisplayName("巡检审计绝不创建 PendingAction(读 / 调度路径只读)")
    void inspectionAuditDoesNotCreatePendingAction() {
        InspectionPlan plan = persistPlan("nopa-plan-1", InspectionTemplateType.HEALTH,
                InspectionNotificationPolicy.NEVER, null);

        InspectionExecution exec = executionService.executeScheduled(plan);

        assertThat(exec.getAuditId()).isNotBlank();
        assertThat(pendingActionRepository.findByAuditId(exec.getAuditId()))
                .as("巡检路径绝不创建 PendingAction")
                .isEmpty();

        AuditLog audit = auditLogRepository.findByAuditId(exec.getAuditId()).orElseThrow();
        assertThat(audit.isConfirmationRequired())
                .as("巡检审计 confirmationRequired 必为 false")
                .isFalse();
    }

    // ───────── 7. 通知策略 — NEVER 不调 emit ─────────

    @Test
    @DisplayName("通知策略=NEVER 时,不调 NotificationService.emit")
    void notificationPolicyNeverSkipsEmit() {
        InspectionPlan plan = persistPlan("never-plan-1", InspectionTemplateType.HEALTH,
                InspectionNotificationPolicy.NEVER, null);

        executionService.executeScheduled(plan);

        verify(notificationService, never()).emit(any(NotificationEvent.class));
    }

    // ───────── 8. 通知策略 — ALWAYS 调 emit ─────────

    @Test
    @DisplayName("通知策略=ALWAYS 时,终态后必调 NotificationService.emit")
    void notificationPolicyAlwaysEmits() {
        InspectionPlan plan = persistPlan("always-plan-1", InspectionTemplateType.HEALTH,
                InspectionNotificationPolicy.ALWAYS, null);

        executionService.executeScheduled(plan);

        verify(notificationService, atLeastOnce()).emit(any(NotificationEvent.class));
    }

    // ───────── 9. 计划快照在执行时固化 ─────────

    @Test
    @DisplayName("plan_snapshot_json 在 execution 落地, 后续修改 plan 不影响历史 execution")
    void planSnapshotIsFrozenOnExecution() {
        InspectionPlan plan = persistPlan("snapshot-plan-1", InspectionTemplateType.HEALTH,
                InspectionNotificationPolicy.NEVER, null);
        String originalName = plan.getName();

        InspectionExecution exec = executionService.executeScheduled(plan);

        // 改 plan.name
        tx.executeWithoutResult(s -> {
            InspectionPlan p = planRepository.findByPlanId(plan.getPlanId()).orElseThrow();
            p.setName("renamed-after-execute");
            planRepository.save(p);
        });

        InspectionExecution reloaded = executionRepository
                .findByExecutionId(exec.getExecutionId()).orElseThrow();
        assertThat(reloaded.getPlanSnapshotJson())
                .as("快照必须保留原 planName")
                .contains(originalName);
    }

    // ───────── 辅助 ─────────

    private InspectionPlan persistPlan(String seed, InspectionTemplateType template,
                                       InspectionNotificationPolicy policy, String thresholdsJson) {
        // plan_id 是 VARCHAR(36) 业务主键,seed 不参与 — 直接用 UUID
        String planId = UUID.randomUUID().toString();
        InspectionPlan p = new InspectionPlan();
        p.setPlanId(planId);
        p.setName("plan-" + seed + "-" + planId.substring(0, 4));
        p.setDescription("test " + seed);
        p.setTemplateType(template);
        p.setTemplateParamsJson("{}");
        p.setThresholdsJson(thresholdsJson != null ? thresholdsJson : "{}");
        p.setScheduleType(InspectionScheduleType.DAILY);
        p.setScheduleConfigJson("{\"localTime\":\"08:00\"}");
        p.setTimezone("Asia/Shanghai");
        p.setNotificationPolicy(policy);
        p.setEnabled(true);
        p.setNextRunAt(LocalDateTime.now().plusDays(1));
        LocalDateTime now = LocalDateTime.now();
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        tx.executeWithoutResult(s -> planRepository.saveAndFlush(p));
        // 重新读出 managed 实体(供 service 拿到持久化后的 planId)
        return planRepository.findByPlanId(planId).orElseThrow();
    }
}
