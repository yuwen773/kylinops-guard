package com.kylinops.agent.intelligence;

import com.kylinops.os.ServiceStatusTool;
import com.kylinops.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * service_status_tool 上下文策略 (P3-T3).
 *
 * <p>暴露：serviceName / activeState / enabledState / isActive / isEnabled。</p>
 */
@Component
public class ServiceStatusContextPolicy extends AbstractLlmToolContextPolicy {

    public ServiceStatusContextPolicy(LlmContextSanitizer sanitizer) {
        super(sanitizer);
    }

    @Override
    public String toolName() {
        return ServiceStatusTool.TOOL_NAME;
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
        sb.append("服务: ").append(safe(data.get("serviceName"))).append('\n');
        sb.append("运行状态: ").append(safe(data.get("activeState")))
                .append(" (isActive=").append(safe(data.get("isActive"))).append(')').append('\n');
        sb.append("启用状态: ").append(safe(data.get("enabledState")))
                .append(" (isEnabled=").append(safe(data.get("isEnabled"))).append(')');

        String text = sanitizeText(sb.toString());
        return truncate(text, maxBytes);
    }

    private String safe(Object v) {
        return v == null ? "-" : v.toString();
    }
}