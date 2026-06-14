package com.kylinops.agent.intelligence;

import com.kylinops.os.NetworkPortTool;
import com.kylinops.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * network_port_tool 上下文策略 (P3-T3).
 *
 * <p>暴露：proto / localAddr / port / state / pid / command（短进程名）。</p>
 */
@Component
public class NetworkPortContextPolicy extends AbstractLlmToolContextPolicy {

    public NetworkPortContextPolicy(LlmContextSanitizer sanitizer) {
        super(sanitizer);
    }

    @Override
    public String toolName() {
        return NetworkPortTool.TOOL_NAME;
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
        Object listeners = data.get("listeners");

        StringBuilder sb = new StringBuilder();
        sb.append("监听端口:\n");

        if (listeners instanceof List<?> list && !list.isEmpty()) {
            int limit = Math.min(list.size(), 50);
            for (int i = 0; i < limit; i++) {
                if (list.get(i) instanceof Map<?, ?> l) {
                    sb.append("  - ").append(safe(l.get("proto")))
                            .append(" ").append(safe(l.get("localAddr")))
                            .append(":").append(safe(l.get("port")))
                            .append(" pid=").append(safe(l.get("pid")))
                            .append(" cmd=").append(safe(l.get("command")))
                            .append('\n');
                }
            }
        } else {
            sb.append("  (无监听端口)");
        }

        String text = sanitizeText(sb.toString());
        return truncate(text, maxBytes);
    }

    private String safe(Object v) {
        return v == null ? "-" : v.toString();
    }
}