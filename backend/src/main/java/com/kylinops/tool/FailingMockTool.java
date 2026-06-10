package com.kylinops.tool;

import com.kylinops.common.enums.PermissionType;
import com.kylinops.common.enums.RiskLevel;
import com.kylinops.common.enums.ToolStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 异常 Mock 工具 — 用于验证 ToolExecutor 的异常隔离能力。
 * <p>
 * {@link #execute(ToolInput)} 直接抛出 RuntimeException，
 * 验证上层不会因此崩溃，而是返回 {@link ToolResult#failed()}。
 * </p>
 */
@Slf4j
@Component
public class FailingMockTool implements OpsTool {

    private static final String TOOL_NAME = "failing_mock_tool";
    private static final String DESCRIPTION = "异常 Mock 工具 — 用于验证工具异常不导致主流程崩溃";

    private final ToolDefinition definition;

    public FailingMockTool() {
        this.definition = new ToolDefinition();
        this.definition.setToolName(TOOL_NAME);
        this.definition.setDescription(DESCRIPTION);
        this.definition.setInputSchema("{\"type\":\"object\",\"properties\":{}}");
        this.definition.setOutputSchema("{\"type\":\"object\",\"properties\":{}}");
        this.definition.setRiskLevel(RiskLevel.L0);
        this.definition.setPermissionType(PermissionType.READ);
        this.definition.setToolStatus(ToolStatus.ENABLED);
        this.definition.setTimeoutMs(3000L);
        this.definition.setAuditRequired(true);
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public ToolResult execute(ToolInput input) {
        log.debug("FailingMockTool 执行 — 即将抛出异常: toolName={}", input.getToolName());
        throw new RuntimeException("模拟工具异常 — FailingMockTool 测试异常隔离");
    }
}
