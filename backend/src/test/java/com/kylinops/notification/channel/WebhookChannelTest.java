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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * WebhookChannel 测试 — HMAC 签名 / 4xx/5xx / 异常。
 */
class WebhookChannelTest {

    private MockWebServer server;
    private WebhookChannel channel;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        channel = new WebhookChannel(new ObjectMapper());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private NotificationConfig.ChannelConfig cfg(String url, String secret) {
        return NotificationConfig.ChannelConfig.builder()
                .id("ch-1").type(ChannelType.WEBHOOK).enabled(true)
                .url(url).secret(secret).timeoutMs(2000).build();
    }

    private NotificationEvent event() {
        return NotificationEvent.builder()
                .eventId("e-1").eventType(NotificationEventType.L4_BLOCK)
                .severity(NotificationSeverity.CRITICAL)
                .title("L4 blocked").summary("rm -rf / blocked")
                .auditId("audit-1").build();
    }

    @Test
    void send_2xx_returnsOk() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));
        NotificationConfig.ChannelConfig c = cfg(server.url("/hook").toString(), null);

        NotificationSendResult r = channel.send(event(), "{\"k\":\"v\"}", c);

        assertTrue(r.isSuccess());
        assertEquals(200, r.getResponseCode());
        assertEquals("ok", r.getResponseBody());
        assertNull(r.getErrorMessage());

        RecordedRequest req = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(req);
        assertEquals("POST", req.getMethod());
        assertEquals("/hook", req.getPath());
        assertEquals("{\"k\":\"v\"}", req.getBody().readUtf8());
    }

    @Test
    void send_withSecret_addsHmacSignatureHeader() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));
        String secret = "test-secret";
        String body = "{\"x\":1}";
        NotificationConfig.ChannelConfig c = cfg(server.url("/signed").toString(), secret);

        NotificationSendResult r = channel.send(event(), body, c);
        assertTrue(r.isSuccess());

        RecordedRequest req = server.takeRequest(2, TimeUnit.SECONDS);
        String sig = req.getHeader("X-KylinOps-Signature");
        assertNotNull(sig);
        assertTrue(sig.startsWith("HMAC-SHA256 "), "签名头应包含算法前缀: " + sig);
        // 独立计算 HMAC,应与 header 一致
        String expected = WebhookChannel.hmacSha256(secret, body);
        assertEquals("HMAC-SHA256 " + expected, sig);
    }

    @Test
    void send_5xx_returnsFailedWithCodeAndBody() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("server error"));
        NotificationConfig.ChannelConfig c = cfg(server.url("/err").toString(), null);

        NotificationSendResult r = channel.send(event(), "{}", c);

        assertFalse(r.isSuccess());
        assertEquals(500, r.getResponseCode());
        assertEquals("server error", r.getResponseBody());
        assertNotNull(r.getErrorMessage());
    }

    @Test
    void send_4xx_returnsFailed() {
        server.enqueue(new MockResponse().setResponseCode(404).setBody("not found"));
        NotificationConfig.ChannelConfig c = cfg(server.url("/missing").toString(), null);

        NotificationSendResult r = channel.send(event(), "{}", c);
        assertFalse(r.isSuccess());
        assertEquals(404, r.getResponseCode());
    }

    @Test
    void send_emptyUrl_returnsException() {
        NotificationConfig.ChannelConfig c = cfg("", null);
        NotificationSendResult r = channel.send(event(), "{}", c);
        assertFalse(r.isSuccess());
        assertNotNull(r.getErrorMessage());
    }

    @Test
    void hmacSha256_matchesKnownVector() {
        // 已知向量:key="key", data="The quick brown fox jumps over the lazy dog"
        //   → f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8
        String sig = WebhookChannel.hmacSha256("key", "The quick brown fox jumps over the lazy dog");
        assertEquals("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8", sig);
    }
}
