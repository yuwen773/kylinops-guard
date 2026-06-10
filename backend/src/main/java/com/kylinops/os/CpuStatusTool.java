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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CPU 状态工具 — cpu_status_tool (L0 / OS_OBSERVE)
 * <p>
 * 解析 {@code /proc/stat} 计算 CPU 使用率 + 读 {@code /proc/loadavg} 取系统负载
 * + {@code ps aux} 获取 Top 10 CPU 消耗进程。
 * </p>
 */
@Slf4j
@Component
public class CpuStatusTool implements OpsTool {

    public static final String TOOL_NAME = "cpu_status_tool";
    private static final String DESCRIPTION = "获取 CPU 使用率、系统负载和 Top 10 CPU 消耗进程";

    private final ToolDefinition definition;
    private final OsCommandExecutor executor;

    public CpuStatusTool(OsCommandExecutor executor) {
        this.executor = executor;
        this.definition = new ToolDefinition();
        this.definition.setToolName(TOOL_NAME);
        this.definition.setDescription(DESCRIPTION);
        this.definition.setInputSchema("{\"type\":\"object\",\"properties\":{}}");
        this.definition.setOutputSchema("{" +
                "\"type\":\"object\"," +
                "\"properties\":{" +
                "\"usagePercent\":{\"type\":\"number\"}," +
                "\"loadAvg1\":{\"type\":\"number\"}," +
                "\"loadAvg5\":{\"type\":\"number\"}," +
                "\"loadAvg15\":{\"type\":\"number\"}," +
                "\"topProcesses\":{\"type\":\"array\"}" +
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
        log.debug("CpuStatusTool 执行: requestId={}", input.getRequestId());

        // 检查环境兼容性
        if (executor.isWindows()) {
            return ToolResult.failed(TOOL_NAME,
                    "CPU 状态查询需要 Linux 环境（当前为 Windows 环境）", 0);
        }

        Map<String, Object> data = new HashMap<>();

        // 1. CPU 使用率（解析 /proc/stat）
        data.put("usagePercent", getCpuUsagePercent());

        // 2. 系统负载（解析 /proc/loadavg）
        parseLoadAvg(data);

        // 3. Top 10 CPU 消耗进程
        data.put("topProcesses", getTopProcesses());

        String summary = String.format("CPU 使用率: %.1f%%，负载: %.2f / %.2f / %.2f",
                data.getOrDefault("usagePercent", 0.0),
                data.getOrDefault("loadAvg1", 0.0),
                data.getOrDefault("loadAvg5", 0.0),
                data.getOrDefault("loadAvg15", 0.0));

        return ToolResult.success(TOOL_NAME, data, summary, 0);
    }

    /**
     * 解析 /proc/stat 计算 CPU 使用率。
     * <p>
     * /proc/stat 首行格式:
     * {@code cpu  user nice system idle iowait irq softirq steal guest guest_nice}
     * 使用率 = (total - idle) / total * 100
     * </p>
     */
    private double getCpuUsagePercent() {
        // 第一次采样
        long[] cpuStats1 = readCpuStat();
        if (cpuStats1 == null) {
            return -1;
        }

        // 间隔 200ms 做第二次采样以计算差值
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long[] cpuStats2 = readCpuStat();
        if (cpuStats2 == null) {
            return -1;
        }

        long total1 = cpuStats1[0];
        long idle1 = cpuStats1[1];
        long total2 = cpuStats2[0];
        long idle2 = cpuStats2[1];

        long totalDiff = total2 - total1;
        long idleDiff = idle2 - idle1;

        if (totalDiff <= 0) {
            return 0.0;
        }

        return Math.round((1.0 - (double) idleDiff / totalDiff) * 1000.0) / 10.0;
    }

    /**
     * 读取 /proc/stat 返回 [total, idle]。
     */
    private long[] readCpuStat() {
        OsCommandExecutor.FileReadResult result = executor.readFile("/proc/stat");
        if (!result.isSuccess() || result.getLines().isEmpty()) {
            return null;
        }

        String firstLine = result.getLines().get(0);
        // "cpu  user nice system idle iowait irq softirq steal guest guest_nice"
        String[] parts = firstLine.trim().split("\\s+");
        if (parts.length < 5 || !"cpu".equals(parts[0])) {
            return null;
        }

        long total = 0;
        for (int i = 1; i < parts.length; i++) {
            try {
                total += Long.parseLong(parts[i]);
            } catch (NumberFormatException e) {
                // ignore non-numeric fields
            }
        }

        long idle = 0;
        try {
            idle = Long.parseLong(parts[4]); // field 4 = idle
        } catch (NumberFormatException e) {
            // ignore
        }

        return new long[]{total, idle};
    }

    /**
     * 解析 /proc/loadavg 获取系统负载。
     * <p>
     * 格式: "1.23 0.45 0.67 2/345 6789"
     * </p>
     */
    private void parseLoadAvg(Map<String, Object> data) {
        OsCommandExecutor.FileReadResult result = executor.readFile("/proc/loadavg");
        if (result.isSuccess() && !result.getLines().isEmpty()) {
            String[] parts = result.getLines().get(0).trim().split("\\s+");
            if (parts.length >= 3) {
                try {
                    data.put("loadAvg1", Double.parseDouble(parts[0]));
                    data.put("loadAvg5", Double.parseDouble(parts[1]));
                    data.put("loadAvg15", Double.parseDouble(parts[2]));
                    return;
                } catch (NumberFormatException e) {
                    // ignore
                }
            }
        }

        // 降级：尝试 uptime 命令
        OsCommandExecutor.CommandResult cmdResult = executor.execute(List.of("uptime"), 3000);
        if (cmdResult.isSuccess() && !cmdResult.getStdout().isEmpty()) {
            String line = cmdResult.getStdout().get(0);
            // 提取 "load average: x.xx, x.xx, x.xx" 或 "load: x.xx, x.xx, x.xx"
            int loadIdx = line.toLowerCase().indexOf("load average:");
            if (loadIdx < 0) {
                loadIdx = line.toLowerCase().indexOf("load:");
            }
            if (loadIdx >= 0) {
                String loadStr = line.substring(loadIdx)
                        .replaceAll("(?i)load\\s*average:\\s*", "")
                        .replaceAll("(?i)load:\\s*", "")
                        .trim();
                String[] loadParts = loadStr.split(",");
                if (loadParts.length >= 3) {
                    try {
                        data.put("loadAvg1", Double.parseDouble(loadParts[0].trim()));
                        data.put("loadAvg5", Double.parseDouble(loadParts[1].trim()));
                        data.put("loadAvg15", Double.parseDouble(loadParts[2].trim()));
                        return;
                    } catch (NumberFormatException e) {
                        // ignore
                    }
                }
            }
        }

        data.put("loadAvg1", -1.0);
        data.put("loadAvg5", -1.0);
        data.put("loadAvg15", -1.0);
    }

    /**
     * 获取 Top 10 CPU 消耗进程：执行 {@code ps aux --no-headers} 并解析。
     * <p>
     * ps aux 格式: USER PID %CPU %MEM VSZ RSS TTY STAT START TIME COMMAND
     * </p>
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getTopProcesses() {
        List<Map<String, Object>> topProcesses = new ArrayList<>();

        if (!executor.isCommandAvailable("ps")) {
            // 降级：尝试解析 /proc
            return getTopProcessesFromProc();
        }

        OsCommandExecutor.CommandResult result = executor.execute(
                List.of("ps", "aux", "--no-headers"), 5000);

        if (!result.isSuccess() || result.getStdout().isEmpty()) {
            return topProcesses;
        }

        // 解析每一行，按 %CPU 排序取前 10
        List<ProcessEntry> entries = new ArrayList<>();
        for (String line : result.getStdout()) {
            ProcessEntry entry = parsePsLine(line);
            if (entry != null) {
                entries.add(entry);
            }
        }

        // 按 CPU 使用率降序排列
        entries.sort((a, b) -> Double.compare(b.cpuPct, a.cpuPct));

        // 取前 10
        int limit = Math.min(entries.size(), 10);
        for (int i = 0; i < limit; i++) {
            ProcessEntry e = entries.get(i);
            Map<String, Object> proc = new HashMap<>();
            proc.put("pid", e.pid);
            proc.put("user", e.user);
            proc.put("cpuPct", e.cpuPct);
            proc.put("command", e.command);
            topProcesses.add(proc);
        }

        return topProcesses;
    }

    /**
     * 通过 /proc 目录遍历获取进程信息（ps 不可用时的降级方案）。
     */
    private List<Map<String, Object>> getTopProcessesFromProc() {
        List<Map<String, Object>> topProcesses = new ArrayList<>();
        try {
            java.io.File procDir = new java.io.File("/proc");
            java.io.File[] entries = procDir.listFiles(file -> file.isDirectory() && file.getName().matches("\\d+"));
            if (entries == null || entries.length == 0) {
                return topProcesses;
            }

            List<ProcessEntry> processList = new ArrayList<>();
            for (java.io.File entry : entries) {
                try {
                    int pid = Integer.parseInt(entry.getName());
                    String statusPath = entry.getAbsolutePath() + "/status";
                    OsCommandExecutor.FileReadResult statusResult = executor.readFile(statusPath);
                    if (!statusResult.isSuccess() || statusResult.getLines().isEmpty()) {
                        continue;
                    }

                    String name = "";
                    String user = "";
                    for (String line : statusResult.getLines()) {
                        if (line.startsWith("Name:")) {
                            name = line.substring(5).trim();
                        } else if (line.startsWith("Uid:")) {
                            user = line.substring(4).trim().split("\\t")[0];
                        }
                    }

                    // 读取 /proc/[pid]/stat 获取 CPU 使用率（简化：使用 utime + stime）
                    // 为简单起见，这里仅填充基本信息
                    processList.add(new ProcessEntry(pid, user, 0.0, name));

                } catch (Exception e) {
                    // 跳过无法读取的进程
                }
            }

            int limit = Math.min(processList.size(), 10);
            for (int i = 0; i < limit; i++) {
                ProcessEntry e = processList.get(i);
                Map<String, Object> proc = new HashMap<>();
                proc.put("pid", e.pid);
                proc.put("user", e.user);
                proc.put("cpuPct", e.cpuPct);
                proc.put("command", e.command);
                topProcesses.add(proc);
            }

        } catch (Exception e) {
            log.debug("从 /proc 获取进程信息失败: {}", e.getMessage());
        }

        return topProcesses;
    }

    /**
     * 解析 ps aux 输出的一行。
     * <p>
     * 列: USER PID %CPU %MEM VSZ RSS TTY STAT START TIME COMMAND
     * COMMAND 从第 10 列起（可能包含空格）。
     * </p>
     */
    private ProcessEntry parsePsLine(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        // 使用正则按空白分割（保留连续空格为一个分隔符）
        String[] parts = line.trim().split("\\s+");
        if (parts.length < 10) {
            return null;
        }

        try {
            String user = parts[0];
            int pid = Integer.parseInt(parts[1]);
            double cpuPct = Double.parseDouble(parts[2]);

            // COMMAND 从第 10 列开始
            StringBuilder command = new StringBuilder();
            for (int i = 10; i < parts.length; i++) {
                if (command.length() > 0) command.append(" ");
                command.append(parts[i]);
            }

            return new ProcessEntry(pid, user, cpuPct, command.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 进程条目内部类。
     */
    private static class ProcessEntry {
        final int pid;
        final String user;
        final double cpuPct;
        final String command;

        ProcessEntry(int pid, String user, double cpuPct, String command) {
            this.pid = pid;
            this.user = user;
            this.cpuPct = cpuPct;
            this.command = command;
        }
    }
}
