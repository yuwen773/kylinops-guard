package com.kylinops.os;

import com.kylinops.common.enums.PermissionType;
import com.kylinops.common.enums.RiskLevel;
import com.kylinops.common.enums.ToolStatus;
import com.kylinops.tool.OpsTool;
import com.kylinops.tool.ToolDefinition;
import com.kylinops.tool.ToolInput;
import com.kylinops.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务状态查询工具 — service_status_tool (L0 / READ)
 * <p>
 * 查询 Linux 系统服务的运行状态和启用状态。
 * 使用固定参数数组调用 {@code systemctl is-active} 和 {@code systemctl is-enabled}，
 * 禁止拼接用户输入为 shell 命令。
 * </p>
 */
@Slf4j
@Component
public class ServiceStatusTool implements OpsTool {

    public static final String TOOL_NAME = "service_status_tool";
    private static final String DESCRIPTION = "查询系统服务状态：运行状态（active/inactive）和启用状态（enabled/disabled）";

    private final ToolDefinition definition;
    private final OsCommandExecutor executor;

    public ServiceStatusTool(OsCommandExecutor executor) {
        this.executor = executor;
        this.definition = new ToolDefinition();
        this.definition.setToolName(TOOL_NAME);
        this.definition.setDescription(DESCRIPTION);
        this.definition.setInputSchema("{" +
                "\"type\":\"object\"," +
                "\"properties\":{" +
                "\"serviceName\":{\"type\":\"string\",\"description\":\"服务名称\"}" +
                "}}");
        this.definition.setOutputSchema("{" +
                "\"type\":\"object\"," +
                "\"properties\":{" +
                "\"serviceName\":{\"type\":\"string\"}," +
                "\"isActive\":{\"type\":\"boolean\"}," +
                "\"isEnabled\":{\"type\":\"boolean\"}," +
                "\"activeState\":{\"type\":\"string\"}," +
                "\"enabledState\":{\"type\":\"string\"}" +
                "}}");
        this.definition.setRiskLevel(RiskLevel.L0);
        this.definition.setPermissionType(PermissionType.READ);
        this.definition.setToolStatus(ToolStatus.ENABLED);
        this.definition.setTimeoutMs(5000L);
        this.definition.setAuditRequired(true);
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public ToolResult execute(ToolInput input) {
        String serviceName = input.getParams() != null
                ? (String) input.getParams().get("serviceName") : null;
        log.debug("ServiceStatusTool 执行: serviceName={}, requestId={}", serviceName, input.getRequestId());

        if (serviceName == null || serviceName.isBlank()) {
            return ToolResult.failed(TOOL_NAME, "服务名不能为空", 0);
        }

        // 服务名格式校验
        if (!BaseOSValidator.isValidServiceName(serviceName)) {
            return ToolResult.failed(TOOL_NAME, "服务名不合法（包含非法字符）: " + serviceName, 0);
        }

        if (executor.isWindows()) {
            return ToolResult.failed(TOOL_NAME,
                    "服务状态查询需要 Linux systemctl（当前为 Windows 环境）", 0);
        }

        // 固定参数数组 — 不拼接用户输入
        Map<String, Object> data = new HashMap<>();
        data.put("serviceName", serviceName);

        // 查询运行状态: systemctl is-active <service>
        OsCommandExecutor.CommandResult activeResult = executor.execute(
                List.of("systemctl", "is-active", serviceName), 5000);
        if (activeResult.isSuccess()) {
            String activeState = activeResult.getStdout().isEmpty() ? "unknown" : activeResult.getStdout().get(0).trim();
            data.put("activeState", activeState);
            data.put("isActive", "active".equals(activeState));
        } else {
            data.put("activeState", "unknown");
            data.put("isActive", false);
        }

        // 查询启用状态: systemctl is-enabled <service>
        OsCommandExecutor.CommandResult enabledResult = executor.execute(
                List.of("systemctl", "is-enabled", serviceName), 5000);
        if (enabledResult.isSuccess()) {
            String enabledState = enabledResult.getStdout().isEmpty() ? "unknown" : enabledResult.getStdout().get(0).trim();
            data.put("enabledState", enabledState);
            data.put("isEnabled", "enabled".equals(enabledState));
        } else {
            data.put("enabledState", "unknown");
            data.put("isEnabled", false);
        }

        String summary = String.format("服务 %s: %s (启用: %s)",
                serviceName, data.get("activeState"), data.get("enabledState"));

        return ToolResult.success(TOOL_NAME, data, summary, 0);
    }
}
