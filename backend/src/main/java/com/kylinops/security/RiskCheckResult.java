package com.kylinops.security;

import com.kylinops.common.enums.RiskDecision;
import com.kylinops.common.enums.RiskLevel;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 风险校验结果
 * <p>
 * 封装 RiskRuleEngine 或 RiskCheckService 对一次评估的输出。
 * 包含综合风险等级、决策、匹配规则明细、原因和安全建议，
 * 以及 L2 场景的待确认动作。
 * </p>
 */
@Data
@Builder
public class RiskCheckResult {

    /** 综合风险等级 */
    private final RiskLevel riskLevel;

    /** 综合风险决策 */
    private final RiskDecision decision;

    /** 匹配的规则 ID 列表 */
    private final List<String> matchedRules;

    /** 决策原因（中文） */
    private final String reason;

    /** 安全建议（中文） */
    private final String safeSuggestion;

    /** 工具定义的风险等级（用于区分工具定义风险 vs 内容风险） */
    private final RiskLevel toolRiskLevel;

    /** L2 待确认动作（decision=CONFIRM 时设置） */
    private final PendingAction pendingAction;

    /**
     * 临时内嵌的待确认动作结构。
     * 将在 Task 4 中替换为持久化实体。
     */
    @Data
    @Builder
    public static class PendingAction {
        private final String actionId;
        private final String toolName;
        private final Map<String, Object> params;
        private final String description;
    }

    public static RiskCheckResult allow(RiskLevel level, String reason) {
        return RiskCheckResult.builder()
                .riskLevel(level)
                .decision(RiskDecision.ALLOW)
                .matchedRules(List.of())
                .reason(reason)
                .safeSuggestion(null)
                .toolRiskLevel(level)
                .build();
    }

    public static RiskCheckResult block(RiskLevel level, List<String> rules, String reason, String suggestion) {
        return RiskCheckResult.builder()
                .riskLevel(level)
                .decision(RiskDecision.BLOCK)
                .matchedRules(rules != null ? rules : List.of())
                .reason(reason)
                .safeSuggestion(suggestion)
                .build();
    }

    public static RiskCheckResult confirm(RiskLevel level, String reason, String suggestion,
                                          PendingAction pendingAction) {
        return RiskCheckResult.builder()
                .riskLevel(level)
                .decision(RiskDecision.CONFIRM)
                .matchedRules(List.of())
                .reason(reason)
                .safeSuggestion(suggestion)
                .pendingAction(pendingAction)
                .build();
    }
}
