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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网络端口监听工具 — network_port_tool (L0 / NETWORK)
 * <p>
 * 优先执行 {@code ss -tulnp}，备选 {@code netstat -tulnp}，
 * 返回当前系统所有监听中的 TCP/UDP 端口列表。
 * </p>
 */
@Slf4j
@Component
public class NetworkPortTool implements OpsTool {

    public static final String TOOL_NAME = "network_port_tool";
    private static final String DESCRIPTION = "查看当前系统监听中的网络端口列表（TCP/UDP）";

    private final ToolDefinition definition;
    private final OsCommandExecutor executor;

    /** 解析 ss -tulnp 输出中的进程信息: users:(("进程名",pid=1234,fd=N)) */
    private static final Pattern SS_PROCESS_PATTERN = Pattern.compile("\"([^\"]+)\".*pid=(\\d+)");

    public NetworkPortTool(OsCommandExecutor executor) {
        this.executor = executor;
        this.definition = new ToolDefinition();
        this.definition.setToolName(TOOL_NAME);
        this.definition.setDescription(DESCRIPTION);
        this.definition.setInputSchema("{\"type\":\"object\",\"properties\":{}}");
        this.definition.setOutputSchema("{" +
                "\"type\":\"object\"," +
                "\"properties\":{" +
                "\"listeners\":{\"type\":\"array\"}" +
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
        log.debug("NetworkPortTool 执行: requestId={}", input.getRequestId());

        if (executor.isWindows()) {
            return ToolResult.failed(TOOL_NAME,
                    "网络端口查询需要 Linux 环境（当前为 Windows 环境）", 0);
        }

        Map<String, Object> data = new HashMap<>();

        // 优先使用 ss，回退 netstat
        if (executor.isCommandAvailable("ss")) {
            parseSsOutput(data);
        } else if (executor.isCommandAvailable("netstat")) {
            parseNetstatOutput(data);
        } else {
            // 降级：尝试解析 /proc/net/tcp 和 /proc/net/udp
            parseProcNet(data);
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> listeners = (List<Map<String, Object>>) data.getOrDefault("listeners", List.of());
        String summary = String.format("共 %d 个监听端口", listeners.size());
        if (data.containsKey("note")) {
            summary += "（" + data.get("note") + "）";
        }

        return ToolResult.success(TOOL_NAME, data, summary, 0);
    }

    /**
     * 使用 ss -tulnp 解析监听端口。
     * <p>
     * 输出格式:
     * <pre>
     * Netid  State      Recv-Q Send-Q  Local Address:Port   Peer Address:Port   Process
     * tcp    LISTEN     0      128     0.0.0.0:22           0.0.0.0:*           users:(("sshd",pid=1234,fd=3))
     * tcp    LISTEN     0      128     [::]:80               [::]:*              users:(("nginx",pid=5678,fd=6))
     * udp    LISTEN     0      0       0.0.0.0:68           0.0.0.0:*           users:(("dhclient",pid=9012,fd=7))
     * </pre>
     * </p>
     */
    private void parseSsOutput(Map<String, Object> data) {
        List<Map<String, Object>> listeners = new ArrayList<>();

        OsCommandExecutor.CommandResult result = executor.execute(
                List.of("ss", "-tulnp"), 5000);

        if (!result.isSuccess() || result.getStdout().size() <= 1) {
            data.put("listeners", listeners);
            data.put("note", "ss 执行失败或无输出");
            return;
        }

        // 跳过第一行标题
        for (int i = 1; i < result.getStdout().size(); i++) {
            String line = result.getStdout().get(i).trim();
            if (line.isBlank()) continue;

            Map<String, Object> entry = parseSsLine(line);
            if (entry != null) {
                listeners.add(entry);
            }
        }

        data.put("listeners", listeners);
    }

    /**
     * 解析 ss -tulnp 的一行输出。
     */
    private Map<String, Object> parseSsLine(String line) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length < 5) return null;

        Map<String, Object> entry = new HashMap<>();

        // 协议
        String proto = parts[0].toUpperCase();
        entry.put("proto", proto);

        // 本地地址和端口
        String localAddr = parts[4];
        String ip = localAddr;
        int port = 0;

        // 解析 [::]:port 或 0.0.0.0:port 或 *:port
        int lastColon = localAddr.lastIndexOf(':');
        if (lastColon > 0) {
            String portStr = localAddr.substring(lastColon + 1);
            try {
                port = Integer.parseInt(portStr);
            } catch (NumberFormatException e) {
                // ignore
            }
            ip = localAddr.substring(0, lastColon);
            // 去掉 [] 包裹的 IPv6
            if (ip.startsWith("[") && ip.endsWith("]")) {
                ip = ip.substring(1, ip.length() - 1);
            }
        }

        entry.put("localAddr", ip);
        entry.put("port", port);

        // 进程信息（ss 第 6 列）
        if (parts.length >= 6) {
            String procInfo = parts[5];
            Matcher matcher = SS_PROCESS_PATTERN.matcher(procInfo);
            if (matcher.find()) {
                entry.put("command", matcher.group(1));
                try {
                    entry.put("pid", Integer.parseInt(matcher.group(2)));
                } catch (NumberFormatException e) {
                    entry.put("pid", -1);
                }
            } else {
                entry.put("command", "-");
                entry.put("pid", -1);
            }
        } else {
            entry.put("command", "-");
            entry.put("pid", -1);
        }

        return entry;
    }

