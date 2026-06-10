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
 * 大文件扫描工具 — large_file_scan_tool (L0 / DISK)
 * <p>
 * 在允许的扫描根目录白名单（{@code /var/log}, {@code /tmp}, {@code /home}）下
 * 执行 {@code du -ah --max-depth=3} 查找大文件，按大小降序排列，最多返回 50 条。
 * </p>
 *
 * <h3>安全约束</h3>
 * <ul>
 *   <li>扫描根目录白名单：{@code /var/log}, {@code /tmp}, {@code /home}</li>
 *   <li>最大扫描深度：4</li>
 *   <li>总执行时间：≤ 5s</li>
 *   <li>绝对禁止扫描：{@code /etc}, {@code /usr}, {@code /bin}, {@code /boot}, {@code /dev}</li>
 *   <li>返回上限：最多 50 条</li>
 * </ul>
 */
@Slf4j
@Component
public class LargeFileScanTool implements OpsTool {

    public static final String TOOL_NAME = "large_file_scan_tool";
    private static final String DESCRIPTION = "扫描大文件：在 /var/log、/tmp、/home 下查找大文件，按大小排序";

    private static final int MAX_DEPTH = 4;
    private static final int MAX_FILES = 50;
    private static final long TIMEOUT_MS = 5000L;

    private final ToolDefinition definition;
    private final OsCommandExecutor executor;

    public LargeFileScanTool(OsCommandExecutor executor) {
        this.executor = executor;
        this.definition = new ToolDefinition();
        this.definition.setToolName(TOOL_NAME);
        this.definition.setDescription(DESCRIPTION);
        this.definition.setInputSchema("{" +
                "\"type\":\"object\"," +
                "\"properties\":{" +
                "\"scanDirs\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"description\":\"可选：指定扫描目录子集，默认扫描所有白名单目录\"}" +
                "}}");
        this.definition.setOutputSchema("{" +
                "\"type\":\"object\"," +
                "\"properties\":{" +
                "\"files\":{\"type\":\"array\"}," +
                "\"scanPaths\":{\"type\":\"array\"}," +
                "\"truncated\":{\"type\":\"boolean\"}" +
                "}}");
        this.definition.setRiskLevel(RiskLevel.L0);
        this.definition.setPermissionType(PermissionType.READ);
        this.definition.setToolStatus(ToolStatus.ENABLED);
        this.definition.setTimeoutMs(TIMEOUT_MS);
        this.definition.setAuditRequired(true);
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult execute(ToolInput input) {
        log.debug("LargeFileScanTool 执行: requestId={}", input.getRequestId());

        if (executor.isWindows()) {
            return ToolResult.failed(TOOL_NAME,
                    "大文件扫描需要 Linux 环境（当前为 Windows 环境）", 0);
        }

        if (!executor.isCommandAvailable("du")) {
            return ToolResult.failed(TOOL_NAME,
                    "大文件扫描需要 du 命令（当前系统不可用）", 0);
        }

        // 确定扫描目录
        List<String> scanDirs = resolveScanDirs(input);

        Map<String, Object> data = new HashMap<>();
        data.put("scanPaths", scanDirs);
        data.put("files", scanFiles(scanDirs));

        String summary = String.format("扫描了 %d 个目录，找到 %d 个大文件",
                scanDirs.size(), ((List<Map<String, Object>>) data.get("files")).size());

        return ToolResult.success(TOOL_NAME, data, summary, 0);
    }

    /**
     * 解析输入参数确定扫描目录。
     * <p>
     * 如果用户指定了 {@code scanDirs} 参数，则使用白名单过滤后使用；
     * 否则使用全部白名单目录。
     * </p>
     */
    private List<String> resolveScanDirs(ToolInput input) {
        if (input.getParams() != null && input.getParams().containsKey("scanDirs")) {
            Object dirsObj = input.getParams().get("scanDirs");
            if (dirsObj instanceof List) {
                List<String> userDirs = (List<String>) dirsObj;
                List<String> validated = new ArrayList<>();
                for (String dir : userDirs) {
                    try {
                        String safePath = BaseOSValidator.sanitizePath(dir, BaseOSValidator.ALLOWED_SCAN_ROOTS);
                        validated.add(safePath);
                    } catch (IllegalArgumentException e) {
                        log.debug("跳过非法扫描路径: {} - {}", dir, e.getMessage());
                    }
                }
                if (!validated.isEmpty()) {
                    return validated;
                }
            }
        }
        // 默认使用全部白名单目录
        return new ArrayList<>(BaseOSValidator.ALLOWED_SCAN_ROOTS);
    }

    /**
     * 对每个扫描目录执行 du，收集结果按大小排序并截断。
     * <p>
     * 使用 {@code du -ah --max-depth=N <path> 2>/dev/null}，
     * 在 Java 中解析排序，避免使用 shell 管道。
     * </p>
     */
    private List<Map<String, Object>> scanFiles(List<String> scanDirs) {
        List<FileEntry> allFiles = new ArrayList<>();

        for (String dir : scanDirs) {
            // 构建命令：不用管道，使用固定模板
            List<String> command = Arrays.asList(
                    "du", "-ah", "--max-depth=" + MAX_DEPTH, dir);

            OsCommandExecutor.CommandResult result = executor.execute(command, TIMEOUT_MS - 500);

            if (!result.isSuccess()) {
                log.debug("扫描目录 {} 失败: {}", dir, result.getErrorMessage());
                continue;
            }

            for (String line : result.getStdout()) {
                parseDuLine(line).ifPresent(allFiles::add);
            }
        }

        // 按大小降序排列
        allFiles.sort((a, b) -> Long.compare(b.sizeBytes, a.sizeBytes));

        // 取前 MAX_FILES 条
        int limit = Math.min(allFiles.size(), MAX_FILES);
        List<Map<String, Object>> files = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            FileEntry entry = allFiles.get(i);
            Map<String, Object> fileInfo = new HashMap<>();
            fileInfo.put("path", entry.path);
            fileInfo.put("sizeMB", entry.sizeMB);
            fileInfo.put("sizeBytes", entry.sizeBytes);
            files.add(fileInfo);
        }

        return files;
    }

