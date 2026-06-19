package com.kylinops.notification.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kylinops.notification.ChannelType;
import com.kylinops.notification.NotificationConfig;
import com.kylinops.notification.NotificationEvent;
import com.kylinops.notification.NotificationEventType;
import com.kylinops.notification.NotificationPublicLinkProperties;
import com.kylinops.notification.NotificationSendResult;
import com.kylinops.notification.NotificationSeverity;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FeishuChannel 测试 — Task 8 精简卡片 + 审计跳转链接。
 *
 * <p>覆盖维度:</p>
 * <ul>
 *   <li>签名算法(timestamp+sign)不变</li>
 *   <li>HTTP 传输(okhttp + JSON body)不变</li>
 *   <li>header 模板与短标题:CRITICAL→red, WARNING→orange, INFO→blue, null→grey, TEST→固定 blue</li>
 *   <li>卡片正文:summary + 1 key object(matchedRuleId/serviceName/diskPath 等)</li>
 *   <li>审计链接:publicBaseUrl 有效→带按钮,缺/无效→无按钮</li>
 *   <li>TEST 卡片:无 auditId、无审计行、无 action 按钮</li>
 *   <li>不泄露 detail(命令原文)与 event.title(旧长标题)</li>
 * </ul>
 */
class FeishuChannelTest {

    private MockWebServer server;
    private NotificationPublicLinkProperties publicLink;
    private FeishuChannel channel;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        publicLink = new NotificationPublicLinkProperties("http://localhost:8080");
        channel = new FeishuChannel(new ObjectMapper(), publicLink);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private NotificationConfig.ChannelConfig cfg() {
        return NotificationConfig.ChannelConfig.builder()
                .id("feishu-1").type(ChannelType.FEISHU).enabled(true)
                .url(server.url("/hook").toString())
                .secret("test-secret-key")
                .timeoutMs(2000).build();
    }

    /** 默认事件构造器 — title 是旧版长标题,detail 是命令原文标记,均不应进卡片。 */
    private NotificationEvent eventOf(NotificationEventType type, NotificationSeverity sev) {
        return NotificationEvent.builder()
                .eventId("e-1")
                .eventType(type)
                .severity(sev)
                .title("OLD_LONG_TITLE_SHOULD_NOT_APPEAR")
                .summary("test summary text")
                .matchedRuleId("rule-1")
                .auditId("audit-123")
                .occurredAt(LocalDateTime.of(2026, 6, 19, 18, 0))
                .detail("SENSITIVE_DETAIL_TOKEN_MUST_NOT_LEAK")
                .build();
    }

    private NotificationEvent l4Event() {
        return eventOf(NotificationEventType.L4_BLOCK, NotificationSeverity.CRITICAL);
    }

    private NotificationEvent testEvent() {
        // TEST:auditId 必为 null(由 NotificationTestService 在 emit 时保证)
        return NotificationEvent.builder()
                .eventId("e-1")
                .eventType(NotificationEventType.TEST)
                .severity(NotificationSeverity.INFO)
                .title("OLD_LONG_TITLE_SHOULD_NOT_APPEAR")
                .summary("test summary text")
                .matchedRuleId("rule-1")
                .occurredAt(LocalDateTime.of(2026, 6, 19, 18, 0))
                .build();
    }

    private JsonNode parseCard(String body) throws Exception {
        ObjectMapper m = new ObjectMapper();
        return m.readTree(body).get("card");
    }

    // ============================================================
    // header 模板 + 短标题 — 严重等级 × 事件类型 矩阵
    // ============================================================

