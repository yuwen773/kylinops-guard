package com.kylinops.notification;

import com.kylinops.common.enums.IntentType;
import com.kylinops.common.enums.RiskDecision;
import com.kylinops.common.enums.RiskLevel;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 通知事件工厂 — 统一构造 {@link NotificationEvent}。
 *
 * <p><b>职责</b>:</p>
 * <ul>
 *   <li>统一构造 title / summary / detail / severity / 强类型字段</li>
 *   <li>AgentOrchestrator 只调用 factory 方法,不直接 new NotificationEvent.builder() 拼字段</li>
 *   <li>后续调整模板、标题、摘要时,不需要改 AgentOrchestrator</li>
 * </ul>
 */
@Component
public class NotificationEventFactory {

    /**
     * L4 绝对阻断事件。
     */
    public NotificationEvent l4Block(String auditId, String sessionId,
                                     RiskLevel riskLevel, RiskDecision riskDecision,
                                     String matchedRuleId, String riskReason) {
        return NotificationEvent.builder()
                .eventType(NotificationEventType.L4_BLOCK)
                .severity(NotificationSeverity.CRITICAL)
                .title("L4 阻断告警")
                .summary(buildL4Summary(matchedRuleId, riskReason))
                .detail(buildL4Detail(riskReason))
                .sessionId(sessionId)
                .auditId(auditId)
                .riskLevel(riskLevel != null ? riskLevel : RiskLevel.L4)
                .riskDecision(riskDecision != null ? riskDecision : RiskDecision.BLOCK)
                .intentType(IntentType.COMMAND_EXECUTION)
                .matchedRuleId(matchedRuleId)
                .build();
    }

    /**
     * Prompt 注入拦截事件。
     */
    public NotificationEvent promptInjectionBlock(String auditId, String sessionId,
                                                  String promptInjectionPattern, String reason) {
        return NotificationEvent.builder()
                .eventType(NotificationEventType.PROMPT_INJECTION_BLOCK)
                .severity(NotificationSeverity.CRITICAL)
                .title("Prompt 注入拦截")
                .summary(buildInjectionSummary(promptInjectionPattern))
                .detail(buildInjectionDetail(reason))
                .sessionId(sessionId)
                .auditId(auditId)
                .riskLevel(RiskLevel.L4)
                .riskDecision(RiskDecision.BLOCK)
                .intentType(IntentType.COMMAND_EXECUTION)
                .promptInjectionPattern(promptInjectionPattern)
                .build();
    }

    /**
     * L2 待确认事件。
     */
    public NotificationEvent l2ConfirmRequired(String auditId, String sessionId,
                                               IntentType intentType, RiskLevel riskLevel,
                                               String actionId, String summary) {
        return NotificationEvent.builder()
                .eventType(NotificationEventType.L2_CONFIRM_REQUIRED)
                .severity(NotificationSeverity.WARNING)
                .title("L2 待用户确认")
                .summary(buildL2Summary(summary))
                .detail(buildL2Detail(actionId))
                .sessionId(sessionId)
                .auditId(auditId)
                .riskLevel(riskLevel != null ? riskLevel : RiskLevel.L2)
                .riskDecision(RiskDecision.CONFIRM)
                .intentType(intentType)
                .build();
    }

    /**
     * 服务异常事件(Phase 2 触发;Plan 01 不调用但保留 API)。
     */
    public NotificationEvent serviceAbnormal(String auditId, String sessionId,
                                             String serviceName, double rcaConfidence,
                                             String conclusion) {
        return NotificationEvent.builder()
                .eventType(NotificationEventType.SERVICE_ABNORMAL)
                .severity(NotificationSeverity.INFO)
                .title("服务异常告警")
                .summary("服务 " + serviceName + " RCA 置信度 " + String.format("%.2f", rcaConfidence))
                .detail(conclusion)
                .sessionId(sessionId)
                .auditId(auditId)
                .riskLevel(RiskLevel.L1)
                .riskDecision(RiskDecision.ALLOW)
                .serviceName(serviceName)
                .rcaConfidence(rcaConfidence)
                .build();
    }

