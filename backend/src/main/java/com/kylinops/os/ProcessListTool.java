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

import java.util.*;

/**
 * 进程列表工具 — process_list_tool (L0 / PROCESS)
 * <p>
 * 执行 {@code ps aux --no-headers} 获取当前所有进程的快照，
 * 返回 CPU 使用率最高的前 50 个进程。
 * </p>
 */
@Slf4j
@Component
public class ProcessListTool implements OpsTool {

    public static final String TOOL_NAME = "process_list_tool";
    private static final String DESCRIPTION = "列出当前所有进程（按 CPU 使用率排序，最多返回 50 条）";

    private static final int MAX_PROCESSES = 50;

    private final ToolDefinition definition;
    private final OsCommandExecutor executor;

    public ProcessListTool(OsCommandExecutor executor) {
        this.executor = executor;
        this.definition = new ToolDefinition();
        this.definition.setToolName(TOOL_NAME);
        this.definition.setDescription(DESCRIPTION);
        this.definition.setInputSchema("{" +
                "\"type\":\"object\"," +
                "\"properties\":{" +
                "\"sortBy\":{\"type\":\"string\",\"enum\":[\"cpu\",\"mem\",\"pid\"],\"description\":\"排序字段（默认 cpu）\"}," +
                "\"limit\":{\"type\":\"integer\",\"description\":\"返回条数上限（默认 50，最大 50）\"}" +
                "}}");
        this.definition.setOutputSchema("{" +
                "\"type\":\"object\"," +
                "\"properties\":{" +
                "\"processes\":{\"type\":\"array\"}," +
                "\"total\":{\"type\":\"integer\"}" +
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
        log.debug("ProcessListTool 执行: requestId={}", input.getRequestId());

        if (executor.isWindows()) {
            return ToolResult.failed(TOOL_NAME,
                    "进程列表查询需要 Linux 环境（当前为 Windows 环境）", 0);
        }

        // 解析参数
        String sortBy = "cpu";
        int limit = MAX_PROCESSES;
        if (input.getParams() != null) {
            if (input.getParams().containsKey("sortBy")) {
                sortBy = input.getParams().get("sortBy").toString();
            }
            if (input.getParams().containsKey("limit")) {
                try {
                    limit = Math.min(Integer.parseInt(input.getParams().get("limit").toString()), MAX_PROCESSES);
                } catch (NumberFormatException e) {
                    // 使用默认值
                }
            }
        }

        Map<String, Object> data = new HashMap<>();

        if (executor.isCommandAvailable("ps")) {
            parsePsOutput(data, sortBy, limit);
        } else {
            parseProcDirectory(data, limit);
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> processes = (List<Map<String, Object>>) data.getOrDefault("processes", List.of());
        String summary = String.format("共 %d 个进程（按 %s 排序显示前 %d 个）",
                data.getOrDefault("total", 0), sortBy, processes.size());

        return ToolResult.success(TOOL_NAME, data, summary, 0);
    }

    /**
     * 执行 ps aux 并解析输出。
     * <p>
     * ps aux 输出格式:
     * USER       PID %CPU %MEM    VSZ   RSS TTY      STAT START   TIME COMMAND
     * </p>
     */
    @SuppressWarnings("unchecked")
    private void parsePsOutput(Map<String, Object> data, String sortBy, int limit) {
        OsCommandExecutor.CommandResult result = executor.execute(
                List.of("ps", "aux", "--no-headers"), 5000);

        if (!result.isSuccess()) {
            data.put("processes", List.of());
            data.put("total", 0);
            return;
        }

        List<ProcessEntry> entries = new ArrayList<>();
        for (String line : result.getStdout()) {
            ProcessEntry entry = parsePsLine(line);
            if (entry != null) {
                entries.add(entry);
            }
        }

        // 排序
        switch (sortBy) {
            case "mem":
                entries.sort((a, b) -> Double.compare(b.memPct, a.memPct));
                break;
            case "pid":
                entries.sort((a, b) -> Integer.compare(a.pid, b.pid));
                break;
            default: // cpu
                entries.sort((a, b) -> Double.compare(b.cpuPct, a.cpuPct));
                break;
        }

        // 截断
        int actualLimit = Math.min(entries.size(), limit);
        List<Map<String, Object>> processes = new ArrayList<>(actualLimit);
        for (int i = 0; i < actualLimit; i++) {
            ProcessEntry e = entries.get(i);
            Map<String, Object> proc = new HashMap<>();
            proc.put("pid", e.pid);
            proc.put("user", e.user);
            proc.put("cpuPct", e.cpuPct);
            proc.put("memPct", e.memPct);
            proc.put("command", e.command);
            processes.add(proc);
        }

        data.put("processes", processes);
        data.put("total", entries.size());
    }

    /**
     * 降级方案：遍历 /proc/[pid]/status 获取进程基本信息。
     */
    private void parseProcDirectory(Map<String, Object> data, int limit) {
        List<Map<String, Object>> processes = new ArrayList<>();
        int total = 0;

        try {
            java.io.File procDir = new java.io.File("/proc");
            java.io.File[] pidDirs = procDir.listFiles(file ->
                    file.isDirectory() && file.getName().matches("\\d+"));
            if (pidDirs == null) {
                data.put("processes", processes);
                data.put("total", 0);
                return;
            }

            // 按 PID 排序
            List<java.io.File> sorted = new ArrayList<>(Arrays.asList(pidDirs));
            sorted.sort((a, b) -> Integer.compare(
                    Integer.parseInt(a.getName()), Integer.parseInt(b.getName())));

            for (java.io.File pidDir : sorted) {
                if (processes.size() >= limit) break;

                try {
                    int pid = Integer.parseInt(pidDir.getName());
                    String statusPath = pidDir.getAbsolutePath() + "/status";
                    OsCommandExecutor.FileReadResult statusResult = executor.readFile(statusPath);
                    if (!statusResult.isSuccess()) continue;

                    String name = "";
                    String state = "";
                    for (String line : statusResult.getLines()) {
                        if (line.startsWith("Name:")) {
                            name = line.substring(5).trim();
                        } else if (line.startsWith("State:")) {
                            state = line.substring(6).trim();
                        }
                    }

                    Map<String, Object> proc = new HashMap<>();
                    proc.put("pid", pid);
                    proc.put("user", "");
                    proc.put("cpuPct", 0.0);
                    proc.put("memPct", 0.0);
                    proc.put("command", name + " (" + state + ")");
                    processes.add(proc);
                    total++;

                } catch (Exception e) {
                    // 跳过无法读取的进程
                }
            }

        } catch (Exception e) {
            log.debug("从 /proc 获取进程列表失败: {}", e.getMessage());
        }

        data.put("processes", processes);
        data.put("total", total);
        data.put("note", "ps 命令不可用，数据来自 /proc（无 CPU/内存使用率）");
    }

    /**
     * 解析 ps aux --no-headers 输出的一行。
     * <p>
     * 列: USER PID %CPU %MEM VSZ RSS TTY STAT START TIME COMMAND
     * COMMAND 从第 10 列起（可能含空格）。
     * </p>
     */
    private ProcessEntry parsePsLine(String line) {
        if (line == null || line.isBlank()) return null;

        String[] parts = line.trim().split("\\s+");
        if (parts.length < 10) return null;

        try {
            String user = parts[0];
            int pid = Integer.parseInt(parts[1]);
            double cpuPct = Double.parseDouble(parts[2]);
            double memPct = Double.parseDouble(parts[3]);

            // COMMAND 从第 10 列开始（0-indexed）
            StringBuilder command = new StringBuilder();
            for (int i = 10; i < parts.length; i++) {
                if (command.length() > 0) command.append(" ");
                command.append(parts[i]);
            }

            return new ProcessEntry(pid, user, cpuPct, memPct, command.toString());
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
        final double memPct;
        final String command;

        ProcessEntry(int pid, String user, double cpuPct, double memPct, String command) {
            this.pid = pid;
            this.user = user;
            this.cpuPct = cpuPct;
            this.memPct = memPct;
            this.command = command;
        }
    }
}
