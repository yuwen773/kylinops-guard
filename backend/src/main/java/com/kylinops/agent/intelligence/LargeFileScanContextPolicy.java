package com.kylinops.agent.intelligence;

import com.kylinops.os.LargeFileScanTool;
import com.kylinops.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * large_file_scan_tool 上下文策略 (P3-T3). <b>敏感工具.</b>
 *
 * <p>仅暴露：path + sizeMB（白名单字段）。</p>
 * <p>不展开：file content / file preview / 子目录树（防止间接注入）。</p>
 */
@Component
public class LargeFileScanContextPolicy extends AbstractLlmToolContextPolicy {

    public LargeFileScanContextPolicy(LlmContextSanitizer sanitizer) {
        super(sanitizer);
    }

    @Override
    public String toolName() {
        return LargeFileScanTool.TOOL_NAME;
    }

    @Override
    public boolean isSensitive() {
        return true;
    }

    @Override
    @SuppressWarnings("unchecked")
    public String sanitize(ToolResult result, int maxBytes) {
        if (isEmpty(result)) {
            return empty();
        }

        Map<String, Object> data = (Map<String, Object>) result.getData();
        Object files = data.get("files");
        Object paths = data.get("scanPaths");

        StringBuilder sb = new StringBuilder();
        sb.append("扫描目录: ").append(safe(paths)).append('\n');

        if (files instanceof List<?> list && !list.isEmpty()) {
            sb.append("Top 大文件 (path / sizeMB):\n");
            int limit = Math.min(list.size(), 50);
            for (int i = 0; i < limit; i++) {
                if (list.get(i) instanceof Map<?, ?> f) {
                    sb.append("  - ").append(safe(f.get("path")))
                            .append(" sizeMB=").append(safe(f.get("sizeMB")))
                            .append('\n');
                }
            }
        } else {
            sb.append("未发现大文件");
        }

        String text = sanitizeText(sb.toString());
        return truncate(text, maxBytes);
    }

    private String safe(Object v) {
        if (v == null) return "-";
        if (v instanceof List<?> list) {
            return String.join(",", list.stream().map(String::valueOf).toList());
        }
        return v.toString();
    }
}