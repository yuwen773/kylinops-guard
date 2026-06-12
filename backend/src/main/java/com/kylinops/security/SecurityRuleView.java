package com.kylinops.security;

import com.kylinops.common.enums.RiskDecision;
import com.kylinops.common.enums.RiskLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 安全规则只读视图。
 * <p>
 * 用于 GET /api/security/rules 的响应；
 * 字段是 {@link RiskRule} 的不可变拷贝，不暴露任何修改或重载入口。
 * </p>
 */
@Data
@Builder
@AllArgsConstructor
public class SecurityRuleView {

    /** 规则唯一标识 */
    private String ruleId;

    /** 规则名（与 id 相同，便于前端展示） */
    private String name;

    /** 中文描述 */
    private String description;

    /** 原始正则字符串（展示用，不含 Pattern 编译状态） */
    private String regex;

    /** 目标类型列表 */
    private List<String> targetTypes;

    /** 风险等级 */
    private RiskLevel riskLevel;

    /** 风险决策 */
    private RiskDecision decision;

    /** 命中原因（中文） */
    private String reason;

    /** 安全建议（中文） */
    private String safeSuggestion;

    /** 是否启用 */
    private boolean enabled;

    /** 优先级 */
    private int priority;
}
