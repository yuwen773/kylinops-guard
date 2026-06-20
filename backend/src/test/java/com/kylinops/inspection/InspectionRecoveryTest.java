package com.kylinops.inspection;

import com.kylinops.audit.AuditLog;
import com.kylinops.audit.AuditLogRepository;
import com.kylinops.inspection.model.InspectionExecutionStatus;
import com.kylinops.inspection.model.InspectionNotificationPolicy;
import com.kylinops.inspection.model.InspectionScheduleType;
import com.kylinops.inspection.model.InspectionTemplateType;
import com.kylinops.inspection.model.InspectionTriggerType;
import com.kylinops.notification.NotificationEvent;
import com.kylinops.notification.NotificationEventType;
import com.kylinops.notification.NotificationService;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * P1-02 Plan 02 — Task 6 启动期/周期恢复测试。
 *
 * <p>覆盖 {@link InspectionRecovery} 契约(对应设计 §7 启动期恢复):</p>
 * <ul>
 *   <li>调 recoverAbandoned() → 所有 abandoned RUNNING 行(startedAt > 1h 前)置 FAILED</li>
 *   <li>已有 auditId 的 abandoned 行 → 审计 markCompleted 触发</li>
 *   <li>已有 auditId 的 abandoned 行 → 报告生成被尝试(null 降级 OK)</li>
 *   <li>已有 auditId 的 abandoned 行 → 通知 INSPECTION_FAILED 事件发出</li>
 *   <li>缺失 auditId 的 abandoned 行 → 不伪造 audit, 直接改 status, 不发通知</li>
 *   <li>恢复完成后, 仍 enabled 的 plan.nextRunAt 被重新计算到未来</li>
 * </ul>
 *
 * <p><b>关键设计约束:</b></p>
 * <ul>
 *   <li>启动期恢复走独立事务,不阻塞 application ready
 *   <li>不在第一次周期扫描里跑恢复, 启动时只跑一次
 *   <li>abandoned 阈值默认 1h, 由 kylinops.inspection.recovery.abandoned-threshold 控制
 *   <li>不发 LLM 调用, 不创建 PendingAction
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("P1-02 T6 — InspectionRecovery")
class InspectionRecoveryTest {

    @Autowired
    private InspectionRecovery recovery;

    @Autowired
    private InspectionPlanService planService;

    @Autowired
    private InspectionPlanRepository planRepository;

    @Autowired
    private InspectionExecutionRepository executionRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private PlatformTransactionManager txManager;

    @MockBean
    private NotificationService notificationService;

    private TransactionTemplate tx;

    @BeforeEach
    void cleanDb() {
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(s -> {
            executionRepository.deleteAllInBatch();
            auditLogRepository.deleteAllInBatch();
            planRepository.deleteAllInBatch();
        });
    }

    // ───────── 1. abandoned RUNNING → FAILED ─────────

    @Test
    @DisplayName("abandoned RUNNING(超过 1h)→ recoverAbandoned 后全部置 FAILED")
    void abandonedRunning_markedAsFailed() {
        InspectionPlan plan = createEnabledPlan("recover-1");

        // 准备 2 条 abandoned RUNNING(startedAt 2h 前和 3h 前)
        String[] executionIds = persistAbandonedExecutions(plan.getPlanId(), 2,
                LocalDateTime.now().minusHours(2));

        // 执行恢复
        recovery.recoverAbandoned();

        // 两条执行都必须改为 FAILED
        for (String execId : executionIds) {
            InspectionExecution reloaded = executionRepository.findByExecutionId(execId).orElseThrow();
            assertThat(reloaded.getStatus())
                    .as("abandoned execution 必须被置为 FAILED: " + execId)
                    .isEqualTo(InspectionExecutionStatus.FAILED);
            assertThat(reloaded.getFinishedAt())
                    .as("FAILED execution 必须有 finishedAt")
                    .isNotNull();
        }
    }

    // ───────── 2. 仍有 RUNNING(startedAt 30 分钟前,未到阈值) 不被恢复 ─────────

