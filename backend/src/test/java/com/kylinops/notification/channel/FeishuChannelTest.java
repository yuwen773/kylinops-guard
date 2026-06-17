package com.kylinops.notification.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kylinops.notification.ChannelType;
import com.kylinops.notification.NotificationConfig;
import com.kylinops.notification.NotificationEvent;
import com.kylinops.notification.NotificationEventType;
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
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * FeishuChannel 测试 — timestamp+sign 算法 / markdown payload。
 */
class FeishuChannelTest {

    private MockWebServer server;
    private FeishuChannel channel;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        channel = new FeishuChannel(new ObjectMapper());
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

    private NotificationEvent event() {
        return NotificationEvent.builder()
                .eventId("e-1").eventType(NotificationEventType.L4_BLOCK)
                .severity(NotificationSeverity.CRITICAL)
                .title("L4 blocked")
                .summary("rm -rf / blocked")
                .matchedRuleId("rm_root_recursive")
                .auditId("audit-1").build();
    }

    @Test
    void send_2xx_succeedsAndBodyContainsMarkdown() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"ok\":true}"));

        NotificationSendResult r = channel.send(event(), "{}", cfg());
        assertTrue(r.isSuccess());

        RecordedRequest req = server.takeRequest(2, TimeUnit.SECONDS);
        String body = req.getBody().readUtf8();
        assertTrue(body.contains("\"msg_type\":\"interactive\""), "应使用 interactive 消息: " + body);
        assertTrue(body.contains("\"markdown\""), "应包含 markdown 元素: " + body);
        assertTrue(body.contains("L4 blocked"), "title 应在 markdown 中: " + body);
        assertTrue(body.contains("rm_root_recursive"), "matchedRuleId 应在 markdown 中: " + body);
    }

    @Test
    void send_includesTimestampAndSign() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));

        long before = System.currentTimeMillis() / 1000L;
        NotificationSendResult r = channel.send(event(), "{}", cfg());
        long after = System.currentTimeMillis() / 1000L;
        assertTrue(r.isSuccess());

        RecordedRequest req = server.takeRequest(2, TimeUnit.SECONDS);
        String body = req.getBody().readUtf8();
        // timestamp 在 [before, after] 范围内
        ObjectMapper m = new ObjectMapper();
        long ts = Long.parseLong(m.readTree(body).get("timestamp").asText());
        assertTrue(ts >= before && ts <= after, "timestamp=" + ts + " not in [" + before + "," + after + "]");
        // sign 存在
        String sign = m.readTree(body).get("sign").asText();
        assertNotNull(sign);
        assertFalse(sign.isBlank());
    }

    @Test
    void generateSign_matchesOfficialAlgorithm() {
        // 飞书官方文档示例:
        //   secret = "test-secret-key", timestamp = 1700000000
        //   string_to_sign = "1700000000\ntest-secret-key"
        //   hmac_code (HmacSHA256) → base64
        // 我们手算:对空字节做 HMAC(key = "1700000000\ntest-secret-key"),然后 base64
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
        // secret 缺失 → 应抛 IllegalArgumentException
        assertThrows(IllegalArgumentException.class,
                () -> channel.send(event(), "{}", c));
    }

    @Test
    void send_5xx_returnsFailed() {
        server.enqueue(new MockResponse().setResponseCode(503).setBody("oops"));
        NotificationSendResult r = channel.send(event(), "{}", cfg());
        assertFalse(r.isSuccess());
        assertEquals(503, r.getResponseCode());
        assertEquals("oops", r.getResponseBody());
    }
}
