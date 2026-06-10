package com.kylinops.tool;

import com.kylinops.common.enums.PermissionType;
import com.kylinops.common.enums.RiskLevel;
import com.kylinops.common.enums.ToolStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工具定义 VO（视图对象）
 * <p>
 * 用于 REST API 返回，不直接暴露 JPA 实体 {@link ToolDefinition}。
 * 脱敏处理：不返回 inputSchema / outputSchema 的原始 JSON 内容，
 * 仅返回必要元数据供前端展示。
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ToolDefinitionVO {

    /** 工具名称（唯一标识） */
    private String toolName;

    /** 工具描述 */
    private String description;

    /** 输入 JSON Schema（可选，前端可忽略大段 Schema） */
    private String inputSchema;

    /** 输出 JSON Schema */
    private String outputSchema;

    /** 风险等级 */
    private String riskLevel;

    /** 权限类型 */
    private String permissionType;

    /** 工具启用状态 */
    private String toolStatus;

    /** 超时时间（毫秒） */
    private long timeoutMs;

    /** 是否需要审计 */
    private boolean auditRequired;

    /**
     * 从实体构造 VO
     */
    public static ToolDefinitionVO fromEntity(ToolDefinition entity) {
        return ToolDefinitionVO.builder()
                .toolName(entity.getToolName())
                .description(entity.getDescription())
                .inputSchema(entity.getInputSchema())
                .outputSchema(entity.getOutputSchema())
                .riskLevel(entity.getRiskLevel().name())
                .permissionType(entity.getPermissionType().name())
                .toolStatus(entity.getToolStatus().name())
                .timeoutMs(entity.getTimeoutMs())
                .auditRequired(entity.isAuditRequired())
                .build();
    }
}