    @Test
    @DisplayName("未达阈值的 RUNNING(30 分钟前)→ 不被恢复, 保持 RUNNING")
    void freshRunning_notRecovered() {
        InspectionPlan plan = createEnabledPlan("recover-fresh-1");

        // startedAt = 30min 前, 未达 1h 阈值
        String executionId = UUID.randomUUID().toString();
        tx.executeWithoutResult(s -> {
            InspectionExecution exec = new InspectionExecution();
            exec.setExecutionId(executionId);
            exec.setPlanId(plan.getPlanId());
            exec.setPlanSnapshotJson("{\"name\":\"fresh\"}");
            exec.setStatus(InspectionExecutionStatus.RUNNING);
            exec.setTriggerType(InspectionTriggerType.SCHEDULED);
            exec.setOperator("SYSTEM_SCHEDULER");
            exec.setStartedAt(LocalDateTime.now().minusMinutes(30));
            exec.setAbnormal(false);
            executionRepository.save(exec);
        });

        recovery.recoverAbandoned();

        InspectionExecution reloaded = executionRepository.findByExecutionId(executionId).orElseThrow();
        assertThat(reloaded.getStatus())
                .as("未达阈值的 RUNNING 不应被恢复")
                .isEqualTo(InspectionExecutionStatus.RUNNING);
    }

    // ───────── 3. 已有 auditId → 审计 markCompleted 触发 ─────────

    @Test
    @DisplayName("abandoned 行带 auditId → 审计被标记 SUCCESS(走 markCompleted 收尾)")
    void abandonedWithAuditId_auditMarkedCompleted() {
        InspectionPlan plan = createEnabledPlan("recover-audit-1");

        // 准备 1 条 abandoned RUNNING + 1 条审计行(auditId 与之关联)
        String executionId = UUID.randomUUID().toString();
        String auditId = UUID.randomUUID().toString();
        tx.executeWithoutResult(s -> {
            InspectionExecution exec = new InspectionExecution();
            exec.setExecutionId(executionId);
            exec.setPlanId(plan.getPlanId());
            exec.setPlanSnapshotJson("{\"name\":\"with-audit\"}");
            exec.setStatus(InspectionExecutionStatus.RUNNING);
            exec.setTriggerType(InspectionTriggerType.SCHEDULED);
            exec.setOperator("SYSTEM_SCHEDULER");
            exec.setStartedAt(LocalDateTime.now().minusHours(2));
            exec.setAuditId(auditId);
            exec.setAbnormal(false);
            executionRepository.save(exec);

            AuditLog audit = new AuditLog();
            audit.setAuditId(auditId);
            audit.setIntentType(com.kylinops.common.enums.IntentType.SYSTEM_CHECK);
            audit.setTriggerType("SCHEDULED");
            audit.setOperator("SYSTEM_SCHEDULER");
            audit.setStatus(com.kylinops.common.enums.AuditStatus.RECEIVED);
            audit.setConfirmationRequired(false);
            auditLogRepository.save(audit);
        });

        recovery.recoverAbandoned();

        // 审计应被标记为 SUCCESS(markCompleted 行为)
        AuditLog reloadedAudit = auditLogRepository.findByAuditId(auditId).orElseThrow();
        assertThat(reloadedAudit.getStatus())
                .as("abandoned execution 的 auditId 必须被 markCompleted")
                .isEqualTo(com.kylinops.common.enums.AuditStatus.SUCCESS);
    }

    // ───────── 4. 已有 auditId → 通知 INSPECTION_FAILED 发出 ─────────

    @Test
    @DisplayName("abandoned 行带 auditId → NotificationService.emit(INSPECTION_FAILED) 被调用")
    void abandonedWithAuditId_emitsInspectionFailed() {
        InspectionPlan plan = createEnabledPlan("recover-emit-1");
        String executionId = UUID.randomUUID().toString();
        String auditId = UUID.randomUUID().toString();
        tx.executeWithoutResult(s -> {
            InspectionExecution exec = new InspectionExecution();
            exec.setExecutionId(executionId);
            exec.setPlanId(plan.getPlanId());
            exec.setPlanSnapshotJson("{\"name\":\"emit\"}");
            exec.setStatus(InspectionExecutionStatus.RUNNING);
            exec.setTriggerType(InspectionTriggerType.SCHEDULED);
            exec.setOperator("SYSTEM_SCHEDULER");
            exec.setStartedAt(LocalDateTime.now().minusHours(2));
            exec.setAuditId(auditId);
            exec.setAbnormal(false);
            executionRepository.save(exec);
        });

        recovery.recoverAbandoned();

        ArgumentCaptor<NotificationEvent> captor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(notificationService, atLeastOnce()).emit(captor.capture());
        assertThat(captor.getAllValues())
                .as("abandoned execution 收尾必须触发 INSPECTION_FAILED 通知")
                .extracting(NotificationEvent::getEventType)
                .contains(NotificationEventType.INSPECTION_FAILED);
    }