    /**
     * 解析 du 输出的一行。
     * <p>
     * 格式: "size\tpath"，如 "1.5G\t/var/log/syslog"
     * </p>
     */
    private Optional<FileEntry> parseDuLine(String line) {
        if (line == null || line.isBlank()) {
            return Optional.empty();
        }

        String trimmed = line.trim();
        // 分割第一个空白
        int splitIdx = trimmed.indexOf('\t');
        if (splitIdx < 0) {
            splitIdx = trimmed.indexOf(' ');
        }
        if (splitIdx < 0) return Optional.empty();

        String sizeStr = trimmed.substring(0, splitIdx).trim();
        String path = trimmed.substring(splitIdx + 1).trim();

        // 跳过总计行
        if ("total".equals(path)) return Optional.empty();

        long sizeBytes = parseSize(sizeStr);
        if (sizeBytes <= 0) return Optional.empty();

        double sizeMB = Math.round((double) sizeBytes / (1024 * 1024) * 100.0) / 100.0;

        return Optional.of(new FileEntry(path, sizeMB, sizeBytes));
    }

    /**
     * 解析 du 大小后缀（K, M, G, T）。
     */
    static long parseSize(String sizeStr) {
        if (sizeStr == null || sizeStr.isBlank()) return 0;
        sizeStr = sizeStr.trim().toUpperCase();

        try {
            if (sizeStr.endsWith("T")) {
                return (long) (Double.parseDouble(sizeStr.substring(0, sizeStr.length() - 1)) * 1024 * 1024 * 1024 * 1024);
            } else if (sizeStr.endsWith("G")) {
                return (long) (Double.parseDouble(sizeStr.substring(0, sizeStr.length() - 1)) * 1024 * 1024 * 1024);
            } else if (sizeStr.endsWith("M")) {
                return (long) (Double.parseDouble(sizeStr.substring(0, sizeStr.length() - 1)) * 1024 * 1024);
            } else if (sizeStr.endsWith("K")) {
                return (long) (Double.parseDouble(sizeStr.substring(0, sizeStr.length() - 1)) * 1024);
            } else {
                return (long) Double.parseDouble(sizeStr);
            }
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 文件条目内部类。
     */
    private static class FileEntry {
        final String path;
        final double sizeMB;
        final long sizeBytes;

        FileEntry(String path, double sizeMB, long sizeBytes) {
            this.path = path;
            this.sizeMB = sizeMB;
            this.sizeBytes = sizeBytes;
        }
    }
}