    /**
     * 磁盘风险事件(Phase 2 触发;Plan 01 不调用但保留 API)。
     */
    public NotificationEvent diskRisk(String auditId, String sessionId,
                                      String diskPath, Double diskUsagePercent,
                                      double rcaConfidence, String conclusion) {
        return NotificationEvent.builder()
                .eventType(NotificationEventType.DISK_RISK)
                .severity(NotificationSeverity.INFO)
                .title("磁盘风险告警")
                .summary(buildDiskSummary(diskPath, diskUsagePercent))
                .detail(conclusion)
                .sessionId(sessionId)
                .auditId(auditId)
                .riskLevel(RiskLevel.L1)
                .riskDecision(RiskDecision.ALLOW)
                .diskPath(diskPath)
                .diskUsagePercent(diskUsagePercent)
                .rcaConfidence(rcaConfidence)
                .build();
    }

    /**
     * 巡检事件工厂（P1-02 Task 4）。
     *
     * <p>统一产出 {@code INSPECTION_COMPLETED}/{@code INSPECTION_ABNORMAL}/{@code INSPECTION_FAILED}
     * 三类巡检事件,与现有 Channel 实现完全解耦（不修改任何 Channel 的 if/else）。
     * Channel 仅根据 {@link NotificationEventType} 投递。</p>
     *
     * <p>{@link NotificationEvent#getDetailMap()} 必须包含固定 6 键（{@code planName},
     * {@code templateType}, {@code status}, {@code summary}, {@code auditId}, {@code reportId}）
     * + ABNORMAL/FAILED 时附加 {@code abnormal} 布尔键。{@code summary} 字段值按现有
     * 通知契约截断至 500 字符（与 {@code NotificationEvent.detail} 语义一致）。</p>
     *
     * @param eventType    {@link NotificationEventType#INSPECTION_COMPLETED} 等
     * @param auditId      巡检审计 ID
     * @param planName     巡检计划名称
     * @param templateType 模板类型字符串（{@code HEALTH}/{@code DISK}/{@code SERVICE}）
     * @param status       巡检执行状态字符串（{@code SUCCESS}/{@code PARTIAL_SUCCESS}/{@code FAILED}）
     * @param summary      巡检摘要（可空）
     * @param reportId     关联报告 ID（可空）
     * @return 巡检事件
     */
    public NotificationEvent createInspectionEvent(NotificationEventType eventType,
                                                   String auditId,
                                                   String planName,
                                                   String templateType,
                                                   String status,
                                                   String summary,
                                                   String reportId) {
        if (eventType != NotificationEventType.INSPECTION_COMPLETED
                && eventType != NotificationEventType.INSPECTION_ABNORMAL
                && eventType != NotificationEventType.INSPECTION_FAILED) {
            throw new IllegalArgumentException(
                    "createInspectionEvent 仅支持 INSPECTION_* 事件类型,收到: " + eventType);
        }

        Map<String, Object> detailMap = new LinkedHashMap<>();
        detailMap.put("planName", planName);
        detailMap.put("templateType", templateType);
        detailMap.put("status", status);
        detailMap.put("summary", truncateSummary(summary));
        detailMap.put("auditId", auditId);
        detailMap.put("reportId", reportId);
        // abnormal 字段语义:仅 INSPECTION_ABNORMAL 时为 true。
// INSPECTION_FAILED 是执行层错误(工具/网络/权限),不等于"系统异常"。
// 两者正交:FAILED + abnormal=true 才会触发 ON_ABNORMAL 策略升级通知,见 InspectionNotificationPolicy。
boolean abnormal = eventType == NotificationEventType.INSPECTION_ABNORMAL;
        detailMap.put("abnormal", abnormal);

        NotificationSeverity severity = switch (eventType) {
            case INSPECTION_COMPLETED -> NotificationSeverity.INFO;
            case INSPECTION_ABNORMAL -> NotificationSeverity.WARNING;
            case INSPECTION_FAILED -> NotificationSeverity.CRITICAL;
            default -> NotificationSeverity.INFO;
        };

        return NotificationEvent.builder()
                .eventType(eventType)
                .severity(severity)
                .title(buildInspectionTitle(eventType, planName, templateType))
                .summary(buildInspectionSummary(eventType, planName, status))
                .detail(buildInspectionDetail(status, summary))
                .auditId(auditId)
                .riskLevel(RiskLevel.L0)
                .riskDecision(RiskDecision.ALLOW)
                .intentType(IntentType.SYSTEM_CHECK)
                .detailMap(detailMap)
                .build();
    }

