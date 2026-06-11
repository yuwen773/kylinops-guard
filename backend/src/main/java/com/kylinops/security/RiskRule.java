package com.kylinops.security;

import com.kylinops.common.enums.RiskDecision;
import com.kylinops.common.enums.RiskLevel;
import lombok.Getter;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 不可变安全规则
 * <p>
 * 从 security-rules.yml 加载，启动后不可修改。
 * 每个规则定义一个目标类型、匹配模式、风险等级和决策。
 * </p>
 */
@Getter
public class RiskRule {

    /** 规则唯一标识（如 "block_rm_rf_root"） */
    private final String id;

    /** 目标类型列表（command / path / tool / action / prompt） */
    private final List<String> targetTypes;

    /** 正则匹配模式（命令/内容归一化后匹配） */
    private final Pattern pattern;

    /** 原始正则字符串（用于日志和审计） */
    private final String patternString;

    /** 风险等级 */
    private final RiskLevel riskLevel;

    /** 风险决策 */
    private final RiskDecision decision;

    /** 命中原因（中文，用于审计） */
    private final String reason;

    /** 安全建议（中文） */
    private final String safeSuggestion;

    /** 是否启用 */
    private final boolean enabled;

    /** 优先级（值越大优先级越高，用于同等级规则排序） */
    private final int priority;

    public RiskRule(String id, List<String> targetTypes, String patternString,
                    RiskLevel riskLevel, RiskDecision decision,
                    String reason, String safeSuggestion,
                    boolean enabled, int priority) {
        this.id = id;
        this.targetTypes = targetTypes != null ? List.copyOf(targetTypes) : List.of();
        this.patternString = patternString;
        this.pattern = Pattern.compile(patternString, Pattern.CASE_INSENSITIVE);
        this.riskLevel = riskLevel;
        this.decision = decision;
        this.reason = reason;
        this.safeSuggestion = safeSuggestion;
        this.enabled = enabled;
        this.priority = priority;
    }

    /**
     * 判断规则是否适用于给定的目标类型。
     */
    public boolean matchesTargetType(String targetType) {
        if (targetType == null || targetTypes.isEmpty()) return true;
        return targetTypes.contains(targetType);
    }

    /**
     * 检查给定的归一化内容是否匹配此规则。
     */
    public boolean matches(String normalizedContent) {
        if (normalizedContent == null || normalizedContent.isBlank()) return false;
        return pattern.matcher(normalizedContent).find();
    }
}
