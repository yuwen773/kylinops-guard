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
import java.util.stream.Collectors;

/**
 * 系统日志查询工具 — journal_log_tool (L0 / READ)
 * <p>
 * 查询 Linux 系统日志（journald）中指定服务的日志条目。
 * 使用固定参数数组调用 {@code journalctl -u <service> -n <lines> --no-pager}，
 * 禁止拼接用户输入为 shell 命令。
 * </p>
 */
@Slf4j
@Component
public class JournalLogTool implements OpsTool {

    public static final String TOOL_NAME = "journal_log_tool";
    private static final String DESCRIPTION = "查询系统日志（journald）：指定服务的近期日志条目";

    /** 最大返回行数 */
    static final int MAX_LINES = 200;

    /** 默认返回行数 */
    static final int DEFAULT_LINES = 50;

    private final ToolDefinition definition;
    private final OsCommandExecutor executor;

    public JournalLogTool(OsCommandExecutor executor) {
        this.executor = executor;
        this.definition = new ToolDefinition();
        this.definition.setToolName(TOOL_NAME);
        this.definition.setDescription(DESCRIPTION);
        this.definition.setInputSchema("{" +
                "\"type\":\"object\"," +
                "\"properties\":{" +
                "\"serviceName\":{\"type\":\"string\"}," +
                "\"lines\":{\"type\":\"integer\",\"default\":50,\"maximum\":200}" +
                "}}");
        this.definition.setOutputSchema("{" +
                "\"type\":\"object\"," +
                "\"properties\":{" +
                "\"serviceName\":{\"type\":\"string\"}," +
                "\"lines\":{\"type\":\"integer\"}," +
                "\"entries\":{\"type\":\"array\"}" +
                "}}");
        this.definition.setRiskLevel(RiskLevel.L0);
        this.definition.setPermissionType(PermissionType.READ);
        this.definition.setToolStatus(ToolStatus.ENABLED);
        this.definition.setTimeoutMs(10000L);
        this.definition.setAuditRequired(true);
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public ToolResult execute(ToolInput input) {
        Map<String, Object> params = input.getParams();
        String serviceName = params != null ? (String) params.get("serviceName") : null;
        Integer lines = params != null ? (Integer) params.get("lines") : null;

        log.debug("JournalLogTool 执行: serviceName={}, lines={}, requestId={}",
                serviceName, lines, input.getRequestId());

        if (serviceName == null || serviceName.isBlank()) {
            return ToolResult.failed(TOOL_NAME, "服务名不能为空", 0);
        }

        // 服务名格式校验
        if (!BaseOSValidator.isValidServiceName(serviceName)) {
            return ToolResult.failed(TOOL_NAME, "服务名不合法（包含非法字符）: " + serviceName, 0);
        }

        // 限制行数
        int actualLines = (lines != null && lines > 0) ? Math.min(lines, MAX_LINES) : DEFAULT_LINES;

        if (executor.isWindows()) {
            return ToolResult.failed(TOOL_NAME,
                    "系统日志查询需要 Linux journalctl（当前为 Windows 环境）", 0);
        }

        // 固定参数数组 — 不拼接用户输入
        // journalctl -u <service> -n <lines> --no-pager
        OsCommandExecutor.CommandResult result = executor.execute(
                List.of("journalctl", "-u", serviceName, "-n", String.valueOf(actualLines), "--no-pager"),
                definition.getTimeoutMs());

        Map<String, Object> data = new HashMap<>();
        data.put("serviceName", serviceName);
        data.put("lines", actualLines);

        if (result.isSuccess()) {
            data.put("entries", result.getStdout());
            data.put("truncated", result.isTruncated());
        } else {
            // journalctl 可能因权限或服务名无效失败
            data.put("entries", List.of());
            data.put("note", result.getErrorMessage() != null
                    ? "日志查询返回异常: " + result.getErrorMessage()
                    : "无可用日志条目");
        }

        @SuppressWarnings("unchecked")
        List<String> entries = (List<String>) data.getOrDefault("entries", List.of());
        String summary = String.format("服务 %s 最近 %d 条日志%s",
                serviceName,
                entries.size(),
                result.isTruncated() ? "（输出已截断）" : "");

        return ToolResult.success(TOOL_NAME, data, summary, 0);
    }
}
