package com.kylinops.agent.intelligence;

import com.kylinops.os.CpuStatusTool;
import com.kylinops.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * cpu_status_tool 上下文策略 (P3-T3).
 *
 * <p>暴露：usagePercent / loadAvg(1/5/15) / Top 进程（pid+user+cpu%，命令截断到 32 字符）。</p>
 * <p>不暴露：完整命令行（可能含 token / 路径）。</p>
 */
@Component
public class CpuStatusContextPolicy extends AbstractLlmToolContextPolicy {

    /** Top 进程命令截断长度 */
    private static final int CMD_TRUNCATE = 32;

    public CpuStatusContextPolicy(LlmContextSanitizer sanitizer) {
        super(sanitizer);
    }

    @Override
    public String toolName() {
        return CpuStatusTool.TOOL_NAME;
    }

    @Override
    public boolean isSensitive() {
        return false;
    }

    @Override
    @SuppressWarnings("unchecked")
    public String sanitize(ToolResult result, int maxBytes) {
        if (isEmpty(result)) {
            return empty();
        }

        Map<String, Object> data = (Map<String, Object>) result.getData();
        StringBuilder sb = new StringBuilder();
        sb.append("CPU 使用率: ").append(formatPct(data.get("usagePercent"))).append('\n');
        sb.append("负载 (1/5/15): ")
                .append(formatDouble(data.get("loadAvg1"))).append(" / ")
                .append(formatDouble(data.get("loadAvg5"))).append(" / ")
                .append(formatDouble(data.get("loadAvg15"))).append('\n');

        Object top = data.get("topProcesses");
        if (top instanceof List<?> list && !list.isEmpty()) {
            sb.append("Top CPU 进程:\n");
            int limit = Math.min(list.size(), 10);
            for (int i = 0; i < limit; i++) {
                if (list.get(i) instanceof Map<?, ?> p) {
                    sb.append("  - pid=").append(p.get("pid"))
                            .append(" user=").append(p.get("user"))
                            .append(" cpu=").append(formatPct(p.get("cpuPct")))
                            .append(" cmd=").append(truncateCmd(p.get("command")))
                            .append('\n');
                }
            }
        }

        String text = sanitizeText(sb.toString());
        return truncate(text, maxBytes);
    }

    private String formatPct(Object v) {
        if (v == null) return "-";
        if (v instanceof Number n) return String.format("%.1f%%", n.doubleValue());
        return v.toString();
    }

    private String formatDouble(Object v) {
        if (v == null) return "-";
        if (v instanceof Number n) return String.format("%.2f", n.doubleValue());
        return v.toString();
    }

    private String truncateCmd(Object cmd) {
        if (cmd == null) return "-";
        String s = cmd.toString();
        if (s.length() <= CMD_TRUNCATE) return s;
        return s.substring(0, CMD_TRUNCATE) + "...";
    }
}