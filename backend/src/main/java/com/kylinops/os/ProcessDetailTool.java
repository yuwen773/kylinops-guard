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
 * 进程详情工具 — process_detail_tool (L0 / PROCESS)
 * <p>
 * 根据 PID 查询单个进程的详细信息：
 * PID、PPID、用户、命令、状态、运行时间、CPU 时间、内存占用。
 * </p>
 */
@Slf4j
@Component
public class ProcessDetailTool implements OpsTool {

    public static final String TOOL_NAME = "process_detail_tool";
    private static final String DESCRIPTION = "根据 PID 查询单个进程的详细信息";

    private final ToolDefinition definition;
    private final OsCommandExecutor executor;

    public ProcessDetailTool(OsCommandExecutor executor) {
        this.executor = executor;
        this.definition = new ToolDefinition();
        this.definition.setToolName(TOOL_NAME);
        this.definition.setDescription(DESCRIPTION);
        this.definition.setInputSchema("{" +
                "\"type\":\"object\"," +
                "\"properties\":{" +
                "\"pid\":{\"type\":\"integer\",\"description\":\"进程 ID\"}" +
                "}," +
                "\"required\":[\"pid\"]}");
        this.definition.setOutputSchema("{" +
                "\"type\":\"object\"," +
                "\"properties\":{" +
                "\"pid\":{\"type\":\"integer\"}," +
                "\"ppid\":{\"type\":\"integer\"}," +
                "\"user\":{\"type\":\"string\"}," +
                "\"command\":{\"type\":\"string\"}," +
                "\"state\":{\"type\":\"string\"}," +
                "\"startedAt\":{\"type\":\"string\"}," +
                "\"cpuTime\":{\"type\":\"string\"}," +
                "\"memMB\":{\"type\":\"number\"}" +
                "}}");
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
        log.debug("ProcessDetailTool 执行: requestId={}", input.getRequestId());

        if (executor.isWindows()) {
            return ToolResult.failed(TOOL_NAME,
                    "进程详情查询需要 Linux 环境（当前为 Windows 环境）", 0);
        }

        // 解析 PID 参数
        int pid;
        try {
            pid = parsePid(input);
        } catch (IllegalArgumentException e) {
            return ToolResult.failed(TOOL_NAME, e.getMessage(), 0);
        }

        Map<String, Object> data = new HashMap<>();

        if (executor.isCommandAvailable("ps")) {
            boolean found = parseWithPs(data, pid);
            if (!found) {
                return ToolResult.failed(TOOL_NAME,
                        "进程 " + pid + " 不存在", 0);
            }
        } else {
            // 降级：解析 /proc/[pid]
            boolean found = parseProcDirectory(data, pid);
            if (!found) {
                return ToolResult.failed(TOOL_NAME,
                        "进程 " + pid + " 不存在", 0);
            }
        }

        String summary = String.format("进程 %d (%s) — 状态: %s",
                pid, data.getOrDefault("command", ""), data.getOrDefault("state", "unknown"));

        return ToolResult.success(TOOL_NAME, data, summary, 0);
    }

    /**
     * 解析 PID 参数。
     */
    private int parsePid(ToolInput input) {
        if (input.getParams() == null || !input.getParams().containsKey("pid")) {
            throw new IllegalArgumentException("缺少必填参数: pid");
        }

        Object pidObj = input.getParams().get("pid");
        String pidStr = pidObj.toString().trim();

        if (!BaseOSValidator.isValidPid(pidStr)) {
            throw new IllegalArgumentException("无效的 PID: " + pidStr + "（必须为正整数）");
        }

        return Integer.parseInt(pidStr);
    }

    /**
     * 使用 ps 命令获取进程详情。
     * <p>
     * 命令: ps -p &lt;pid&gt; -o pid,ppid,user,comm,state,etime,%cpu,%rss --no-headers
     * </p>
     */
    private boolean parseWithPs(Map<String, Object> data, int pid) {
        OsCommandExecutor.CommandResult result = executor.execute(
                List.of("ps", "-p", String.valueOf(pid),
                        "-o", "pid,ppid,user,comm,state,etime,%cpu,%rss", "--no-headers"),
                3000);

        if (!result.isSuccess() || result.getStdout().isEmpty()) {
            return false;
        }

        String line = result.getStdout().get(0).trim();
        if (line.isBlank()) return false;

        String[] parts = line.trim().split("\\s+");
        if (parts.length < 8) return false;

        try {
            data.put("pid", Integer.parseInt(parts[0]));
            data.put("ppid", Integer.parseInt(parts[1]));
            data.put("user", parts[2]);
            data.put("command", parts[3]);
            data.put("state", parts[4]);
            data.put("startedAt", parts[5]);
            data.put("cpuTime", parts[6]);
            // RSS 以 KB 为单位，转换为 MB
            int rssKb = Integer.parseInt(parts[7]);
            data.put("memMB", Math.round((double) rssKb / 1024.0 * 100.0) / 100.0);

            return true;
        } catch (NumberFormatException e) {
            log.debug("解析 ps 输出失败: {} - {}", line, e.getMessage());
            return false;
        }
    }

    /**
     * 降级方案：解析 /proc/[pid]/status 和 /proc/[pid]/stat。
     */
    private boolean parseProcDirectory(Map<String, Object> data, int pid) {
        String statusPath = "/proc/" + pid + "/status";
        OsCommandExecutor.FileReadResult statusResult = executor.readFile(statusPath);
        if (!statusResult.isSuccess()) {
            return false;
        }

        data.put("pid", pid);

        for (String line : statusResult.getLines()) {
            if (line.startsWith("Name:")) {
                data.put("command", line.substring(5).trim());
            } else if (line.startsWith("State:")) {
                data.put("state", line.substring(6).trim());
            } else if (line.startsWith("PPid:")) {
                try {
                    data.put("ppid", Integer.parseInt(line.substring(5).trim()));
                } catch (NumberFormatException e) {
                    data.put("ppid", 0);
                }
            } else if (line.startsWith("Uid:")) {
                data.put("user", line.substring(4).trim().split("\\t")[0]);
            } else if (line.startsWith("VmRSS:")) {
                // VmRSS: 12345 kB
                String rssStr = line.substring(6).trim().replace("kB", "").trim();
                try {
                    int rssKb = Integer.parseInt(rssStr);
                    data.put("memMB", Math.round((double) rssKb / 1024.0 * 100.0) / 100.0);
                } catch (NumberFormatException e) {
                    data.put("memMB", 0);
                }
            }
        }

        data.putIfAbsent("command", "unknown");
        data.putIfAbsent("state", "unknown");
        data.putIfAbsent("ppid", 0);
        data.putIfAbsent("user", "unknown");
        data.putIfAbsent("memMB", 0);
        data.putIfAbsent("startedAt", "unknown");
        data.putIfAbsent("cpuTime", "unknown");
        data.put("note", "数据来自 /proc（部分字段可能不完整）");

        return true;
    }
}
