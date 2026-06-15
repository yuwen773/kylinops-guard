package com.kylinops.agent.intelligence;

import com.kylinops.os.DiskUsageTool;
import com.kylinops.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * disk_usage_tool 上下文策略 (P3-T3).
 *
 * <p>暴露：filesystem / size / used / available / usedPercent / mount。</p>
 */
@Component
public class DiskUsageContextPolicy extends AbstractLlmToolContextPolicy {

    public DiskUsageContextPolicy(LlmContextSanitizer sanitizer) {
        super(sanitizer);
    }

    @Override
    public String toolName() {
        return DiskUsageTool.TOOL_NAME;
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
        Object parts = data.get("partitions");
        if (!(parts instanceof List<?> list) || list.isEmpty()) {
            return empty();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("磁盘分区:\n");
        int limit = Math.min(list.size(), 30);
        for (int i = 0; i < limit; i++) {
            if (list.get(i) instanceof Map<?, ?> p) {
                sb.append("  - ").append(safe(p.get("filesystem")))
                        .append(" size=").append(safe(p.get("size")))
                        .append(" used=").append(safe(p.get("used")))
                        .append(" avail=").append(safe(p.get("available")))
                        .append(" use=").append(safe(p.get("usedPercent"))).append('%')
                        .append(" mount=").append(safe(p.get("mount")))
                        .append('\n');
            }
        }
        if (data.containsKey("dfNote")) {
            sb.append("注: ").append(data.get("dfNote"));
        }

        String text = sanitizeText(sb.toString());
        return truncate(text, maxBytes);
    }

    private String safe(Object v) {
        return v == null ? "-" : v.toString();
    }
}