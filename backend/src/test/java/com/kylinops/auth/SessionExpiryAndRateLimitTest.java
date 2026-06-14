package com.kylinops.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kylinops.agent.AgentResult;
import com.kylinops.chat.ChatService;
import com.kylinops.common.ApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Session 过期 + Chat 限流 + 锁定恢复测试。
 * <p>
 * <ul>
 *   <li>绝对超时（>8h）→ 401</li>
 *   <li>空闲超时（>30m 不活动）→ Spring Session 401（这里只断言 session 失效）</li>
 *   <li>/api/chat/send 30/分钟/会话 → 第 31 次 429</li>
 *   <li>lockDuration 后锁定恢复</li>
 * </ul>
 * </p>
 *
 * <h3>Mock 范围</h3>
 * <p>ChatService 被 Mock 化以避免依赖 LLM/工具，仅验证限流是否先于 ChatService 触发。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            // 缩短 idle/absolute 以便在测试中可观察行为；锁定时长与限流保持默认
            "kylinops.auth.idle-timeout=10s",
            "kylinops.auth.absolute-timeout=2s",
            "kylinops.auth.lock-duration=200ms",
            "kylinops.auth.chat-rate-limit=3",
            "kylinops.auth.chat-rate-window=10s"
        })
@ActiveProfiles("test")
@DisplayName("Session 过期 / Chat 限流 / 锁定恢复")
class SessionExpiryAndRateLimitTest {

    @org.springframework.boot.test.web.server.LocalServerPort
    private int port;

    @Autowired
    private org.springframework.boot.web.client.RestTemplateBuilder restTemplateBuilder;

    @Autowired
    private LoginRateLimiter rateLimiter;

    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ChatService chatService;

