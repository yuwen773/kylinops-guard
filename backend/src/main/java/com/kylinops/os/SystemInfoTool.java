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

import java.io.BufferedReader;
import java.io.StringReader;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统信息工具 — system_info_tool (L0 / OS_OBSERVE)
 * <p>
 * 组合 {@code uname -m} + {@code cat /etc/os-release} + {@code uptime} 三合一，
 * 返回主机名、OS 版本、内核、架构、运行时间等系统基本信息。
 * </p>
 */
@Slf4j
@Component
public class SystemInfoTool implements OpsTool {

    public static final String TOOL_NAME = "system_info_tool";
    private static final String DESCRIPTION = "获取系统基本信息：主机名、操作系统版本、内核版本、硬件架构、运行时间";

    private final ToolDefinition definition;
    private final OsCommandExecutor executor;

    public SystemInfoTool(OsCommandExecutor executor) {
        this.executor = executor;
        this.definition = new ToolDefinition();
        this.definition.setToolName(TOOL_NAME);
        this.definition.setDescription(DESCRIPTION);
        this.definition.setInputSchema("{\"type\":\"object\",\"properties\":{}}");
        this.definition.setOutputSchema("{" +
                "\"type\":\"object\"," +
                "\"properties\":{" +
                "\"hostname\":{\"type\":\"string\"}," +
                "\"osVersion\":{\"type\":\"string\"}," +
                "\"kernel\":{\"type\":\"string\"}," +
                "\"arch\":{\"type\":\"string\"}," +
                "\"uptimeSeconds\":{\"type\":\"integer\"}" +
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
        log.debug("SystemInfoTool 执行: requestId={}", input.getRequestId());

        // 检查环境兼容性
        if (executor.isWindows()) {
            return degradeOnWindows();
        }

        Map<String, Object> data = new HashMap<>();

        // 1. 获取 hostname
        data.put("hostname", getHostname());

        // 2. 获取操作系统版本（解析 /etc/os-release）
        data.put("osVersion", getOsVersion());

        // 3. 获取内核版本（uname -r）
        data.put("kernel", getKernelVersion());

        // 4. 获取硬件架构（uname -m）
        data.put("arch", getArchitecture());

        // 5. 获取运行时间（uptime）
        data.put("uptimeSeconds", getUptimeSeconds());

        return ToolResult.success(TOOL_NAME, data, "系统信息获取成功", 0);
    }

    /**
     * 获取主机名。
     */
    private String getHostname() {
        try {
            // 方式一：InetAddress
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            // 方式二：hostname 命令
            OsCommandExecutor.CommandResult result = executor.execute(List.of("hostname"), 3000);
            if (result.isSuccess() && !result.getStdout().isEmpty()) {
                return result.getStdout().get(0).trim();
            }
            return "unknown";
        }
    }

    /**
     * 获取操作系统版本（解析 /etc/os-release）。
     */
    private String getOsVersion() {
        OsCommandExecutor.FileReadResult fileResult = executor.readFile("/etc/os-release");
        if (fileResult.isSuccess()) {
            String name = null;
            String version = null;
            String versionId = null;

            for (String line : fileResult.getLines()) {
                if (line.startsWith("NAME=")) {
                    name = unquote(line.substring(5));
                } else if (line.startsWith("VERSION=")) {
                    version = unquote(line.substring(8));
                } else if (line.startsWith("VERSION_ID=")) {
                    versionId = unquote(line.substring(11));
                }
            }

            if (name != null) {
                if (version != null) {
                    return name + " " + version;
                }
                if (versionId != null) {
                    return name + " " + versionId;
                }
                return name;
            }
        }

        // 回退：尝试 uname -o / -s
        OsCommandExecutor.CommandResult result = executor.execute(List.of("uname", "-o"), 3000);
        if (result.isSuccess() && !result.getStdout().isEmpty()) {
            return result.getStdout().get(0).trim();
        }

        // 再回退：uname -s
        result = executor.execute(List.of("uname", "-s"), 3000);
        if (result.isSuccess() && !result.getStdout().isEmpty()) {
            return result.getStdout().get(0).trim();
        }

        return "unknown";
    }

    /**
     * 获取内核版本。
     */
    private String getKernelVersion() {
        OsCommandExecutor.CommandResult result = executor.execute(List.of("uname", "-r"), 3000);
        if (result.isSuccess() && !result.getStdout().isEmpty()) {
            return result.getStdout().get(0).trim();
        }
        return "unknown";
    }

    /**
     * 获取硬件架构。
     */
    private String getArchitecture() {
        OsCommandExecutor.CommandResult result = executor.execute(List.of("uname", "-m"), 3000);
        if (result.isSuccess() && !result.getStdout().isEmpty()) {
            return result.getStdout().get(0).trim();
        }
        return "unknown";
    }

    /**
     * 获取系统运行时间（秒）。
     */
    private long getUptimeSeconds() {
        OsCommandExecutor.CommandResult result = executor.execute(List.of("uptime"), 3000);
        if (result.isSuccess() && !result.getStdout().isEmpty()) {
            return parseUptimeSeconds(result.getStdout().get(0));
        }

        // 回退：读取 /proc/uptime
        OsCommandExecutor.FileReadResult fileResult = executor.readFile("/proc/uptime");
        if (fileResult.isSuccess() && !fileResult.getLines().isEmpty()) {
            String[] parts = fileResult.getLines().get(0).trim().split("\\s+");
            if (parts.length > 0) {
                try {
                    return (long) Double.parseDouble(parts[0]);
                } catch (NumberFormatException e) {
                    // ignore
                }
            }
        }

        return 0;
    }

    /**
     * 解析 uptime 命令输出的运行时间。
     * <p>
     * 格式示例: "10:25:43 up 3 days,  2:45,  1 user,  load average: 0.08, 0.03, 0.01"
     * 格式示例: "10:25:43 up 3 days, 2:45, 1 user, load: 0.08, 0.03, 0.01"
     * 格式示例: "10:25:43 up 2:30, 1 user, load average: 0.08, 0.03, 0.01"
     * </p>
     */
    static long parseUptimeSeconds(String uptimeLine) {
        if (uptimeLine == null || uptimeLine.isBlank()) {
            return 0;
        }
        try {
            String lower = uptimeLine.toLowerCase();

            // 提取 "up" 之后、"load" 之前的内容
            int upIdx = lower.indexOf("up");
            if (upIdx < 0) return 0;
            String afterUp = lower.substring(upIdx + 2).trim();

            int loadIdx = afterUp.indexOf("load");
            if (loadIdx > 0) {
                afterUp = afterUp.substring(0, loadIdx).trim();
            }

            // 正则：up [X day(s), ] HH:MM[:SS]
            // uptime 中的时间格式始终为 小时:分钟[:秒]
            // 组1: 天数，组2: 小时，组3: 分钟，组4: 秒（可选）
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                    "(?:(\\d+)\\s+days?,\\s*)?" +  // 天数（可选）
                    "(\\d+):(\\d+)" +               // 小时:分钟
                    "(?::(\\d+))?");                  // :秒（可选）
            java.util.regex.Matcher matcher = pattern.matcher(afterUp);

            if (!matcher.find()) return 0;

            long seconds = 0;

            // 天数
            if (matcher.group(1) != null) {
                seconds += Long.parseLong(matcher.group(1)) * 86400L;
            }

            // 小时 (group 2)
            seconds += Long.parseLong(matcher.group(2)) * 3600L;

            // 分钟 (group 3)
            seconds += Long.parseLong(matcher.group(3)) * 60L;

            // 秒 (group 4, 可选)
            if (matcher.group(4) != null) {
                seconds += Long.parseLong(matcher.group(4));
            }

            return seconds;
        } catch (Exception e) {
            log.debug("解析 uptime 失败: {} - {}", e.getMessage(), uptimeLine);
            return 0;
        }
    }

    /**
     * 去掉 os-release 值的引号（支持双引号和单引号）。
     */
    private static String unquote(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String trimmed = value.trim();
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                || (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    /**
     * Windows 环境降级返回。
     */
    private ToolResult degradeOnWindows() {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("hostname", getHostname());
            data.put("osVersion", System.getProperty("os.name") + " " + System.getProperty("os.version"));
            data.put("kernel", System.getProperty("os.version"));
            data.put("arch", System.getProperty("os.arch"));
            data.put("uptimeSeconds", 0);
            return ToolResult.success(TOOL_NAME, data,
                    "系统信息获取成功（注意：当前为 Windows 环境，部分信息来自 JVM 属性）", 0);
        } catch (Exception e) {
            return ToolResult.failed(TOOL_NAME,
                    "系统信息查询需要 Linux 环境（当前为 Windows 环境）", 0);
        }
    }
}
