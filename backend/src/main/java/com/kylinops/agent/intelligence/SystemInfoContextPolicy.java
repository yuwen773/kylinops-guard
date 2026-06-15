package com.kylinops.agent.intelligence;

import com.kylinops.os.SystemInfoTool;
import com.kylinops.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * system_info_tool 上下文策略 (P3-T3).
 *
 * <p>仅暴露：hostname / osVersion / kernel / arch / uptimeSeconds。</p>
 * <p>不暴露：原始命令、env 值、shell 历史。</p>
 */
@Component
public class SystemInfoContextPolicy extends AbstractLlmToolContextPolicy {

    public SystemInfoContextPolicy(LlmContextSanitizer sanitizer) {
        super(sanitizer);
    }

    @Override
    public String toolName() {
        return SystemInfoTool.TOOL_NAME;
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
        sb.append("主机名: ").append(safe(data.get("hostname"))).append('\n');
        sb.append("操作系统: ").append(safe(data.get("osVersion"))).append('\n');
        sb.append("内核: ").append(safe(data.get("kernel"))).append('\n');
        sb.append("架构: ").append(safe(data.get("arch"))).append('\n');
        sb.append("运行时间(秒): ").append(safe(data.get("uptimeSeconds")));

        String text = sanitizeText(sb.toString());
        return truncate(text, maxBytes);
    }

    private String safe(Object v) {
        return v == null ? "-" : v.toString();
    }
}