    @Test
    void send_l4BlockCard_usesRedHeaderAndShortTitle() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));
        assertTrue(channel.send(l4Event(), "{}", cfg()).isSuccess());

        String body = server.takeRequest(2, TimeUnit.SECONDS).getBody().readUtf8();
        JsonNode card = parseCard(body);
        assertEquals("red", card.get("header").get("template").asText());
        assertEquals("高风险操作已阻断", card.get("header").get("title").get("content").asText());
        assertFalse(body.contains("OLD_LONG_TITLE_SHOULD_NOT_APPEAR"),
                "卡片不应包含旧版长标题 event.title: " + body);
    }

    @Test
    void send_promptInjectionCard_usesRedHeader() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));
        NotificationEvent e = NotificationEvent.builder()
                .eventId("e-1")
                .eventType(NotificationEventType.PROMPT_INJECTION_BLOCK)
                .severity(NotificationSeverity.CRITICAL)
                .title("OLD_LONG_TITLE_SHOULD_NOT_APPEAR")
                .summary("test summary text")
                .matchedRuleId("rule-1")
                .promptInjectionPattern("ignore-all-rules")
                .auditId("audit-123")
                .occurredAt(LocalDateTime.of(2026, 6, 19, 18, 0))
                .build();
        assertTrue(channel.send(e, "{}", cfg()).isSuccess());

        String body = server.takeRequest(2, TimeUnit.SECONDS).getBody().readUtf8();
        JsonNode card = parseCard(body);
        assertEquals("red", card.get("header").get("template").asText());
        assertEquals("Prompt 注入拦截", card.get("header").get("title").get("content").asText());
        // 注入模式作为 key object 出现在卡片
        assertTrue(body.contains("ignore-all-rules"),
                "matchedRuleId 类的 key object 应在卡片中: " + body);
    }

    @Test
    void send_l2ConfirmCard_usesOrangeHeader() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));
        NotificationEvent e = eventOf(NotificationEventType.L2_CONFIRM_REQUIRED,
                NotificationSeverity.WARNING);
        assertTrue(channel.send(e, "{}", cfg()).isSuccess());

        String body = server.takeRequest(2, TimeUnit.SECONDS).getBody().readUtf8();
        JsonNode card = parseCard(body);
        assertEquals("orange", card.get("header").get("template").asText());
        assertEquals("待确认操作", card.get("header").get("title").get("content").asText());
    }

    @Test
    void send_serviceAbnormalCard_usesBlueHeader() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));
        NotificationEvent e = NotificationEvent.builder()
                .eventId("e-1")
                .eventType(NotificationEventType.SERVICE_ABNORMAL)
                .severity(NotificationSeverity.INFO)
                .title("OLD_LONG_TITLE_SHOULD_NOT_APPEAR")
                .summary("test summary text")
                .serviceName("nginx")
                .auditId("audit-123")
                .occurredAt(LocalDateTime.of(2026, 6, 19, 18, 0))
                .build();
        assertTrue(channel.send(e, "{}", cfg()).isSuccess());

        String body = server.takeRequest(2, TimeUnit.SECONDS).getBody().readUtf8();
        JsonNode card = parseCard(body);
        assertEquals("blue", card.get("header").get("template").asText());
        assertEquals("服务异常", card.get("header").get("title").get("content").asText());
        assertTrue(body.contains("nginx"), "serviceName 应作为 key object 出现在卡片: " + body);
    }

    @Test
    void send_diskRiskCard_usesBlueHeader() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));
        NotificationEvent e = NotificationEvent.builder()
                .eventId("e-1")
                .eventType(NotificationEventType.DISK_RISK)
                .severity(NotificationSeverity.INFO)
                .title("OLD_LONG_TITLE_SHOULD_NOT_APPEAR")
                .summary("test summary text")
                .diskPath("/var/log")
                .diskUsagePercent(86.5)
                .auditId("audit-123")
                .occurredAt(LocalDateTime.of(2026, 6, 19, 18, 0))
                .build();
        assertTrue(channel.send(e, "{}", cfg()).isSuccess());

        String body = server.takeRequest(2, TimeUnit.SECONDS).getBody().readUtf8();
        JsonNode card = parseCard(body);
        assertEquals("blue", card.get("header").get("template").asText());
        assertEquals("磁盘风险", card.get("header").get("title").get("content").asText());
        assertTrue(body.contains("/var/log"), "diskPath 应作为 key object 出现在卡片: " + body);
        assertTrue(body.contains("86.5"), "diskUsagePercent 应出现在卡片: " + body);
    }

    @Test
    void send_testCard_usesSpecialTitleAndOmitsAuditFields() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));
        assertTrue(channel.send(testEvent(), "{}", cfg()).isSuccess());

        String body = server.takeRequest(2, TimeUnit.SECONDS).getBody().readUtf8();
        JsonNode card = parseCard(body);
        // TEST 强制 blue 模板
        assertEquals("blue", card.get("header").get("template").asText());
        // TEST 固定标题
        assertEquals("通道连接测试", card.get("header").get("title").get("content").asText());
        // 不出现 auditId 与 action 按钮
        assertFalse(body.contains("audit-123"), "TEST 卡片不应含 auditId: " + body);
        assertFalse(body.contains("/audit?auditId="), "TEST 卡片不应含审计跳转链接: " + body);
        assertFalse(body.contains("查看审计详情"),
                "TEST 卡片不应含查看审计详情按钮: " + body);
    }

    @Test
    void send_nullSeverityCard_usesGreyHeaderAndFallbackTitle() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));
        NotificationEvent e = NotificationEvent.builder()
                .eventId("e-1")
                .eventType(NotificationEventType.L4_BLOCK)
                .severity(null)
                .title("OLD_LONG_TITLE_SHOULD_NOT_APPEAR")
                .summary("test summary text")
                .matchedRuleId("rule-1")
                .auditId("audit-123")
                .occurredAt(LocalDateTime.of(2026, 6, 19, 18, 0))
                .build();
        assertTrue(channel.send(e, "{}", cfg()).isSuccess());

        String body = server.takeRequest(2, TimeUnit.SECONDS).getBody().readUtf8();
        JsonNode card = parseCard(body);
        assertEquals("grey", card.get("header").get("template").asText());
        // null severity 兜底标题 — 仍按 eventType 优先
        assertEquals("高风险操作已阻断", card.get("header").get("title").get("content").asText());
    }

    // ============================================================
    // 审计跳转链接 — publicBaseUrl 状态矩阵
    // ============================================================

    @Test
    void send_validPublicBaseUrl_includesAuditLinkButton() throws Exception {
        // setUp 默认使用 http://localhost:8080(有效)
        server.enqueue(new MockResponse().setResponseCode(200));
        assertTrue(channel.send(l4Event(), "{}", cfg()).isSuccess());

        String body = server.takeRequest(2, TimeUnit.SECONDS).getBody().readUtf8();
        // 完整 URL 拼接(尾部斜杠已被 trim)
        assertTrue(body.contains("http://localhost:8080/audit?auditId=audit-123"),
                "应含审计跳转 URL: " + body);
        assertTrue(body.contains("查看审计详情"), "应含按钮文案: " + body);
        // auditId 在审计行中以 code 形式呈现
        assertTrue(body.contains("`audit-123`"), "应含 auditId code 段: " + body);
    }

    @Test
    void send_missingPublicBaseUrl_omitsActionButtonButKeepsAuditId() throws Exception {
        // 构造一个 publicBaseUrl 为空的 channel
        FeishuChannel noLink = new FeishuChannel(new ObjectMapper(),
                new NotificationPublicLinkProperties(""));
        server.enqueue(new MockResponse().setResponseCode(200));
        assertTrue(noLink.send(l4Event(), "{}", cfg()).isSuccess());

        String body = server.takeRequest(2, TimeUnit.SECONDS).getBody().readUtf8();
        assertFalse(body.contains("查看审计详情"),
                "缺 publicBaseUrl 时不应含按钮: " + body);
        assertFalse(body.contains("/audit?auditId="),
                "缺 publicBaseUrl 时不应含跳转 URL: " + body);
        // auditId code 段仍应出现(便于用户手动查)
        assertTrue(body.contains("`audit-123`"),
                "缺 publicBaseUrl 时审计行仍应显示 auditId: " + body);
    }

    @Test
    void send_invalidPublicBaseUrl_omitsActionButton() throws Exception {
        FeishuChannel badLink = new FeishuChannel(new ObjectMapper(),
                new NotificationPublicLinkProperties("not-a-valid-url"));
        server.enqueue(new MockResponse().setResponseCode(200));
        assertTrue(badLink.send(l4Event(), "{}", cfg()).isSuccess());

        String body = server.takeRequest(2, TimeUnit.SECONDS).getBody().readUtf8();
        assertFalse(body.contains("查看审计详情"),
                "非法 publicBaseUrl 时不应含按钮: " + body);
        assertFalse(body.contains("/audit?auditId="),
                "非法 publicBaseUrl 时不应含跳转 URL: " + body);
    }

    // ============================================================
    // 卡片正文 — 发生时间 + summary + key object
    // ============================================================

    @Test
    void send_cardContainsOccurredAtSummaryAndKeyObject() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));
        assertTrue(channel.send(l4Event(), "{}", cfg()).isSuccess());

        String body = server.takeRequest(2, TimeUnit.SECONDS).getBody().readUtf8();
        // 发生时间格式 yyyy-MM-dd HH:mm
        assertTrue(body.contains("2026-06-19 18:00"),
                "卡片应含发生时间: " + body);
        // summary
        assertTrue(body.contains("test summary text"),
                "卡片应含 summary: " + body);
        // key object (matchedRuleId)
        assertTrue(body.contains("rule-1"),
                "卡片应含 1 个 key object (matchedRuleId): " + body);
    }

    @Test
    void send_doesNotLeakDetailInCard() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));
        // l4Event 默认带 detail="SENSITIVE_DETAIL_TOKEN_MUST_NOT_LEAK"
        assertTrue(channel.send(l4Event(), "{}", cfg()).isSuccess());

        String body = server.takeRequest(2, TimeUnit.SECONDS).getBody().readUtf8();
        assertFalse(body.contains("SENSITIVE_DETAIL_TOKEN_MUST_NOT_LEAK"),
                "卡片不应包含 detail 字段原文(可能含敏感命令): " + body);
    }

    // ============================================================
    // 不变行为 — 签名 + HTTP 错误处理
    // ============================================================

    @Test
    void send_includesTimestampAndSign() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));

        long before = System.currentTimeMillis() / 1000L;
        NotificationSendResult r = channel.send(l4Event(), "{}", cfg());
        long after = System.currentTimeMillis() / 1000L;
        assertTrue(r.isSuccess());

        RecordedRequest req = server.takeRequest(2, TimeUnit.SECONDS);
        String body = req.getBody().readUtf8();
        ObjectMapper m = new ObjectMapper();
        long ts = Long.parseLong(m.readTree(body).get("timestamp").asText());
        assertTrue(ts >= before && ts <= after, "timestamp=" + ts + " not in [" + before + "," + after + "]");
        String sign = m.readTree(body).get("sign").asText();
        assertNotNull(sign);
        assertFalse(sign.isBlank());
        assertEquals("interactive", m.readTree(body).get("msg_type").asText());
    }

    @Test
    void generateSign_matchesOfficialAlgorithm() {
        // 飞书官方文档示例:
        //   secret = "test-secret-key", timestamp = 1700000000
        //   string_to_sign = "1700000000\ntest-secret-key"
        //   hmac_code (HmacSHA256) → base64
        long ts = 1700000000L;
        String expected = manualFeishuSign("test-secret-key", ts);
        assertEquals(expected, FeishuChannel.generateSign("test-secret-key", ts));
    }

    private static String manualFeishuSign(String secret, long ts) {
        try {
            String stringToSign = ts + "\n" + secret;
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    stringToSign.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] result = mac.doFinal(new byte[]{});
            return Base64.getEncoder().encodeToString(result);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void send_withoutSecret_throws() {
        NotificationConfig.ChannelConfig c = NotificationConfig.ChannelConfig.builder()
                .id("feishu-bad").type(ChannelType.FEISHU).enabled(true)
                .url(server.url("/hook").toString())
                .secret(null).build();
        assertThrows(IllegalArgumentException.class,
                () -> channel.send(l4Event(), "{}", c));
    }

    @Test
    void send_5xx_returnsFailed() {
        server.enqueue(new MockResponse().setResponseCode(503).setBody("oops"));
        NotificationSendResult r = channel.send(l4Event(), "{}", cfg());
        assertFalse(r.isSuccess());
        assertEquals(503, r.getResponseCode());
        assertEquals("oops", r.getResponseBody());
    }
}
