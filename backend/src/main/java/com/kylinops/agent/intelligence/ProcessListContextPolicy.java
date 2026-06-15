package com.kylinops.agent.intelligence;

import com.kylinops.os.ProcessListTool;
import com.kylinops.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * process_list_tool 上下文策略 (P3-T3).
 *
 * <p>暴露：pid / user / cpu% / mem% / command (截断到 32 字符)。</p>
 * <p>命令截断原因：完整命令行可能含 --token= / --password= 等敏感参数。</p>
 */
@Component
public class ProcessListContextPolicy extends AbstractLlmToolContextPolicy {

    /** 命令截断长度 */
    private static final int CMD_TRUNCATE = 32;

    public ProcessListContextPolicy(LlmContextSanitizer sanitizer) {
        super(sanitizer);
    }

    @Override
    public String toolName() {
        return ProcessListTool.TOOL_NAME;
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
        Object procs = data.get("processes");
        Object total = data.get("total");

        StringBuilder sb = new StringBuilder();
        sb.append("进程总数: ").append(safe(total)).append('\n');

        if (procs instanceof List<?> list && !list.isEmpty()) {
            int limit = Math.min(list.size(), 30);
            for (int i = 0; i < limit; i++) {
                if (list.get(i) instanceof Map<?, ?> p) {
                    sb.append("  - pid=").append(safe(p.get("pid")))
                            .append(" user=").append(safe(p.get("user")))
                            .append(" cpu=").append(safe(p.get("cpuPct"))).append('%')
                            .append(" mem=").append(safe(p.get("memPct"))).append('%')
                            .append(" cmd=").append(truncateCmd(p.get("command")))
                            .append('\n');
                }
            }
        }

        String text = sanitizeText(sb.toString());
        return truncate(text, maxBytes);
    }

    private String safe(Object v) {
        return v == null ? "-" : v.toString();
    }

    private String truncateCmd(Object cmd) {
        if (cmd == null) return "-";
        String s = cmd.toString();
        if (s.length() <= CMD_TRUNCATE) return s;
        return s.substring(0, CMD_TRUNCATE) + "...";
    }
}