    /**
     * 备选方案：使用 netstat -tulnp 解析监听端口。
     * <p>
     * 输出格式:
     * <pre>
     * Proto Recv-Q Send-Q Local Address           Foreign Address         State       PID/Program name
     * tcp        0      0 0.0.0.0:22              0.0.0.0:*               LISTEN      1234/sshd
     * tcp6       0      0 :::80                   :::*                    LISTEN      5678/nginx
     * udp        0      0 0.0.0.0:68              0.0.0.0:*                           9012/dhclient
     * </pre>
     * </p>
     */
    private void parseNetstatOutput(Map<String, Object> data) {
        List<Map<String, Object>> listeners = new ArrayList<>();

        OsCommandExecutor.CommandResult result = executor.execute(
                List.of("netstat", "-tulnp"), 5000);

        if (!result.isSuccess() || result.getStdout().size() <= 1) {
            data.put("listeners", listeners);
            data.put("note", "netstat 执行失败或无输出");
            return;
        }

        // 跳过前两行标题（netstat 通常有两行标题）
        int startIdx = 0;
        for (int i = 0; i < result.getStdout().size(); i++) {
            if (result.getStdout().get(i).startsWith("Proto")) {
                startIdx = i + 1;
                break;
            }
        }

        for (int i = startIdx; i < result.getStdout().size(); i++) {
            String line = result.getStdout().get(i).trim();
            if (line.isBlank()) continue;

            Map<String, Object> entry = parseNetstatLine(line);
            if (entry != null) {
                listeners.add(entry);
            }
        }

        data.put("listeners", listeners);
    }

