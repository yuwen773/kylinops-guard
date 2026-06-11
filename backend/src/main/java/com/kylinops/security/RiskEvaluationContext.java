package com.kylinops.security;

import lombok.Getter;

import java.util.Map;

/**
 * 风险评估上下文
 * <p>
 * 封装一次安全评估所需的全部输入信息：
 * 目标类型、原始内容/命令、工具名称和参数。
 * </p>
 */
@Getter
public class RiskEvaluationContext {

    /** 目标类型: prompt / command / path / tool / action */
    private final String targetType;

    /** 原始内容（用户输入、命令文本、路径等） */
    private final String content;

    /** 工具名称（仅 targetType=tool 时有效） */
    private final String toolName;

    /** 工具参数（仅 targetType=tool 时有效） */
    private final Map<String, Object> params;

    /** 归一化后的内容（用于规则匹配） */
    private final String normalizedContent;

    public RiskEvaluationContext(String targetType, String content, String toolName, Map<String, Object> params) {
        this.targetType = targetType;
        this.content = content;
        this.toolName = toolName;
        this.params = params;
        this.normalizedContent = normalizeContent(content);
    }

    /**
     * 对命令/内容做归一化：
     * 1. 去除首尾空白
     * 2. 合并连续空白为单个空格
     * 3. 统一为大写（Pattern 已设 CASE_INSENSITIVE）
     * 4. 保留原文用于审计摘要
     */
    private static String normalizeContent(String content) {
        if (content == null || content.isBlank()) return "";
        return content.trim().replaceAll("\\s+", " ");
    }
}
