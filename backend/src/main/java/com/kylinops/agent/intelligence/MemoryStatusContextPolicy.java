package com.kylinops.agent.intelligence;

import com.kylinops.os.MemoryStatusTool;
import com.kylinops.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * memory_status_tool 上下文策略 (P3-T3).
 *
 * <p>暴露：totalMB / usedMB / freeMB / swapTotalMB / swapUsedMB / usedPercent。</p>
 */
@Component
public class MemoryStatusContextPolicy extends AbstractLlmToolContextPolicy {

    public MemoryStatusContextPolicy(LlmContextSanitizer sanitizer) {
        super(sanitizer);
    }

    @Override
    public String toolName() {
        return MemoryStatusTool.TOOL_NAME;
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
        sb.append("物理内存: ").append(safe(data.get("usedMB"))).append("MB / ")
                .append(safe(data.get("totalMB"))).append("MB (")
                .append(safe(data.get("usedPercent"))).append("%)\n");
        sb.append("空闲: ").append(safe(data.get("freeMB"))).append("MB\n");

        long swapTotal = asLong(data.get("swapTotalMB"));
        if (swapTotal > 0) {
            sb.append("SWAP: ").append(safe(data.get("swapUsedMB"))).append("MB / ")
                    .append(swapTotal).append("MB");
        }

        String text = sanitizeText(sb.toString());
        return truncate(text, maxBytes);
    }

    private String safe(Object v) {
        return v == null ? "-" : v.toString();
    }

    private long asLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        return 0L;
    }
}