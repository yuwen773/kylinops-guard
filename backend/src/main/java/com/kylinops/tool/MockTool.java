package com.kylinops.tool;

import com.kylinops.common.enums.PermissionType;
import com.kylinops.common.enums.RiskLevel;
import com.kylinops.common.enums.ToolStatus;
import jakarta.persistence.Column;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Mock 工具 — 用于开发阶段测试工具注册、执行和审计链路。
 * <p>
 * 风险等级 L0、权限 READ、启用状态。
 * 模拟返回固定数据，不执行任何真实 OS 操作。
 * </p>
 */
@Slf4j
@Component
public class MockTool implements OpsTool {

    private static final String TOOL_NAME = "mock_tool";
    private static final String DESCRIPTION = "Mock 测试工具 — 验证工具注册、执行和审计链路是否正常";

    private final ToolDefinition definition;

    public MockTool() {
        this.definition = new ToolDefinition();
        this.definition.setToolName(TOOL_NAME);
        this.definition.setDescription(DESCRIPTION);
        this.definition.setInputSchema("{\"type\":\"object\",\"properties\":{}}");
        this.definition.setOutputSchema("{\"type\":\"object\",\"properties\":{\"mockKey\":{\"type\":\"string\"}}}");
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
        log.debug("MockTool 执行: toolName={}, params={}, requestId={}",
                input.getToolName(), input.getParams(), input.getRequestId());

        Map<String, Object> data = new HashMap<>();
        data.put("mockKey", "mockValue");
        data.put("toolName", TOOL_NAME);
        data.put("requestId", input.getRequestId());

        return ToolResult.success(TOOL_NAME, data, "Mock 工具执行成功", 0);
    }
}
