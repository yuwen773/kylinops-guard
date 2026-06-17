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
import java.util.regex.Pattern;

/**
 * lsof_tool — L0 只读工具，按 pid 查询进程打开的文件与 socket 摘要。
 *
 * <p><b>安全约束</b>：
 * <ul>
 *   <li>pid 必须经 PID_PATTERN 强校验（1~7 位正整数，禁止 0/负数/字符串注入）</li>
 *   <li>固定参数 lsof -p &lt;pid&gt; -F 0nPt，禁止用户拼接其他 lsof 参数</li>
 *   <li>命令不存在 / Windows 环境 → status=failed（不抛异常）</li>
 *   <li>-F 解析失败 → status=success + files=[] + rawLines（前端可显示）</li>
 * </ul>
 */
@Slf4j
@Component
public class LsofTool implements OpsTool {

    public static final String TOOL_NAME = "lsof_tool";
    private static final String DESCRIPTION = "查询进程打开的文件与 socket 摘要（fd / 文件 / 端口）";
    private static final Pattern PID_PATTERN = Pattern.compile("^[1-9]\\d{0,6}$");

    private final ToolDefinition definition;
    private final OsCommandExecutor executor;

    public LsofTool(OsCommandExecutor executor) {
        this.executor = executor;
        this.definition = new ToolDefinition();
        this.definition.setToolName(TOOL_NAME);
        this.definition.setDescription(DESCRIPTION);
        this.definition.setInputSchema("{" +
                "\"type\":\"object\"," +
                "\"properties\":{\"pid\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":9999999}}," +
                "\"required\":[\"pid\"]}");
        this.definition.setOutputSchema("{" +
                "\"type\":\"object\"," +
                "\"properties\":{" +
                "\"pid\":{\"type\":\"integer\"}," +
                "\"fdCount\":{\"type\":\"integer\"}," +
                "\"files\":{\"type\":\"array\"}," +
                "\"sockets\":{\"type\":\"array\"}," +
                "\"rawLines\":{\"type\":\"array\"}}}");
        this.definition.setRiskLevel(RiskLevel.L0);
        this.definition.setPermissionType(PermissionType.READ);
        this.definition.setToolStatus(ToolStatus.ENABLED);
        this.definition.setTimeoutMs(5000L);
        this.definition.setAuditRequired(true);
    }

    @Override
    public ToolDefinition definition() { return definition; }

    @Override
    public ToolResult execute(ToolInput input) {
        Object pidRaw = input.getParams() == null ? null : input.getParams().get("pid");
        if (pidRaw == null) {
            return ToolResult.failed(TOOL_NAME, "pid 不能为空", 0);
        }
        String pidStr = String.valueOf(pidRaw);
        if (!PID_PATTERN.matcher(pidStr).matches()) {
            return ToolResult.failed(TOOL_NAME, "pid 非法（必须是 1~7 位正整数）: " + pidStr, 0);
        }
        int pid = Integer.parseInt(pidStr);

        if (executor.isWindows()) {
            return ToolResult.failed(TOOL_NAME,
                    "lsof 需要 Linux 环境（当前为 Windows）", 0);
        }

        OsCommandExecutor.CommandResult cmd = executor.execute(
                List.of("lsof", "-p", pidStr, "-F", "0nPt"), 5000);

        if (!cmd.isSuccess() && cmd.getExitCode() != 0 && cmd.getExitCode() != 1) {
            // exit 1 也算正常（lsof 没找到任何 fd 的情况）
            return ToolResult.failed(TOOL_NAME,
                    "lsof 执行失败: exit=" + cmd.getExitCode() + ", stderr=" + cmd.getStderr(), 0);
        }

        List<String> rawLines = cmd.getStdout();
        try {
            List<Map<String, String>> files = new ArrayList<>();
            List<Map<String, String>> sockets = new ArrayList<>();
            boolean hasProcessHeader = parseOutput(rawLines, files, sockets);

            // 解析质量判定：非空输入但既无 "p<pid>" 头又无任何 fd 记录 → 视为解析失败
            boolean parseFailed = !rawLines.isEmpty() && !hasProcessHeader
                    && files.isEmpty() && sockets.isEmpty();

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("pid", pid);
            data.put("fdCount", files.size() + sockets.size());
            data.put("files", files);
            data.put("sockets", sockets);
            if (files.isEmpty() && sockets.isEmpty()) {
                data.put("rawLines", rawLines.size() > 50
                        ? rawLines.subList(0, 50) : rawLines);
            }
            if (parseFailed) {
                data.put("parseError", "输出无 -F 格式特征（无 p<...> 头、无 f<fd>/n<name> 记录）");
            }
            return ToolResult.success(TOOL_NAME, data,
                    String.format("pid=%d 打开 %d 个 fd（%d 文件 / %d socket）",
                            pid, files.size() + sockets.size(), files.size(), sockets.size()),
                    0);
        } catch (Exception e) {
            // 解析异常 → 降级返回 rawLines，不影响 success
            log.warn("lsof -F 解析异常，降级返回 rawLines: {}", e.getMessage());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("pid", pid);
            data.put("fdCount", 0);
            data.put("files", List.of());
            data.put("sockets", List.of());
            data.put("rawLines", rawLines.size() > 50 ? rawLines.subList(0, 50) : rawLines);
            data.put("parseError", e.getMessage());
            return ToolResult.success(TOOL_NAME, data,
                    String.format("pid=%d -F 解析失败，已返回原始输出", pid), 0);
        }
    }

    /**
     * 解析 lsof -F 0nPt 输出。
     * 输出格式（每行以单字符前缀开头）：
     *   p<pid>  f<fd>  t<type>  n<name>
     *
     * @return 是否检测到至少一条 "p<pid>" 进程头记录
     */
    private boolean parseOutput(List<String> lines,
                                List<Map<String, String>> files,
                                List<Map<String, String>> sockets) {
        String curFd = null, curType = null;
        boolean hasProcessHeader = false;
        for (String line : lines) {
            if (line.isEmpty()) continue;
            char prefix = line.charAt(0);
            String value = line.substring(1);
            switch (prefix) {
                case 'p' -> hasProcessHeader = true;
                case 'f' -> { curFd = value; curType = null; }
                case 't' -> curType = value;
                case 'n' -> {
                    Map<String, String> entry = new LinkedHashMap<>();
                    entry.put("fd", curFd != null ? curFd : "?");
                    entry.put("type", curType != null ? curType : "?");
                    entry.put("path", value);
                    if ("IPv4".equals(curType) || "IPv6".equals(curType) || "unix".equalsIgnoreCase(curType)) {
                        sockets.add(entry);
                    } else {
                        files.add(entry);
                    }
                    curFd = null; curType = null;
                }
                default -> { /* 其他前缀忽略 */ }
            }
        }
        return hasProcessHeader;
    }
}
