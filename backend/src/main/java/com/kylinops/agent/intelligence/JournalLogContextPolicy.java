package com.kylinops.agent.intelligence;

import com.kylinops.os.JournalLogTool;
import com.kylinops.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * journal_log_tool 上下文策略 (P3-T3). <b>敏感工具.</b>
 *
 * <p>仅暴露：serviceName / lines / truncated 标记 + 摘要（行数 + 截断位）。</p>
 * <p>不展开：entries 全文（日志条目可能含堆栈、密钥、间接注入）。</p>
 *
 * <p>这是核心安全策略 — 日志全文是最容易被注入污染的内容来源。</p>
 */
@Component
public class JournalLogContextPolicy extends AbstractLlmToolContextPolicy {

    public JournalLogContextPolicy(LlmContextSanitizer sanitizer) {
        super(sanitizer);
    }

    @Override
    public String toolName() {
        return JournalLogTool.TOOL_NAME;
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

        StringBuilder sb = new StringBuilder();
        sb.append("服务: ").append(safe(data.get("serviceName"))).append('\n');
        sb.append("查询行数: ").append(safe(data.get("lines"))).append('\n');

        Object entries = data.get("entries");
        if (entries instanceof List<?> list) {
            sb.append("返回行数: ").append(list.size()).append('\n');
        }

        Object truncated = data.get("truncated");
        if (truncated instanceof Boolean b && b) {
            sb.append("(输出已截断)");
        }

        Object note = data.get("note");
        if (note != null) {
            sb.append("\n注: ").append(note);
        }

        String text = sanitizeText(sb.toString());
        return truncate(text, maxBytes);
    }

    private String safe(Object v) {
        return v == null ? "-" : v.toString();
    }
}