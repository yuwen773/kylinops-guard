package com.kylinops.tool;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 工具定义 VO（视图对象）
 * <p>
 * 用于 REST API 返回，不直接暴露 JPA 实体 {@link ToolDefinition}。
 * 脱敏处理：不返回 inputSchema / outputSchema 的原始 JSON 内容，
 * 仅返回必要元数据供前端展示。
 * </p>
 *
 * <h3>Task 11 扩展</h3>
 * <p>
 * 追加三个统计字段，全部 nullable（调用统计为零时 successRate /
 * lastCalledAt 不序列化，避免前端误解）：
 * <ul>
 *   <li>{@code callCount} — 该工具历史调用总数（long，可为 0）</li>
 *   <li>{@code successRate} — SUCCESS / terminal calls（0.0 - 1.0），
 *       无 terminal calls 时为 null（不是 0、不是 100）</li>
 *   <li>{@code lastCalledAt} — 最近一次调用时间，调用统计为零时为 null</li>
 * </ul>
 * 原字段（toolName / description / inputSchema / outputSchema / riskLevel /
 * permissionType / toolStatus / timeoutMs / auditRequired）保持兼容。
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
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

    // ---------------------------------------------------------------------
    // Task 11 — 调用统计字段（追加；原字段不动）
    // ---------------------------------------------------------------------

    /**
     * 调用次数（历史全状态总数，含 PENDING/RUNNING）。
     * <p>调用统计为零的工具仍显式返回 0，便于前端按列对齐展示。</p>
     */
    private Long callCount;

    /**
     * 成功率 = SUCCESS / (SUCCESS + FAILED + TIMEOUT + BLOCKED)。
     * <p>无任何 terminal 调用时为 null（不是 0、不是 100）。
     * 通过 {@code @JsonInclude(NON_NULL)} 序列化时被省略。</p>
     */
    private Double successRate;

    /**
     * 最近一次调用时间（Instant），调用统计为零时为 null。
     */
    private Instant lastCalledAt;

    /**
     * 从实体构造 VO（仅元数据，不含统计；统计由 ToolController 注入）。
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
                .callCount(0L)
                // successRate / lastCalledAt 留 null，由 ToolController 在
                // 注入统计时填充。
                .build();
    }
}