package com.kylinops.notification;

import com.kylinops.common.enums.IntentType;
import com.kylinops.common.enums.RiskDecision;
import com.kylinops.common.enums.RiskLevel;
import org.springframework.stereotype.Component;

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
}