    /** 让 ChatService 永远返回成功，避免触发安全/工具链 */
    @BeforeEach
    void mockChatService() {
        AgentResult ok = AgentResult.builder()
                .sessionId("test-session")
                .answer("ok")
                .build();
        Mockito.when(chatService.processMessage(Mockito.anyString(), Mockito.any(), Mockito.any()))
                .thenReturn(ok);
        // 用 JDK 11+ java.net.http.HttpClient 避开 HttpURLConnection 401 streaming bug
        HttpClient httpClient = HttpClient.newBuilder().build();
        this.restTemplate = new TestRestTemplate(restTemplateBuilder
                .requestFactory(() -> new JdkClientHttpRequestFactory(httpClient)));
        this.restTemplate.setUriTemplateHandler(new org.springframework.web.util.DefaultUriBuilderFactory("http://localhost:" + port));
        // 清空登录限流器状态，避免测试间污染
        rateLimiter.reset();
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private String loginAndGetCookie() {
        try {
            String body = objectMapper.writeValueAsString(Map.of("username", "admin", "password", "test-admin-pwd"));
            ResponseEntity<String> r = restTemplate.postForEntity(
                    "/api/auth/login", new HttpEntity<>(body, jsonHeaders()), String.class);
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
            List<String> setCookies = r.getHeaders().get(HttpHeaders.SET_COOKIE);
            assertThat(setCookies).isNotNull();
            for (String sc : setCookies) {
                if (sc.startsWith("KYLINOPS_SESSION") || sc.startsWith("JSESSIONID")) {
                    return sc.split(";")[0];
                }
            }
            throw new IllegalStateException("No session cookie in response: " + setCookies);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private HttpHeaders authedHeaders(String cookie) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.add(HttpHeaders.COOKIE, cookie);
        return h;
    }

    /** 先做一次 GET 拿到 csrfToken（来自响应 body），再返回带 CSRF header 的 headers */
    /**
     * 轮询等待条件成立（替代 Thread.sleep + 固定等待，避免 flaky）。
     */
    private static void pollUntil(Supplier<Boolean> condition, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.get()) return;
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Poll interrupted", e);
            }
        }
        throw new AssertionError("条件在 " + timeout.toMillis() + "ms 内未满足");
    }

    private HttpHeaders authedHeadersWithCsrf(String sessionCookie) {
        HttpHeaders h = authedHeaders(sessionCookie);
        // 触发一次 GET /api/auth/session 让服务器在 body 中返回 csrfToken
        ResponseEntity<String> r = restTemplate.exchange(
                "/api/auth/session", HttpMethod.GET, new HttpEntity<>(h), String.class);
        String csrf = null;
        try {
            JsonNode root = objectMapper.readTree(r.getBody());
            JsonNode token = root.path("data").path("csrfToken");
            if (!token.isMissingNode() && !token.isNull()) {
                csrf = token.asText();
            }
        } catch (Exception e) {
            // ignore
        }
        HttpHeaders h2 = new HttpHeaders();
        h2.setContentType(MediaType.APPLICATION_JSON);
        h2.add(HttpHeaders.COOKIE, sessionCookie);
        if (csrf != null) {
            h2.add("X-CSRF-TOKEN", csrf);
        }
        return h2;
    }

    // ==================== 绝对超时 ====================

    @Test
    @DisplayName("绝对超时（>8h 等价于这里 2s）→ 401")
    void absoluteSessionExpiryReturns401() throws Exception {
        // 1) 登录
        String cookie = loginAndGetCookie();

        // 2) poll 等待 absolute-timeout（2s）触发，最多 10s
        HttpHeaders h = authedHeaders(cookie);
        pollUntil(() -> {
            ResponseEntity<String> r = restTemplate.exchange(
                    "/api/auth/session", HttpMethod.GET, new HttpEntity<>(h), String.class);
            return r.getStatusCode() == HttpStatus.UNAUTHORIZED;
        }, Duration.ofSeconds(10));
    }

    // ==================== Chat 限流 ====================

    @Test
    @DisplayName("/api/chat/send 30/分钟/会话（测试用 3/10s）→ 超出 429")
    void chatEndpointRateLimited() throws Exception {
        // 登录
        String cookie = loginAndGetCookie();
        HttpHeaders h = authedHeadersWithCsrf(cookie);

        // 第 1~3 次应通过（mock chatService 总是返回 200）
        for (int i = 0; i < 3; i++) {
            ResponseEntity<String> r = restTemplate.exchange(
                    "/api/chat/send", HttpMethod.POST, new HttpEntity<>("{\"content\":\"hi\"}", h), String.class);
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        // 第 4 次起应 429
        ResponseEntity<String> r4 = restTemplate.exchange(
                "/api/chat/send", HttpMethod.POST, new HttpEntity<>("{\"content\":\"hi\"}", h), String.class);
        assertThat(r4.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    // ==================== 锁定恢复 ====================

    @Test
    @DisplayName("lockDuration 过后锁定恢复（默认 max-failures=5, lock-duration=200ms）")
    void lockoutExpiresAfterLockDuration() throws Exception {
        // 5 次失败触发锁定
        for (int i = 0; i < 5; i++) {
            String body = objectMapper.writeValueAsString(Map.of("username", "admin", "password", "wrong-" + i));
            ResponseEntity<String> r = restTemplate.postForEntity(
                    "/api/auth/login", new HttpEntity<>(body, jsonHeaders()), String.class);
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        // 第 6 次锁定
        String body = objectMapper.writeValueAsString(Map.of("username", "admin", "password", "wrong-lock"));
        ResponseEntity<String> locked = restTemplate.postForEntity(
                "/api/auth/login", new HttpEntity<>(body, jsonHeaders()), String.class);
        assertThat(locked.getStatusCode()).isEqualTo(HttpStatus.LOCKED);

        // poll 等待锁定期（200ms）过去；最多 5s
        pollUntil(() -> {
            try {
                String body2 = objectMapper.writeValueAsString(Map.of("username", "admin", "password", "still-wrong"));
                ResponseEntity<String> r = restTemplate.postForEntity(
                        "/api/auth/login", new HttpEntity<>(body2, jsonHeaders()), String.class);
                return r.getStatusCode() != HttpStatus.LOCKED;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, Duration.ofSeconds(5));
    }
}
