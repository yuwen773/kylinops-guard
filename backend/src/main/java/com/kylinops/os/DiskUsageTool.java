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
 * 磁盘使用率工具 — disk_usage_tool (L0 / DISK)
 * <p>
 * 执行 {@code df -h} 获取磁盘分区使用情况，过滤掉 tmpfs / devtmpfs / overlay 等伪文件系统。
 * </p>
 */
@Slf4j
@Component
public class DiskUsageTool implements OpsTool {

    public static final String TOOL_NAME = "disk_usage_tool";
    private static final String DESCRIPTION = "查看磁盘分区使用率：各挂载点、容量、已用空间、使用率";

    /** 需要跳过的伪文件系统类型 */
    private static final List<String> PSEUDO_FS_TYPES = List.of("tmpfs", "devtmpfs", "overlay",
            "squashfs", "proc", "sysfs", "cgroup", "cgroup2", "devpts", "hugetlbfs",
            "mqueue", "pstore", "securityfs", "efivarfs", "bpf", "autofs");

    private final ToolDefinition definition;
    private final OsCommandExecutor executor;

    public DiskUsageTool(OsCommandExecutor executor) {
        this.executor = executor;
        this.definition = new ToolDefinition();
        this.definition.setToolName(TOOL_NAME);
        this.definition.setDescription(DESCRIPTION);
        this.definition.setInputSchema("{\"type\":\"object\",\"properties\":{}}");
        this.definition.setOutputSchema("{" +
                "\"type\":\"object\"," +
                "\"properties\":{" +
                "\"partitions\":{\"type\":\"array\"}" +
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
        log.debug("DiskUsageTool 执行: requestId={}", input.getRequestId());

        if (executor.isWindows()) {
            return ToolResult.failed(TOOL_NAME,
                    "磁盘使用率查询需要 Linux 环境（当前为 Windows 环境）", 0);
        }

        Map<String, Object> data = new HashMap<>();

        if (executor.isCommandAvailable("df")) {
            parseDfOutput(data);
        } else {
            // 降级尝试 /proc/mounts
            parseProcMounts(data);
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> partitions = (List<Map<String, Object>>) data.getOrDefault("partitions", List.of());
        String summary = String.format("共 %d 个分区", partitions.size());

        return ToolResult.success(TOOL_NAME, data, summary, 0);
    }

    /**
     * 使用 df -h 解析分区信息。
     * <p>
     * 输出格式:
     * <pre>
     * Filesystem      Size  Used Avail Use% Mounted on
     * devtmpfs        7.7G     0  7.7G   0% /dev
     * tmpfs           7.8G  2.1M  7.8G   1% /dev/shm
     * /dev/sda1        98G   85G   13G  87% /
     * </pre>
     * </p>
     */
    private void parseDfOutput(Map<String, Object> data) {
        List<Map<String, Object>> partitions = new ArrayList<>();

        OsCommandExecutor.CommandResult result = executor.execute(
                List.of("df", "-h"), 3000);

        if (!result.isSuccess() || result.getStdout().size() <= 1) {
            data.put("partitions", partitions);
            data.put("note", "df 执行失败或无输出");
            return;
        }

        for (int i = 1; i < result.getStdout().size(); i++) {
            String line = result.getStdout().get(i).trim();
            if (line.isBlank()) continue;

            String[] parts = line.split("\\s+");
            if (parts.length < 6) continue;

            String fsType = inferFilesystemType(parts[0]);
            if (PSEUDO_FS_TYPES.contains(fsType)) {
                continue;
            }

            Map<String, Object> partition = new HashMap<>();
            partition.put("filesystem", parts[0]);
            partition.put("size", parts[1]);
            partition.put("used", parts[2]);
            partition.put("available", parts[3]);
            partition.put("usedPercent", parseUsedPercent(parts[4]));
            partition.put("mount", parts[5]);
            partition.put("fsType", fsType);

            partitions.add(partition);
        }

        data.put("partitions", partitions);
    }

    /**
     * 降级方案：解析 /proc/mounts + statvfs 仿真。
     * <p>
     * 由于 Java 中调用 statvfs 需要 JNI，此方案作为最后手段仅基于 /proc/mounts
     * 列出挂载点信息（不含用量）。
     * </p>
     */
    private void parseProcMounts(Map<String, Object> data) {
        List<Map<String, Object>> partitions = new ArrayList<>();

        OsCommandExecutor.FileReadResult result = executor.readFile("/proc/mounts");
        if (!result.isSuccess()) {
            data.put("partitions", partitions);
            data.put("note", "无法读取 /proc/mounts");
            return;
        }

        // 每条记录: device mount_point fs_type options dump_order pass_order
        for (String line : result.getLines()) {
            String trimmed = line.trim();
            if (trimmed.isBlank()) continue;

            String[] parts = trimmed.split("\\s+");
            if (parts.length < 3) continue;

            String fsType = parts[2];
            if (PSEUDO_FS_TYPES.contains(fsType)) {
                continue;
            }

            Map<String, Object> partition = new HashMap<>();
            partition.put("filesystem", parts[0]);
            partition.put("mount", parts[1]);
            partition.put("fsType", fsType);
            partition.put("note", "使用 df -h 获取详细用量");

            partitions.add(partition);
        }

        data.put("partitions", partitions);
        data.put("dfNote", "df 命令不可用，用量数据来自 /proc/mounts（无用量百分比）");
    }

    /**
     * 通过设备路径推断文件系统类型。
     */
    private String inferFilesystemType(String device) {
        if (device.startsWith("/dev/sd")) return "ext4";
        if (device.startsWith("/dev/nvme")) return "ext4";
        if (device.startsWith("/dev/vd")) return "ext4";
        if (device.startsWith("/dev/mapper")) return "ext4";
        if (device.startsWith("tmpfs")) return "tmpfs";
        if (device.startsWith("devtmpfs")) return "devtmpfs";
        if (device.startsWith("overlay")) return "overlay";
        return "unknown";
    }

    /**
     * 解析 "87%" 格式的使用率百分比。
     */
    private int parseUsedPercent(String value) {
        if (value == null || value.isBlank()) return 0;
        String cleaned = value.replace("%", "").trim();
        try {
            return Integer.parseInt(cleaned);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
