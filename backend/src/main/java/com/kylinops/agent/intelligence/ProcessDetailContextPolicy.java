package com.kylinops.agent.intelligence;

import com.kylinops.os.ProcessDetailTool;
import com.kylinops.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * process_detail_tool 上下文策略 (P3-T3).
 *
 * <p>暴露：pid / ppid / user / comm (短名) / state / memMB。</p>
 * <p>不暴露：cmdline 全文（仅取 comm 字段；不读 /proc/{pid}/cmdline 之类）。</p>
 */
@Component
public class ProcessDetailContextPolicy extends AbstractLlmToolContextPolicy {

    public ProcessDetailContextPolicy(LlmContextSanitizer sanitizer) {
        super(sanitizer);
    }

    @Override
    public String toolName() {
        return ProcessDetailTool.TOOL_NAME;
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
        sb.append("pid: ").append(safe(data.get("pid"))).append('\n');
        sb.append("ppid: ").append(safe(data.get("ppid"))).append('\n');
        sb.append("user: ").append(safe(data.get("user"))).append('\n');
        sb.append("comm: ").append(safe(data.get("command"))).append('\n');
        sb.append("state: ").append(safe(data.get("state"))).append('\n');
        sb.append("memMB: ").append(safe(data.get("memMB")));

        String text = sanitizeText(sb.toString());
        return truncate(text, maxBytes);
    }

    private String safe(Object v) {
        return v == null ? "-" : v.toString();
    }
}