    /**
     * 解析 netstat -tulnp 的一行输出。
     */
    private Map<String, Object> parseNetstatLine(String line) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length < 4) return null;

        Map<String, Object> entry = new HashMap<>();

        // 协议
        String proto = parts[0].toUpperCase().replace("TCP6", "TCP").replace("UDP6", "UDP");
        entry.put("proto", proto.contains("TCP") ? "TCP" : "UDP");

        // 本地地址:端口 (格式: 0.0.0.0:22 或 :::80)
        String localAddr = parts[3];
        String ip = localAddr;
        int port = 0;

        int lastColon = localAddr.lastIndexOf(':');
        if (lastColon > 0) {
            String portStr = localAddr.substring(lastColon + 1);
            try {
                port = Integer.parseInt(portStr);
            } catch (NumberFormatException e) {
                // ignore
            }
            ip = localAddr.substring(0, lastColon);
            if (ip.startsWith("[") && ip.endsWith("]")) {
                ip = ip.substring(1, ip.length() - 1);
            }
        }

        entry.put("localAddr", ip);
        entry.put("port", port);

        // PID/Program name (最后一列)
        if (parts.length >= 7) {
            String lastField = parts[parts.length - 1];
            String[] pidCommand = lastField.split("/", 2);
            if (pidCommand.length == 2) {
                try {
                    entry.put("pid", Integer.parseInt(pidCommand[0]));
                } catch (NumberFormatException e) {
                    entry.put("pid", -1);
                }
                entry.put("command", pidCommand[1]);
            } else {
                entry.put("pid", -1);
                entry.put("command", lastField);
            }
        } else {
            entry.put("pid", -1);
            entry.put("command", "-");
        }

        return entry;
    }

    /**
     * 降级方案：解析 /proc/net/tcp 和 /proc/net/udp。
     * <p>
     * 仅包含基本信息（不含 PID/进程名）。
     * </p>
     */
    private void parseProcNet(Map<String, Object> data) {
        List<Map<String, Object>> listeners = new ArrayList<>();

        // 解析 TCP
        listeners.addAll(parseProcNetFile("/proc/net/tcp", "TCP"));
        // 解析 TCP6
        listeners.addAll(parseProcNetFile("/proc/net/tcp6", "TCP"));
        // 解析 UDP
        listeners.addAll(parseProcNetFile("/proc/net/udp", "UDP"));
        // 解析 UDP6
        listeners.addAll(parseProcNetFile("/proc/net/udp6", "UDP"));

        data.put("listeners", listeners);
        data.put("note", "ss/netstat 均不可用，数据来自 /proc/net（不含 PID 和进程名）");
    }

    /**
     * 解析 /proc/net/{tcp,udp,tcp6,udp6} 文件。
     * <p>
     * 格式: sl  local_address rem_address st tx_queue rx_queue tr tm->when retrnsmt ...
     * local_address 格式: 0100007F:0035 (十六进制 IP:十六进制端口)
     * st = 0A 表示 LISTEN
     * </p>
     */
    private List<Map<String, Object>> parseProcNetFile(String filePath, String proto) {
        List<Map<String, Object>> entries = new ArrayList<>();

        OsCommandExecutor.FileReadResult result = executor.readFile(filePath);
        if (!result.isSuccess() || result.getLines().size() <= 1) {
            return entries;
        }

        // 跳过标题行
        for (int i = 1; i < result.getLines().size(); i++) {
            String line = result.getLines().get(i).trim();
            if (line.isBlank()) continue;

            String[] parts = line.trim().split("\\s+");
            if (parts.length < 4) continue;

            // 状态: 第 4 列 (0-indexed: 3)
            String state = parts[3];
            // 0A = LISTEN
            if (!"0A".equals(state)) continue;

            // 本地地址: 第 2 列 (0-indexed: 1)
            String localHex = parts[1];
            String[] hexParts = localHex.split(":");
            if (hexParts.length < 2) continue;

            try {
                String hexIp = hexParts[0];
                int port = Integer.parseInt(hexParts[1], 16);

                // 转换十六进制 IP 为点分十进制
                String ip = hexToIp(hexIp);

                Map<String, Object> entry = new HashMap<>();
                entry.put("proto", proto);
                entry.put("localAddr", ip);
                entry.put("port", port);
                entry.put("pid", -1);
                entry.put("command", "-");
                entries.add(entry);
            } catch (NumberFormatException e) {
                // ignore
            }
        }

        return entries;
    }

    /**
     * 将十六进制 IP 转换为标准格式。
     * <p>
     * IPv4: 0100007F → 127.0.0.1（小端序）
     * IPv6: 00000000000000000000000001000000 → ::1
     * </p>
     */
    private String hexToIp(String hex) {
        if (hex == null || hex.isBlank()) return "0.0.0.0";

        // IPv4 (8 个十六进制字符)
        if (hex.length() == 8) {
            try {
                int raw = (int) Long.parseLong(hex, 16);
                return (raw & 0xFF) + "." +
                        ((raw >> 8) & 0xFF) + "." +
                        ((raw >> 16) & 0xFF) + "." +
                        ((raw >> 24) & 0xFF);
            } catch (NumberFormatException e) {
                return "0.0.0.0";
            }
        }

        // IPv6 (32 个十六进制字符) — 简化处理
        return "::" + hex;
    }
}
