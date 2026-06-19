package com.kylinops.audit;

import com.kylinops.common.enums.AuditStatus;
import com.kylinops.common.enums.IntentType;
import com.kylinops.common.enums.RiskDecision;
import com.kylinops.common.enums.RiskLevel;
import com.kylinops.notification.NotificationRecordSummary;
import com.kylinops.rca.RootCauseChain;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 审计日志详情 DTO
 * <p>
 * 用于 GET /api/audit/logs/{auditId} 的响应。
 * 聚合主审计记录、工具调用、风险校验、确认和执行信息。
 * </p>
 */
@Data
@Builder
@AllArgsConstructor
public class AuditLogDetail {

    /** 审计 ID */
    private String auditId;

    /** 会话 ID */
    private String sessionId;

    /** 用户输入（已脱敏截断） */
    private String userInput;

    /** 意图类型 */
    private IntentType intentType;

    /** 风险等级 */
    private RiskLevel riskLevel;

    /** 风险决策 */
    private RiskDecision riskDecision;

    /** 审计状态 */
    private AuditStatus status;

    /** 审计消息 */
    private String message;

    /** 匹配规则（JSON） */
    private String matchedRules;

    /** 执行计划 */
    private String actionPlan;

    /** 是否需确认 */
    private boolean confirmationRequired;

    /** 确认状态 */
    private String confirmationStatus;

    /** 执行结果 */
    private String executionResult;

    /** 最终回复 */
    private String finalAnswer;

    /** 警告 */
    private String warning;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    /** 关联的工具调用记录 */
    private List<ToolCallInfo> toolCalls;

    /** 关联的风险校验记录 */
    private List<RiskCheckInfo> riskChecks;

    /** 待确认动作 */
    private PendingActionInfo pendingAction;

    private List<NotificationRecordSummary> notificationRecords;

    @Data
    @Builder
    @AllArgsConstructor
    public static class ToolCallInfo {
        private String toolCallId;
        private String toolName;
        private String status;
        private String input;
        private String output;
        private String errorMessage;
        private Long durationMs;
    }

    @Data
    @Builder
    @AllArgsConstructor
    public static class RiskCheckInfo {
        private String riskCheckId;
        private String targetType;
        private String riskLevel;
        private String riskDecision;
        private String matchedRules;
        private String reason;
        private java.time.LocalDateTime checkedAt;
    }

    @Data
    @Builder
    @AllArgsConstructor
    public static class PendingActionInfo {
        private String actionId;
        private String actionType;
        private String toolName;
        private String status;
        private String executionResult;
    }

    /** 根因分析链（反序列化自 rootCauseChainJson） */
    private RootCauseChain rootCauseChain;

    /** 触发来源（巡检审计填 SCHEDULED/MANUAL，其他审计为 null） */
    private String triggerType;

    /** 操作主体（巡检审计填 SYSTEM_SCHEDULER/管理员用户名，其他审计为 null） */
    private String operator;
}