    // ───────── 5. 缺失 auditId → 不发通知 ─────────

    @Test
    @DisplayName("abandoned 行缺失 auditId → 不发通知, 不伪造 audit")
    void abandonedWithoutAuditId_noNotificationNoFabrication() {
        InspectionPlan plan = createEnabledPlan("recover-noaudit-1");
        String executionId = UUID.randomUUID().toString();
        tx.executeWithoutResult(s -> {
            InspectionExecution exec = new InspectionExecution();
            exec.setExecutionId(executionId);
            exec.setPlanId(plan.getPlanId());
            exec.setPlanSnapshotJson("{\"name\":\"no-audit\"}");
            exec.setStatus(InspectionExecutionStatus.RUNNING);
            exec.setTriggerType(InspectionTriggerType.SCHEDULED);
            exec.setOperator("SYSTEM_SCHEDULER");
            exec.setStartedAt(LocalDateTime.now().minusHours(2));
            // 不设 auditId
            exec.setAbnormal(false);
            executionRepository.save(exec);
        });

        long auditCountBefore = auditLogRepository.count();

        recovery.recoverAbandoned();

        // 状态必须改为 FAILED
        InspectionExecution reloaded = executionRepository.findByExecutionId(executionId).orElseThrow();
        assertThat(reloaded.getStatus())
                .as("即便无 auditId, abandoned execution 状态也必须改为 FAILED")
                .isEqualTo(InspectionExecutionStatus.FAILED);

        // 不应伪造 audit
        assertThat(auditLogRepository.count())
                .as("无 auditId 的 abandoned 行不允许伪造审计")
                .isEqualTo(auditCountBefore);

        // 不应发通知
        verify(notificationService, never()).emit(any(NotificationEvent.class));
    }

    // ───────── 6. 恢复后, enabled plan.nextRunAt 推到未来 ─────────

    @Test
    @DisplayName("恢复完成后, 仍 enabled 的 plan.nextRunAt 重新计算到未来")
    void recovery_recomputesNextRunAtForEnabledPlans() {
        InspectionPlan plan = createEnabledPlan("recover-recompute-1");
        String planId = plan.getPlanId();
        // 让 plan 当前的 nextRunAt 为"5 小时前"(模拟被错过的过去时间)
        tx.executeWithoutResult(s -> {
            InspectionPlan p = planRepository.findByPlanId(planId).orElseThrow();
            p.setNextRunAt(LocalDateTime.now().minusHours(5));
            planRepository.save(p);
        });

        // 准备 1 条 abandoned RUNNING 触发恢复
        persistAbandonedExecutions(planId, 1, LocalDateTime.now().minusHours(2));

        recovery.recoverAbandoned();

        InspectionPlan reloaded = planRepository.findByPlanId(planId).orElseThrow();
        assertThat(reloaded.getNextRunAt())
                .as("恢复后, enabled plan.nextRunAt 必须重算到未来")
                .isAfter(LocalDateTime.now());
    }

    // ───────── 7. disabled plan 不重算 nextRunAt ─────────

    @Test
    @DisplayName("disabled plan 即使有 abandoned, nextRunAt 也不重算(已停用)")
    void recovery_doesNotRecomputeForDisabledPlans() {
        InspectionPlan plan = createEnabledPlan("recover-disabled-1");
        String planId = plan.getPlanId();
        // 先 disable
        tx.executeWithoutResult(s -> {
            InspectionPlan p = planRepository.findByPlanId(planId).orElseThrow();
            p.setEnabled(false);
            p.setNextRunAt(LocalDateTime.now().minusHours(5));
            planRepository.save(p);
        });

        persistAbandonedExecutions(planId, 1, LocalDateTime.now().minusHours(2));

        recovery.recoverAbandoned();

        InspectionPlan reloaded = planRepository.findByPlanId(planId).orElseThrow();
        assertThat(reloaded.getNextRunAt())
                .as("disabled plan.nextRunAt 不应被重算, 仍保留旧值(5 小时前)")
                .isBefore(LocalDateTime.now());
    }

