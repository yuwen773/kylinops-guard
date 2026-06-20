package com.kylinops.inspection;

import com.kylinops.audit.AuditLogService;
import com.kylinops.inspection.model.InspectionExecutionStatus;
import com.kylinops.inspection.model.InspectionNotificationPolicy;
import com.kylinops.inspection.model.InspectionTriggerType;
import com.kylinops.notification.NotificationEvent;
import com.kylinops.notification.NotificationEventFactory;
import com.kylinops.notification.NotificationEventType;
import com.kylinops.notification.NotificationService;
import com.kylinops.report.ReportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 启动期 / 周期 abandoned 执行恢复(P1-02 Task 6)。
 *
 * <h3>职责</h3>
 * <p>在应用启动后 + 调度周期初,扫描所有 RUNNING 执行,若 startedAt 距今超过
 * {@code kylinops.inspection.recovery.abandoned-threshold}(默认 1h)即视为
 * abandoned(进程崩溃 / 异常终止)。</p>
 *
 * <h3>收尾流程</h3>
 * <ol>
 *   <li>status: RUNNING → FAILED, finishedAt = now, summary = "执行进程异常终止"</li>
 *   <li>若 auditId 存在:markCompleted 收尾 + 尝试生成报告(null 降级 OK)</li>
 *   <li>若 auditId 存在 + 通知策略允许:emit INSPECTION_FAILED 事件</li>
 *   <li>若 auditId 缺失:不伪造审计, 不发通知</li>
 *   <li>对仍 enabled 的 plan 重算 nextRunAt; disabled plan 不动</li>
 * </ol>
 *
 * <h3>设计约束</h3>
 * <ul>
 *   <li>启动期恢复走 REQUIRES_NEW 独立事务, 不阻塞 application ready
 *   <li>不创建 PendingAction / Session / Message(CLAUDE.md 红线 5)
 *   <li>不调用 LLM
 * </ul>
 */
@Slf4j
@Component
public class InspectionRecovery {

    private final InspectionExecutionRepository executionRepository;
    private final InspectionPlanRepository planRepository;
    private final InspectionPlanService planService;
    private final AuditLogService auditLogService;
    private final ReportService reportService;
    private final NotificationService notificationService;
    private final NotificationEventFactory eventFactory;
    private final InspectionProperties properties;
    private final Clock clock;

    public InspectionRecovery(InspectionExecutionRepository executionRepository,
                               InspectionPlanRepository planRepository,
                               InspectionPlanService planService,
                               AuditLogService auditLogService,
                               ReportService reportService,
                               NotificationService notificationService,
                               NotificationEventFactory eventFactory,
                               InspectionProperties properties,
                               Clock clock) {
        this.executionRepository = executionRepository;
        this.planRepository = planRepository;
        this.planService = planService;
        this.auditLogService = auditLogService;
        this.reportService = reportService;
        this.notificationService = notificationService;
        this.eventFactory = eventFactory;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 启动期 / 周期恢复。独立事务避免污染调用方。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recoverAbandoned() {
        Instant now = clock.instant();
        // 持久化字段全部为 LocalDateTime, 与系统时钟 / 其它服务(plan.nextRunAt 等)保持同一时区语义。
        // 转换用 system default zone, 避免跨时区比较语义错位(设计 §5.1 nextRunAt/lastRunAt 是 UTC,
        // 但与 existing startedAt 同字段类型同 JVM 时区写入, 直接用 LocalDateTime.now() 保持一致)。
        LocalDateTime nowLdt = LocalDateTime.ofInstant(now,
                java.time.ZoneId.systemDefault());
        // abandoned 阈值: 默认 1h
        LocalDateTime threshold = nowLdt.minus(
                java.time.Duration.of(properties.getRecovery().getAbandonedThreshold().toMillis(),
                        java.time.temporal.ChronoUnit.MILLIS));

        List<InspectionExecution> running = executionRepository.findAllByStatus(
                InspectionExecutionStatus.RUNNING);
        if (running.isEmpty()) {
            log.debug("启动期恢复: 无遗留 RUNNING 执行");
            return;
        }

        int recovered = 0;
        for (InspectionExecution exec : running) {
            if (exec.getStartedAt() == null || exec.getStartedAt().isAfter(threshold)) {
                // 未达阈值, 跳过
                continue;
            }
            try {
                recoverOne(exec, nowLdt);
                recovered++;
            } catch (Exception e) {
                log.error("abandoned 执行恢复失败: executionId={}", exec.getExecutionId(), e);
            }
        }
        log.info("启动期恢复: 共扫描 {} 条 RUNNING, 恢复 {} 条", running.size(), recovered);
    }

    /**
     * 单条 abandoned 执行的收尾流程。
     */
    private void recoverOne(InspectionExecution exec, LocalDateTime now) {
        exec.setStatus(InspectionExecutionStatus.FAILED);
        exec.setFinishedAt(now);
        exec.setSummary("执行进程异常终止(abandoned)");

        // 找 plan 以决定通知策略 + 重算 nextRunAt
        InspectionPlan plan = planRepository.findByPlanId(exec.getPlanId()).orElse(null);

        // 1. 收尾审计(若 auditId 存在)
        String auditId = exec.getAuditId();
        if (auditId != null && !auditId.isBlank()) {
            try {
                auditLogService.markCompleted(auditId);
            } catch (Exception e) {
                log.warn("abandoned 审计 markCompleted 失败: auditId={}", auditId, e);
            }
            // 2. 尝试生成报告(null 降级 OK, 不阻断)
            if (plan != null && plan.getTemplateType() != null) {
                String reportId = reportService.generateFromInspectionAudit(
                        auditId, plan.getTemplateType());
                if (reportId != null) {
                    exec.setReportId(reportId);
                }
            }
            // 3. 通知 INSPECTION_FAILED
            if (plan != null) {
                emitRecoveryNotification(plan, exec, auditId);
            }
        } else {
            log.warn("abandoned execution 无 auditId, 不伪造审计也不发通知: executionId={}",
                    exec.getExecutionId());
        }

        // 持久化终态
        executionRepository.save(exec);

        // 4. 重算 nextRunAt(对仍 enabled 的 plan)
        if (plan != null && plan.isEnabled()) {
            try {
                plan.setLastRunAt(now);
                plan.setNextRunAt(planService.computeNextRunAt(plan, Instant.now()));
                planRepository.save(plan);
            } catch (Exception e) {
                log.warn("abandoned 恢复后重算 nextRunAt 失败: planId={}", plan.getPlanId(), e);
            }
        }
    }

    /**
     * 发送恢复通知。检查通知策略:NEVER → 不发;ALWAYS → 发;ON_ABNORMAL → 发(因为 FAILED 必视为异常信号)
     */
    private void emitRecoveryNotification(InspectionPlan plan, InspectionExecution exec,
                                           String auditId) {
        InspectionNotificationPolicy policy = plan.getNotificationPolicy();
        if (policy == null || policy == InspectionNotificationPolicy.NEVER) {
            return;
        }
        try {
            NotificationEvent event = eventFactory.createInspectionEvent(
                    NotificationEventType.INSPECTION_FAILED,
                    auditId,
                    safe(plan.getName()),
                    plan.getTemplateType() != null ? plan.getTemplateType().name() : "UNKNOWN",
                    exec.getStatus().name(),
                    safe(exec.getSummary()),
                    exec.getReportId());
            notificationService.emit(event);
        } catch (Exception e) {
            log.warn("abandoned 通知 emit 失败(已记录 execution.status): executionId={}",
                    exec.getExecutionId(), e);
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}