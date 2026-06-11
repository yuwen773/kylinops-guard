package com.kylinops.security;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 风险校验请求 DTO
 * <p>
 * POST /api/security/risk-check 的请求体。
 * </p>
 */
@Data
public class RiskCheckRequest {

    /** 目标类型: prompt / command / path / tool / action */
    @NotBlank(message = "目标类型不能为空")
    private String targetType;

    /** 待评估的原始内容（命令文本、路径、输入等） */
    @NotBlank(message = "评估内容不能为空")
    private String content;

    /** 工具名称（仅 targetType=tool 时可选） */
    private String toolName;
}