    // ───── 文本模板(集中管理,后续调整只改这里) ─────

    private String buildL4Summary(String matchedRuleId, String riskReason) {
        String rule = matchedRuleId != null ? matchedRuleId : "unknown-rule";
        return "L4 阻断:命中规则 [" + rule + "]";
    }

    private String buildL4Detail(String riskReason) {
        if (riskReason == null || riskReason.isBlank()) {
            return "命中 L4 绝对阻断规则,已拒绝执行并审计。";
        }
        return "命中 L4 绝对阻断规则,已拒绝执行。理由: " + riskReason;
    }

    private String buildInjectionSummary(String pattern) {
        return "检测到 Prompt 注入" + (pattern != null ? ": " + pattern : "");
    }

    private String buildInjectionDetail(String reason) {
        if (reason == null || reason.isBlank()) {
            return "Prompt 注入检测命中,已阻断并审计。";
        }
        return "Prompt 注入检测命中,已阻断。理由: " + reason;
    }

    private String buildL2Summary(String summary) {
        return summary != null ? "待确认操作: " + summary : "待确认操作";
    }

    private String buildL2Detail(String actionId) {
        return actionId != null ? ("PendingAction ID: " + actionId) : "无 PendingAction ID";
    }

    private String buildDiskSummary(String diskPath, Double percent) {
        if (diskPath == null && percent == null) {
            return "磁盘使用率异常";
        }
        if (percent == null) {
            return "磁盘 " + diskPath + " 存在风险";
        }
        return "磁盘 " + (diskPath != null ? diskPath : "/") + " 使用率 "
                + String.format("%.1f%%", percent);
    }

    /** 巡检事件 title 模板（按 eventType 区分）。 */
    private String buildInspectionTitle(NotificationEventType eventType,
                                        String planName, String templateType) {
        String name = planName != null ? planName : "未命名计划";
        String tpl = templateType != null ? templateType : "INSPECTION";
        return switch (eventType) {
            case INSPECTION_COMPLETED -> "巡检完成: " + name + " (" + tpl + ")";
            case INSPECTION_ABNORMAL -> "巡检异常: " + name + " (" + tpl + ")";
            case INSPECTION_FAILED -> "巡检失败: " + name + " (" + tpl + ")";
            default -> "巡检事件: " + name;
        };
    }

    /** 巡检事件 summary 模板。 */
    private String buildInspectionSummary(NotificationEventType eventType,
                                          String planName, String status) {
        String name = planName != null ? planName : "未命名计划";
        String st = status != null ? status : "UNKNOWN";
        return switch (eventType) {
            case INSPECTION_COMPLETED -> "巡检计划 " + name + " 已完成,状态: " + st;
            case INSPECTION_ABNORMAL -> "巡检计划 " + name + " 发现异常,状态: " + st;
            case INSPECTION_FAILED -> "巡检计划 " + name + " 执行失败,状态: " + st;
            default -> "巡检计划 " + name;
        };
    }

    /** 巡检事件 detail 模板。 */
    private String buildInspectionDetail(String status, String summary) {
        StringBuilder sb = new StringBuilder();
        sb.append("执行状态: ").append(status != null ? status : "UNKNOWN");
        if (summary != null && !summary.isBlank()) {
            sb.append("。摘要: ").append(truncateSummary(summary));
        }
        return sb.toString();
    }

    /** summary 字段按现有通知契约截断至 500 字符。 */
    private String truncateSummary(String s) {
        if (s == null) return null;
        return s.length() <= 500 ? s : s.substring(0, 500) + "...";
    }
}
