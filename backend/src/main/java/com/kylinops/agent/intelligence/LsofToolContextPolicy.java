package com.kylinops.agent.intelligence;

import com.kylinops.os.LsofTool;
import com.kylinops.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * lsof_tool 上下文策略 (P3-T3, Fix-02).
 *
 * <p>暴露：pid + fd 总数 + 前 N 条文件/ socket（fd + type + 截断到 64 字符的 path）。</p>
 * <p>不暴露：完整原始 lsof 输出行（可能含间接注入）。parseError 时仅返回错误摘要。</p>
 */
@Component
public class LsofToolContextPolicy extends AbstractLlmToolContextPolicy {

    /** 单侧（文件/socket）最大暴露条目数 */
    private static final int MAX_ENTRIES_PER_KIND = 10;
    /** 路径截断长度（防止某些 fd path 极长） */
    private static final int PATH_TRUNCATE = 64;

    public LsofToolContextPolicy(LlmContextSanitizer sanitizer) {
        super(sanitizer);
    }

    @Override
    public String toolName() {
        return LsofTool.TOOL_NAME;
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
        sb.append("进程 pid=").append(safe(data.get("pid")))
                .append(" 打开 fd 数=").append(safe(data.get("fdCount"))).append('\n');

        appendKind(sb, "文件", (List<Map<String, String>>) data.get("files"));
        appendKind(sb, "Socket", (List<Map<String, String>>) data.get("sockets"));

        Object parseError = data.get("parseError");
        if (parseError != null) {
            sb.append("解析失败: ").append(parseError).append('\n');
        }

        String text = sanitizeText(sb.toString());
        return truncate(text, maxBytes);
    }

    private void appendKind(StringBuilder sb, String label, List<Map<String, String>> entries) {
        if (entries == null || entries.isEmpty()) {
            sb.append(label).append(": (无)\n");
            return;
        }
        sb.append(label).append(" (").append(entries.size()).append("):\n");
        int limit = Math.min(entries.size(), MAX_ENTRIES_PER_KIND);
        for (int i = 0; i < limit; i++) {
            Map<String, String> e = entries.get(i);
            sb.append("  fd=").append(safe(e.get("fd")))
                    .append(" type=").append(safe(e.get("type")))
                    .append(" path=").append(truncatePath(safe(e.get("path"))))
                    .append('\n');
        }
        if (entries.size() > MAX_ENTRIES_PER_KIND) {
            sb.append("  ...(其余 ").append(entries.size() - MAX_ENTRIES_PER_KIND).append(" 条已截断)\n");
        }
    }

    private String safe(Object v) {
        return v == null ? "-" : v.toString();
    }

    private String truncatePath(String p) {
        if (p == null || p.length() <= PATH_TRUNCATE) return p == null ? "-" : p;
        return p.substring(0, PATH_TRUNCATE) + "...";
    }
}
