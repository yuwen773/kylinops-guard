package com.kylinops.inspection;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kylinops.audit.AuditLogService;
import com.kylinops.common.enums.IntentType;
import com.kylinops.inspection.model.InspectionExecutionStatus;
import com.kylinops.inspection.model.InspectionNotificationPolicy;
import com.kylinops.inspection.model.InspectionTemplateType;
import com.kylinops.inspection.model.InspectionTriggerType;
import com.kylinops.notification.NotificationEvent;
import com.kylinops.notification.NotificationEventFactory;
import com.kylinops.notification.NotificationEventType;
import com.kylinops.notification.NotificationService;
import com.kylinops.report.ReportService;
import com.kylinops.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 巡检执行服务(P1-02 Task 5) — 巡检路径的只读执行闭环入口。
 *
 * <h3>主入口</h3>
 * <ul>
 *   <li>{@link #executeScheduled(InspectionPlan)} — 调度器调用(SCHEDULED + SYSTEM_SCHEDULER)</li>
 *   <li>{@link #executeManual(InspectionPlan, String)} — 管理员手动触发(MANUAL + admin)</li>
 * </ul>
 *
 * <h3>执行闭环</h3>
 * <ol>
 *   <li>重入检测:同 plan 已有 {@code RUNNING} → 写 {@code SKIPPED} 返回,不创建 audit/report</li>
 *   <li>创建 {@code RUNNING} execution + 固化 plan snapshot</li>
 *   <li>调 {@link AuditLogService#createInspectionAudit} 写入巡检审计行</li>
 *   <li>调 {@link InspectionTemplateRegistry} 拿模板,执行 stages</li>
 *   <li>调 {@link InspectionResultEvaluator} 判定 abnormal</li>
 *   <li>调 {@link ReportService#generateFromInspectionAudit} 关联 reportId(降级 null)</li>
 *   <li>根据 keyToolFailed / nonKeyFailed 决定终态(SUCCESS / PARTIAL_SUCCESS / FAILED)</li>
 *   <li>按 {@link InspectionNotificationPolicy} 触发通知事件</li>
 * </ol>
 *
 * <h3>安全红线(CLAUDE.md)</h3>
 * <ul>
 *   <li>绝不创建 {@code PendingAction} — 巡检是只读路径</li>
 *   <li>巡检审计 sessionId=null,triggerType + operator 写明来源</li>
 *   <li>任何工具异常被 StageExecutor 隔离,execution.status 永远收敛到终态</li>
 * </ul>
 */
@Slf4j
@Service
public class InspectionExecutionService {

    private final InspectionExecutionRepository executionRepository;
    private final InspectionTemplateRegistry templateRegistry;
    private final InspectionStageExecutor stageExecutor;
    private final InspectionResultEvaluator evaluator;
    private final AuditLogService auditLogService;
    private final ReportService reportService;
    private final NotificationEventFactory eventFactory;
    private final NotificationService notificationService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public InspectionExecutionService(InspectionExecutionRepository executionRepository,
                                       InspectionTemplateRegistry templateRegistry,
                                       InspectionStageExecutor stageExecutor,
                                       InspectionResultEvaluator evaluator,
                                       AuditLogService auditLogService,
                                       ReportService reportService,
                                       NotificationEventFactory eventFactory,
                                       NotificationService notificationService) {
        this.executionRepository = executionRepository;
        this.templateRegistry = templateRegistry;
        this.stageExecutor = stageExecutor;
        this.evaluator = evaluator;
        this.auditLogService = auditLogService;
        this.reportService = reportService;
        this.eventFactory = eventFactory;
        this.notificationService = notificationService;
    }

    /** 调度器入口 — {@code triggerType=SCHEDULED, operator=SYSTEM_SCHEDULER}。 */
    @Transactional
    public InspectionExecution executeScheduled(InspectionPlan plan) {
        return executeInternal(plan, InspectionTriggerType.SCHEDULED, "SYSTEM_SCHEDULER");
    }

    /** 手动入口 — {@code triggerType=MANUAL, operator=<admin username>}。 */
    @Transactional
    public InspectionExecution executeManual(InspectionPlan plan, String operator) {
        String safeOperator = (operator == null || operator.isBlank()) ? "admin" : operator;
        return executeInternal(plan, InspectionTriggerType.MANUAL, safeOperator);
    }

    // ==================== 查询 API(P1-02 Task 7) ====================

    /**
     * 按 executionId 查询执行记录。P1-02 Task 7 详情 API 用 —
     * 由 controller 把 {@code Optional.empty()} 映射为 HTTP 404。
     */
    @Transactional(readOnly = true)
    public java.util.Optional<InspectionExecution> findExecution(String executionId) {
        if (executionId == null || executionId.isBlank()) {
            return java.util.Optional.empty();
        }
        return executionRepository.findByExecutionId(executionId);
    }

    /**
     * 列出执行记录,支持按 planId / status 过滤(均可选),分页。
     *
     * <p>实现说明:Repository 已有 {@code findByPlanIdOrderByStartedAtDesc} 支持
     * plan 过滤;若 status 与 planId 同时给出,先按 plan 过滤再内存中按 status 过滤
     * (数据量小,避免引入复合索引;P1-02 MVP 阶段可接受)。</p>
     */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<InspectionExecution> listExecutions(
            String planId,
            com.kylinops.inspection.model.InspectionExecutionStatus status,
            org.springframework.data.domain.Pageable pageable) {
        org.springframework.data.domain.Page<InspectionExecution> base;
        if (planId != null && !planId.isBlank()) {
            base = executionRepository.findByPlanIdOrderByStartedAtDesc(planId, pageable);
        } else {
            // 全部:按 started_at DESC 排序
            org.springframework.data.domain.PageRequest sorted = org.springframework.data.domain.PageRequest.of(
                    pageable.getPageNumber(),
                    pageable.getPageSize(),
                    org.springframework.data.domain.Sort.by(
                            org.springframework.data.domain.Sort.Direction.DESC, "startedAt"));
            base = executionRepository.findAll(sorted);
        }
        if (status == null) {
            return base;
        }
        // 内存过滤 status(数据量小,可接受)
        java.util.List<InspectionExecution> filtered = base.getContent().stream()
                .filter(e -> e.getStatus() == status)
                .toList();
        return new org.springframework.data.domain.PageImpl<>(
                filtered, pageable, filtered.size());
    }

    // ==================== 内部主流程 ====================

    private InspectionExecution executeInternal(InspectionPlan plan,
                                                  InspectionTriggerType triggerType,
                                                  String operator) {
        if (plan == null || plan.getPlanId() == null) {
            throw new IllegalArgumentException("巡检计划不能为空");
        }

        // 1. 重入检测:同 plan 已有 RUNNING → 写 SKIPPED 直接返回
        if (executionRepository.existsByPlanIdAndStatus(
                plan.getPlanId(), InspectionExecutionStatus.RUNNING)) {
            InspectionExecution skipped = new InspectionExecution();
            skipped.setExecutionId(UUID.randomUUID().toString());
            skipped.setPlanId(plan.getPlanId());
            skipped.setStatus(InspectionExecutionStatus.SKIPPED);
            skipped.setTriggerType(triggerType);
            skipped.setOperator(operator);
            skipped.setStartedAt(LocalDateTime.now());
            skipped.setFinishedAt(LocalDateTime.now());
            skipped.setAbnormal(false);
            skipped.setSummary("已有 RUNNING 执行,本次跳过");
            return executionRepository.save(skipped);
        }

        // 2. 创建 RUNNING execution + 固化 plan snapshot
        InspectionExecution exec = new InspectionExecution();
        exec.setExecutionId(UUID.randomUUID().toString());
        exec.setPlanId(plan.getPlanId());
        exec.setPlanSnapshotJson(buildPlanSnapshot(plan));
        exec.setStatus(InspectionExecutionStatus.RUNNING);
        exec.setTriggerType(triggerType);
        exec.setOperator(operator);
        exec.setStartedAt(LocalDateTime.now());
        exec.setAbnormal(false);
        exec = executionRepository.save(exec);

        // 3. 创建巡检审计
        String auditId = UUID.randomUUID().toString();
        IntentType intent = mapTemplateToIntent(plan.getTemplateType());
        auditLogService.createInspectionAudit(
                auditId, intent, triggerType.name(), operator,
                "巡检执行: " + safe(plan.getName()));
        exec.setAuditId(auditId);

        // 4. 加载模板
        InspectionTemplateDefinition template = templateRegistry
                .getTemplate(plan.getTemplateType(), parseParams(plan.getTemplateParamsJson()))
                .orElse(null);

        if (template == null) {
            // 模板缺失(registry 拒绝)— 标记 FAILED,不发 COMPLETED 事件
            log.warn("巡检模板缺失,FAILED: planId={}, templateType={}",
                    plan.getPlanId(), plan.getTemplateType());
            return finishAndNotify(exec, plan,
                    InspectionExecutionStatus.FAILED,
                    "巡检模板缺失: " + plan.getTemplateType(),
                    /*abnormal=*/ false,
                    /*results=*/ List.of());
        }

        // 5. 顺序执行 stages(异常由 StageExecutor 内部隔离为 failed ToolResult)
        Map<String, Object> params = parseParams(plan.getTemplateParamsJson());
        List<ToolResult> results = stageExecutor.executeStages(template, params, auditId);

        // 6. 评估异常
        Map<String, Double> thresholds = parseThresholds(plan.getThresholdsJson());
        AbnormalVerdict verdict = evaluator.evaluate(results, template, thresholds);

        // 7. 决定终态
        InspectionExecutionStatus finalStatus = decideStatus(verdict, results, template);
        String summary = buildSummary(verdict, results, finalStatus);

        return finishAndNotify(exec, plan, finalStatus, summary, verdict.isAbnormal(), results);
    }

    /**
     * 收尾流程:持久化终态 → 生成报告 → 完成审计 → 发通知。
     */
    private InspectionExecution finishAndNotify(InspectionExecution exec, InspectionPlan plan,
                                                 InspectionExecutionStatus finalStatus,
                                                 String summary,
                                                 boolean abnormal,
                                                 List<ToolResult> results) {
        exec.setStatus(finalStatus);
        exec.setFinishedAt(LocalDateTime.now());
        exec.setSummary(summary);
        exec.setAbnormal(abnormal);

        // 生成报告(可能降级为 null)
        if (exec.getAuditId() != null && plan.getTemplateType() != null) {
            String reportId = reportService.generateFromInspectionAudit(
                    exec.getAuditId(), plan.getTemplateType());
            if (reportId != null) {
                exec.setReportId(reportId);
            }
        }

        // 完成审计
        if (exec.getAuditId() != null) {
            try {
                auditLogService.markCompleted(exec.getAuditId());
            } catch (Exception e) {
                // markCompleted 失败不阻断执行流
                log.warn("审计 markCompleted 失败(已记录 execution.status): auditId={}",
                        exec.getAuditId(), e);
            }
        }

        // 持久化最终状态
        exec = executionRepository.save(exec);

        // 按通知策略触发事件
        NotificationEventType eventType = pickEventType(finalStatus, abnormal);
        emitInspectionEvent(plan, eventType, exec);

        log.debug("巡检执行完成: executionId={}, planId={}, status={}, abnormal={}, eventType={}",
                exec.getExecutionId(), plan.getPlanId(), finalStatus, abnormal, eventType);
        return exec;
    }

    // ==================== 决策逻辑 ====================

    /**
     * 终态决策:
     * <ul>
     *   <li>关键工具失败 → FAILED</li>
     *   <li>非关键工具失败 / 其它失败 → PARTIAL_SUCCESS</li>
     *   <li>全部 success → SUCCESS</li>
     * </ul>
     */
    private static InspectionExecutionStatus decideStatus(AbnormalVerdict verdict,
                                                           List<ToolResult> results,
                                                           InspectionTemplateDefinition template) {
        if (verdict.isKeyToolFailed()) {
            return InspectionExecutionStatus.FAILED;
        }
        if (results == null || results.isEmpty()) {
            return InspectionExecutionStatus.SUCCESS;
        }
        boolean anyFailure = false;
        for (ToolResult r : results) {
            if (r == null) continue;
            if (!r.isSuccess()) {
                anyFailure = true;
                break;
            }
        }
        return anyFailure ? InspectionExecutionStatus.PARTIAL_SUCCESS : InspectionExecutionStatus.SUCCESS;
    }

    /**
     * 事件类型选择 — 与 {@link NotificationEventFactory#createInspectionEvent} 的
     * detail.abnormal 字段正交:
     * <ul>
     *   <li>FAILED → INSPECTION_FAILED(事件 detail.abnormal=false)</li>
     *   <li>abnormal=true → INSPECTION_ABNORMAL(事件 detail.abnormal=true)</li>
     *   <li>其它 → INSPECTION_COMPLETED(事件 detail.abnormal=false)</li>
     * </ul>
     */
    private static NotificationEventType pickEventType(InspectionExecutionStatus status, boolean abnormal) {
        if (status == InspectionExecutionStatus.FAILED) {
            return NotificationEventType.INSPECTION_FAILED;
        }
        if (abnormal) {
            return NotificationEventType.INSPECTION_ABNORMAL;
        }
        return NotificationEventType.INSPECTION_COMPLETED;
    }

    // ==================== 通知 ====================

    private void emitInspectionEvent(InspectionPlan plan, NotificationEventType eventType,
                                      InspectionExecution exec) {
        InspectionNotificationPolicy policy = plan.getNotificationPolicy();
        if (policy == null || policy == InspectionNotificationPolicy.NEVER) {
            return;
        }
        if (policy == InspectionNotificationPolicy.ON_ABNORMAL
                && eventType != NotificationEventType.INSPECTION_ABNORMAL) {
            // ON_ABNORMAL 仅在异常时通知
            return;
        }

        try {
            String status = exec.getStatus() != null ? exec.getStatus().name() : "UNKNOWN";
            String summary = exec.getSummary() != null ? exec.getSummary() : "";
            NotificationEvent event = eventFactory.createInspectionEvent(
                    eventType,
                    exec.getAuditId(),
                    safe(plan.getName()),
                    plan.getTemplateType() != null ? plan.getTemplateType().name() : "UNKNOWN",
                    status,
                    summary,
                    exec.getReportId());
            notificationService.emit(event);
        } catch (Exception e) {
            // 通知失败不阻断执行
            log.warn("巡检通知 emit 失败(已记录 execution.status): executionId={}, eventType={}",
                    exec.getExecutionId(), eventType, e);
        }
    }

    // ==================== 辅助 ====================

    private String buildPlanSnapshot(InspectionPlan plan) {
        try {
            Map<String, Object> snap = new LinkedHashMap<>();
            snap.put("planId", plan.getPlanId());
            snap.put("name", plan.getName());
            snap.put("description", plan.getDescription());
            snap.put("templateType", plan.getTemplateType() != null ? plan.getTemplateType().name() : null);
            snap.put("scheduleType", plan.getScheduleType() != null ? plan.getScheduleType().name() : null);
            snap.put("timezone", plan.getTimezone());
            snap.put("notificationPolicy", plan.getNotificationPolicy() != null
                    ? plan.getNotificationPolicy().name() : null);
            snap.put("templateParamsJson", plan.getTemplateParamsJson());
            snap.put("thresholdsJson", plan.getThresholdsJson());
            snap.put("scheduleConfigJson", plan.getScheduleConfigJson());
            return objectMapper.writeValueAsString(snap);
        } catch (Exception e) {
            return "{\"planId\":\"" + safe(plan.getPlanId()) + "\"}";
        }
    }

    private static String buildSummary(AbnormalVerdict verdict, List<ToolResult> results,
                                        InspectionExecutionStatus status) {
        int total = results == null ? 0 : results.size();
        int success = 0;
        if (results != null) {
            for (ToolResult r : results) {
                if (r != null && r.isSuccess()) success++;
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("巡检").append(status != null ? status.name() : "UNKNOWN")
                .append(": ").append(success).append("/").append(total).append(" 工具成功");
        if (verdict.isAbnormal() && verdict.getReasons() != null && !verdict.getReasons().isEmpty()) {
            sb.append(";异常: ");
            sb.append(String.join("; ", verdict.getReasons()));
        }
        // 截断到 1024(对应 column length)
        String s = sb.toString();
        return s.length() > 1024 ? s.substring(0, 1021) + "..." : s;
    }

    private static Map<String, Object> parseParams(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return new ObjectMapper().readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static Map<String, Double> parseThresholds(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return new ObjectMapper().readValue(json, new TypeReference<Map<String, Double>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static IntentType mapTemplateToIntent(InspectionTemplateType template) {
        if (template == null) return IntentType.SYSTEM_CHECK;
        return switch (template) {
            case HEALTH -> IntentType.SYSTEM_CHECK;
            case DISK -> IntentType.DISK_DIAGNOSIS;
            case SERVICE -> IntentType.SERVICE_DIAGNOSIS;
        };
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