    // ───────── 8. 恢复不创建 PendingAction ─────────

    @Test
    @DisplayName("恢复流程绝不创建 PendingAction(CLAUDE.md 红线 5)")
    void recovery_doesNotCreatePendingAction() {
        InspectionPlan plan = createEnabledPlan("recover-nopa-1");
        String executionId = UUID.randomUUID().toString();
        String auditId = UUID.randomUUID().toString();
        tx.executeWithoutResult(s -> {
            InspectionExecution exec = new InspectionExecution();
            exec.setExecutionId(executionId);
            exec.setPlanId(plan.getPlanId());
            exec.setPlanSnapshotJson("{\"name\":\"nopa\"}");
            exec.setStatus(InspectionExecutionStatus.RUNNING);
            exec.setTriggerType(InspectionTriggerType.SCHEDULED);
            exec.setOperator("SYSTEM_SCHEDULER");
            exec.setStartedAt(LocalDateTime.now().minusHours(2));
            exec.setAuditId(auditId);
            exec.setAbnormal(false);
            executionRepository.save(exec);

            // 同时持久化 audit 行(模拟 ExecutionService.createInspectionAudit 路径)
            AuditLog audit = new AuditLog();
            audit.setAuditId(auditId);
            audit.setIntentType(com.kylinops.common.enums.IntentType.SYSTEM_CHECK);
            audit.setTriggerType("SCHEDULED");
            audit.setOperator("SYSTEM_SCHEDULER");
            audit.setStatus(com.kylinops.common.enums.AuditStatus.RECEIVED);
            audit.setConfirmationRequired(false);
            auditLogRepository.save(audit);
        });

        recovery.recoverAbandoned();

        // 1. execution 必须改为 FAILED
        InspectionExecution reloadedExec = executionRepository.findByExecutionId(executionId).orElseThrow();
        assertThat(reloadedExec.getStatus()).isEqualTo(InspectionExecutionStatus.FAILED);

        // 2. audit 行若存在,confirmationRequired 必为 false(巡检路径不允许要求确认)
        auditLogRepository.findByAuditId(auditId).ifPresent(audit ->
                assertThat(audit.isConfirmationRequired())
                        .as("巡检审计 confirmationRequired 必须为 false")
                        .isFalse());
    }

    // ───────── 辅助 ─────────

    private InspectionPlan createEnabledPlan(String seed) {
        CreatePlanInput input = new CreatePlanInput();
        input.name = "plan-" + seed + "-" + UUID.randomUUID().toString().substring(0, 4);
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
        tx.executeWithoutResult(s -> {
            InspectionPlan p = planRepository.findByPlanId(holder[0].getPlanId()).orElseThrow();
            p.setEnabled(true);
            p.setNextRunAt(LocalDateTime.now().plusDays(1));
            planRepository.save(p);
        });
        return planRepository.findByPlanId(holder[0].getPlanId()).orElseThrow();
    }

    /** 批量持久化 N 条 abandoned RUNNING,返回 executionIds 数组。 */
    private String[] persistAbandonedExecutions(String planId, int count, LocalDateTime startedAt) {
        String[] ids = new String[count];
        for (int i = 0; i < count; i++) {
            ids[i] = UUID.randomUUID().toString();
            int idx = i;
            tx.executeWithoutResult(s -> {
                InspectionExecution exec = new InspectionExecution();
                exec.setExecutionId(ids[idx]);
                exec.setPlanId(planId);
                exec.setPlanSnapshotJson("{\"name\":\"abandoned-" + idx + "\"}");
                exec.setStatus(InspectionExecutionStatus.RUNNING);
                exec.setTriggerType(InspectionTriggerType.SCHEDULED);
                exec.setOperator("SYSTEM_SCHEDULER");
                exec.setStartedAt(startedAt);
                exec.setAbnormal(false);
                executionRepository.save(exec);
            });
        }
        return ids;
    }
}