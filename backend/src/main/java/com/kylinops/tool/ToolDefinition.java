package com.kylinops.tool;

import com.kylinops.common.BaseEntity;
import com.kylinops.common.enums.PermissionType;
import com.kylinops.common.enums.RiskLevel;
import com.kylinops.common.enums.ToolStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * 工具定义实体
 * <p>
 * 注册在 ToolRegistry 中的 OpsTool 元数据定义。
 * 包含工具的声明式属性：名称、描述、输入输出模式、风险等级、权限类型等。
 * </p>
 */
@Entity
@Table(name = "kylin_tool_definition")
@Getter
@Setter
public class ToolDefinition extends BaseEntity {

    /** 工具名称（唯一标识） */
    @Column(nullable = false, unique = true, length = 64)
    private String toolName;

    /** 工具描述 */
    @Column(nullable = false, length = 512)
    private String description;

    /** 输入 JSON Schema */
    @Column(columnDefinition = "TEXT")
    private String inputSchema;

    /** 输出 JSON Schema */
    @Column(columnDefinition = "TEXT")
    private String outputSchema;

    /** 风险等级 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private RiskLevel riskLevel;

    /** 权限类型 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private PermissionType permissionType;

    /** 工具启用状态 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private ToolStatus toolStatus = ToolStatus.ENABLED;

    /** 超时时间（毫秒） */
    @Column(nullable = false)
    private long timeoutMs = 3000L;

    /** 是否需要审计 */
    @Column(nullable = false)
    private boolean auditRequired = true;
}
