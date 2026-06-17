package com.kylinops.notification;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 通知 payload 脱敏器。
 *
 * <p><b>规则</b>:</p>
 * <ul>
 *   <li>key 包含 secret/token/password/api_key/access_key/private_key → 替换为 "***"</li>
 *   <li>value 包含 /etc/passwd / /etc/shadow / ~/.ssh/ / /var/lib/mysql/ → 替换为 "/etc/[REDACTED]" 等</li>
 *   <li>userInput 原文(风险命令) → 仅保留前 50 字符 + "..."</li>
 * </ul>
 *
 * <p><b>设计要点</b>:</p>
 * <ul>
 *   <li>同一份 maskedPayload 用于 NotificationRecord.requestPayload 与 HTTP 请求体</li>
 *   <li>不修改原 NotificationEvent 实例(返回新的 JSON 字符串)</li>
 *   <li>不抛异常:任何解析失败 → 返回原始 JSON 字符串</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationPayloadSanitizer {

    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(?i).*(secret|token|password|api_?key|access_?key|private_?key).*");
    private static final Pattern SENSITIVE_PATH = Pattern.compile(
            "(/etc/(passwd|shadow)|/root/\\.ssh/|/home/[^/]+/\\.ssh/|/var/lib/mysql/)");
    private static final int USER_INPUT_TRUNCATE_LEN = 50;

    private final ObjectMapper objectMapper;

    /**
     * 脱敏 payload。返回 JSON 字符串。
     *
     * <p>行为:</p>
     * <ol>
     *   <li>把 NotificationEvent 序列化为 Map</li>
     *   <li>递归遍历 Map/List 节点,匹配敏感 key / path</li>
     *   <li>特殊处理 userInput:截断到 50 字符</li>
     *   <li>序列化为 JSON 字符串</li>
     * </ol>
     */
    public String mask(NotificationEvent event) {
        if (event == null) return "{}";
        try {
            Map<String, Object> raw = objectMapper.convertValue(event,
                    new TypeReference<Map<String, Object>>() {});
            if (raw == null) raw = new LinkedHashMap<>();
            Map<String, Object> sanitized = sanitizeMap(raw);
            return objectMapper.writeValueAsString(sanitized);
        } catch (Exception e) {
            log.warn("payload 脱敏失败,返回原始事件基本信息: {}", e.getMessage());
            return fallbackJson(event);
        }
    }

    Map<String, Object> sanitizeMap(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : source.entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            // 1. 敏感 key 替换
            if (key != null && SENSITIVE_KEY.matcher(key).matches()) {
                result.put(key, "***");
                continue;
            }
            // 2. 特殊:userInput 截断
            if ("userInput".equals(key) && value instanceof String s) {
                result.put(key, truncate(s, USER_INPUT_TRUNCATE_LEN));
                continue;
            }
            // 3. 递归处理嵌套结构
            if (value instanceof Map<?, ?> nested) {
                Map<String, Object> sanitizedNested = sanitizeMap(toStringMap(nested));
                result.put(key, sanitizedNested);
            } else if (value instanceof List<?> list) {
                result.put(key, sanitizeList(list));
            } else if (value instanceof String s && SENSITIVE_PATH.matcher(s).find()) {
                result.put(key, redactPath(s));
            } else {
                result.put(key, value);
            }
        }
        return result;
    }

    private List<Object> sanitizeList(List<?> list) {
        java.util.List<Object> out = new java.util.ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                out.add(sanitizeMap(toStringMap(m)));
            } else if (item instanceof List<?> l) {
                out.add(sanitizeList(l));
            } else if (item instanceof String s && SENSITIVE_PATH.matcher(s).find()) {
                out.add(redactPath(s));
            } else {
                out.add(item);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toStringMap(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }

    private static String redactPath(String value) {
        return SENSITIVE_PATH.matcher(value).replaceAll("/[REDACTED]");
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    private String fallbackJson(NotificationEvent event) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "eventType", event.getEventType() != null ? event.getEventType().name() : "UNKNOWN",
                    "severity", event.getSeverity() != null ? event.getSeverity().name() : "INFO",
                    "title", event.getTitle() != null ? event.getTitle() : "",
                    "auditId", event.getAuditId() != null ? event.getAuditId() : ""
            ));
        } catch (Exception e) {
            return "{}";
        }
    }
}
