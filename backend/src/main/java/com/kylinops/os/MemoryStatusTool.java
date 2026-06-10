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
 * 内存状态工具 — memory_status_tool (L0 / OS_OBSERVE)
 * <p>
 * 执行 {@code free -m}（或降级解析 {@code /proc/meminfo}）
 * 获取物理内存、SWAP 的使用详情。
 * </p>
 */
@Slf4j
@Component
public class MemoryStatusTool implements OpsTool {

    public static final String TOOL_NAME = "memory_status_tool";
    private static final String DESCRIPTION = "获取内存使用详情：物理内存总量/已用/空闲、SWAP 使用率";

    private final ToolDefinition definition;
    private final OsCommandExecutor executor;

    public MemoryStatusTool(OsCommandExecutor executor) {
        this.executor = executor;
        this.definition = new ToolDefinition();
        this.definition.setToolName(TOOL_NAME);
        this.definition.setDescription(DESCRIPTION);
        this.definition.setInputSchema("{\"type\":\"object\",\"properties\":{}}");
        this.definition.setOutputSchema("{" +
                "\"type\":\"object\"," +
                "\"properties\":{" +
                "\"totalMB\":{\"type\":\"integer\"}," +
                "\"usedMB\":{\"type\":\"integer\"}," +
                "\"freeMB\":{\"type\":\"integer\"}," +
                "\"swapTotalMB\":{\"type\":\"integer\"}," +
                "\"swapUsedMB\":{\"type\":\"integer\"}," +
                "\"usedPercent\":{\"type\":\"number\"}" +
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
        log.debug("MemoryStatusTool 执行: requestId={}", input.getRequestId());

        if (executor.isWindows()) {
            return ToolResult.failed(TOOL_NAME,
                    "内存状态查询需要 Linux 环境（当前为 Windows 环境）", 0);
        }

        Map<String, Object> data = new HashMap<>();

        if (executor.isCommandAvailable("free")) {
            parseFreeOutput(data);
        } else {
            parseProcMeminfo(data);
        }

        String summary = String.format("物理内存: %dMB / %dMB (%.1f%%)",
                data.getOrDefault("usedMB", 0),
                data.getOrDefault("totalMB", 0),
                data.getOrDefault("usedPercent", 0.0));

        if ((int) data.getOrDefault("swapTotalMB", 0) > 0) {
            summary += String.format("，SWAP: %dMB / %dMB",
                    data.getOrDefault("swapUsedMB", 0),
                    data.getOrDefault("swapTotalMB", 0));
        }

        return ToolResult.success(TOOL_NAME, data, summary, 0);
    }

    /**
     * 使用 free -m 解析内存信息。
     * <p>
     * 输出格式:
     * <pre>
     *               total        used        free      shared  buff/cache   available
     * Mem:          15934        4567        2345         123        9022       11023
     * Swap:          2048         123        1925
     * </pre>
     * </p>
     */
    private void parseFreeOutput(Map<String, Object> data) {
        OsCommandExecutor.CommandResult result = executor.execute(List.of("free", "-m"), 3000);
        if (!result.isSuccess()) {
            // 降级
            parseProcMeminfo(data);
            return;
        }

        for (String line : result.getStdout()) {
            String trimmed = line.trim();
            if (trimmed.startsWith("Mem:")) {
                String[] parts = trimmed.split("\\s+");
                if (parts.length >= 3) {
                    try {
                        int total = Integer.parseInt(parts[1]);
                        int used = Integer.parseInt(parts[2]);
                        int free = total - used; // 注意：free 命令的 used 含 buff/cache

                        // 更精确的算法：used = total - available
                        if (parts.length >= 7) {
                            int available = Integer.parseInt(parts[6]);
                            used = total - available;
                        }

                        data.put("totalMB", total);
                        data.put("usedMB", used);
                        data.put("freeMB", total - used);
                        data.put("usedPercent", total > 0
                                ? Math.round((double) used / total * 1000.0) / 10.0
                                : 0.0);
                    } catch (NumberFormatException e) {
                        // ignore
                    }
                }
            } else if (trimmed.startsWith("Swap:")) {
                String[] parts = trimmed.split("\\s+");
                if (parts.length >= 3) {
                    try {
                        data.put("swapTotalMB", Integer.parseInt(parts[1]));
                        data.put("swapUsedMB", Integer.parseInt(parts[2]));
                    } catch (NumberFormatException e) {
                        // ignore
                    }
                }
            }
        }

        // 填充 swap 默认值
        data.putIfAbsent("swapTotalMB", 0);
        data.putIfAbsent("swapUsedMB", 0);
    }

    /**
     * 降级方案：解析 /proc/meminfo。
     * <p>
     * 格式:
     * <pre>
     * MemTotal:       16322512 kB
     * MemFree:         2345678 kB
     * MemAvailable:   11023456 kB
     * SwapTotal:       2097152 kB
     * SwapFree:        1923456 kB
     * </pre>
     * </p>
     */
    private void parseProcMeminfo(Map<String, Object> data) {
        OsCommandExecutor.FileReadResult result = executor.readFile("/proc/meminfo");
        if (!result.isSuccess()) {
            data.put("totalMB", 0);
            data.put("usedMB", 0);
            data.put("freeMB", 0);
            data.put("swapTotalMB", 0);
            data.put("swapUsedMB", 0);
            data.put("usedPercent", 0.0);
            return;
        }

        long memTotalKB = 0, memFreeKB = 0, memAvailableKB = 0;
        long swapTotalKB = 0, swapFreeKB = 0;

        for (String line : result.getLines()) {
            if (line.startsWith("MemTotal:")) {
                memTotalKB = parseKbValue(line);
            } else if (line.startsWith("MemFree:")) {
                memFreeKB = parseKbValue(line);
            } else if (line.startsWith("MemAvailable:")) {
                memAvailableKB = parseKbValue(line);
            } else if (line.startsWith("SwapTotal:")) {
                swapTotalKB = parseKbValue(line);
            } else if (line.startsWith("SwapFree:")) {
                swapFreeKB = parseKbValue(line);
            }
        }

        int totalMB = (int) (memTotalKB / 1024);
        int availableMB = (int) (memAvailableKB / 1024);
        int usedMB = totalMB - availableMB;
        int freeMB = (int) (memFreeKB / 1024);

        data.put("totalMB", totalMB);
        data.put("usedMB", Math.max(usedMB, 0));
        data.put("freeMB", freeMB);
        data.put("swapTotalMB", (int) (swapTotalKB / 1024));
        data.put("swapUsedMB", (int) ((swapTotalKB - swapFreeKB) / 1024));
        data.put("usedPercent", totalMB > 0
                ? Math.round((double) Math.max(usedMB, 0) / totalMB * 1000.0) / 10.0
                : 0.0);
    }

    /**
     * 解析 /proc/meminfo 中 "Key:       value kB" 格式的值。
     */
    private long parseKbValue(String line) {
        // "MemTotal:       16322512 kB"
        String[] parts = line.split(":\\s+");
        if (parts.length < 2) return 0;
        String[] valueParts = parts[1].trim().split("\\s+");
        try {
            return Long.parseLong(valueParts[0]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
