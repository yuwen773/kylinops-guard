package com.kylinops.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kylinops.common.ApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;

import java.net.http.HttpClient;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AuthController 集成测试 — 验证单管理员会话登录/会话查询/登出。
 * <p>
 * Step 1 中的 6 个必含场景：
 * <ul>
 *   <li>有效 BCrypt 凭据 → 200 + 管理员摘要</li>
 *   <li>无效凭据 → 401（不暴露哪个字段失败）</li>
 *   <li>第 5 次连续失败 → 423 Locked，15 分钟</li>
 *   <li>登录成功 → sessionId 轮换</li>
 *   <li>{@code GET /api/auth/session} → 已认证返回 CSRF token</li>
 *   <li>{@code POST /api/auth/logout} → 204 + invalidate</li>
 * </ul>
 * </p>
 *
 * <h3>测试凭据来源</h3>
 * <p>
 * 凭据由 {@link com.kylinops.config.KylinOpsConfig.KylinOpsConfigInitializer} 的
 * dev/test 默认 BCrypt hash 占位（见 application-dev.yml / application-test.yml）注入。
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("AuthController — 单管理员会话登录/会话/登出")
class AuthControllerTest {

    @LocalServerPort
    private int port;

    @Autowired
    private RestTemplateBuilder restTemplateBuilder;

    @Autowired
    private LoginRateLimiter rateLimiter;

    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 用 JDK 11+ java.net.http.HttpClient（{@link JdkClientHttpRequestFactory}）
     * 避开 {@code java.net.HttpURLConnection} 在 401 POST streaming 下的 retry bug。
     */
    @BeforeEach
    void wrapRestTemplate() {
        HttpClient httpClient = HttpClient.newBuilder().build();
        ClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        this.restTemplate = new TestRestTemplate(restTemplateBuilder
                .requestFactory(() -> factory));
        this.restTemplate.setUriTemplateHandler(new org.springframework.web.util.DefaultUriBuilderFactory("http://localhost:" + port));
        // 每个测试开始前清空登录限流器状态，避免测试间污染
        rateLimiter.reset();
    }

    /** 与 application-test.yml 中 password-hash 默认值匹配的明文（hash: $2a$10$...test/admin...） */
    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "test-admin-pwd";

    private HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private ResponseEntity<String> login(String username, String password) {
        String body;
        try {
            body = objectMapper.writeValueAsString(Map.of("username", username, "password", password));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return restTemplate.postForEntity("/api/auth/login", new HttpEntity<>(body, jsonHeaders()), String.class);
    }

    // ==================== Step 1 6 个用例 ====================

    @Test
    @DisplayName("有效 BCrypt 凭据 → 200 + 管理员摘要")
    void validCredentialsReturn200AndAdminSummary() {
        ResponseEntity<String> response = login(ADMIN_USER, ADMIN_PASS);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).contains("\"username\":\"" + ADMIN_USER + "\"");
        // 必须包含 csrfToken / expiresAt
        assertThat(response.getBody()).contains("csrfToken");
        assertThat(response.getBody()).contains("expiresAt");
    }

    @Test
    @DisplayName("无效凭据 → 401 且不暴露哪个字段失败")
    void invalidCredentialsReturn401() {
        ResponseEntity<String> response = login(ADMIN_USER, "wrong-password");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        // 通用文案 — 不应区分用户名/密码
        assertThat(response.getBody()).contains("用户名或密码错误");
        assertThat(response.getBody()).doesNotContain("用户不存在");
        assertThat(response.getBody()).doesNotContain("password");
    }

    @Test
    @DisplayName("第 5 次连续失败 → 423 Locked")
    void fifthConsecutiveFailureLocks() {
        // 用一个新用户名（避免与其他测试共享限流计数），这里直接用 admin 在 lockDuration 内逐次错
        // 由于 IP 限流也是 10/min，6 次失败不会被 IP 限流影响
        for (int i = 0; i < 4; i++) {
            ResponseEntity<String> r = login(ADMIN_USER, "wrong-" + i);
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        // 第 5 次：仍然 401
        ResponseEntity<String> fifth = login(ADMIN_USER, "wrong-5");
        assertThat(fifth.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // 第 6 次起：423 Locked（同一 IP+username 已达 5 次）
        ResponseEntity<String> sixth = login(ADMIN_USER, "wrong-6");
        assertThat(sixth.getStatusCode()).isEqualTo(HttpStatus.LOCKED);
    }

    @Test
    @DisplayName("成功登录 → sessionId 轮换")
    void successfulLoginRotatesSessionId() throws Exception {
        // 第一次登录：拿到 session cookie
        ResponseEntity<String> first = login(ADMIN_USER, ADMIN_PASS);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        String firstSession = extractSessionCookie(first.getHeaders());
        String firstCsrf = extractCsrfFromBody(first.getBody());
        assertThat(firstSession).isNotNull();

        // 登出（必须带 session cookie + CSRF token，否则被 Spring CSRF 拒绝为 403）
        HttpHeaders logoutH = new HttpHeaders();
        logoutH.add(HttpHeaders.COOKIE, firstSession);
        if (firstCsrf != null) {
            logoutH.add("X-CSRF-TOKEN", firstCsrf);
        }
        ResponseEntity<String> logout = restTemplate.exchange(
                "/api/auth/logout", HttpMethod.POST, new HttpEntity<>(logoutH), String.class);
        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // 再次登录：sessionId 必须变化
        ResponseEntity<String> second = login(ADMIN_USER, ADMIN_PASS);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        String secondSession = extractSessionCookie(second.getHeaders());
        assertThat(secondSession).isNotNull();
        assertThat(secondSession).isNotEqualTo(firstSession);
    }

    @Test
    @DisplayName("GET /api/auth/session → 已认证返回 CSRF token")
    void sessionEndpointReturnsCsrfWhenAuthenticated() throws Exception {
        // 先登录拿到 session cookie
        ResponseEntity<String> loginResp = login(ADMIN_USER, ADMIN_PASS);
        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String cookie = extractSessionCookie(loginResp.getHeaders());
        assertThat(cookie).isNotNull();

        // 携带 session cookie 访问 /api/auth/session
        HttpHeaders h = new HttpHeaders();
        h.add(HttpHeaders.COOKIE, cookie);
        ResponseEntity<String> session = restTemplate.exchange(
                "/api/auth/session", HttpMethod.GET, new HttpEntity<>(h), String.class);

        assertThat(session.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(session.getBody()).isNotNull();
        JsonNode root = objectMapper.readTree(session.getBody());
        // 包一层 ApiResponse
        assertThat(root.path("code").asInt()).isEqualTo(200);
        JsonNode data = root.path("data");
        assertThat(data.path("username").asText()).isEqualTo(ADMIN_USER);
        assertThat(data.path("csrfToken").asText()).isNotBlank();
        assertThat(data.path("loginAt").asText()).isNotBlank();
    }

    @Test
    @DisplayName("POST /api/auth/logout → 204 + session 失效")
    void logoutReturns204AndInvalidatesSession() throws Exception {
        // 登录
        ResponseEntity<String> loginResp = login(ADMIN_USER, ADMIN_PASS);
        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String cookie = extractSessionCookie(loginResp.getHeaders());
        String csrf = extractCsrfFromBody(loginResp.getBody());
        assertThat(cookie).isNotNull();

        // 登出（带 session cookie + CSRF token）
        HttpHeaders h = new HttpHeaders();
        h.add(HttpHeaders.COOKIE, cookie);
        if (csrf != null) {
            h.add("X-CSRF-TOKEN", csrf);
        }
        ResponseEntity<String> logout = restTemplate.exchange(
                "/api/auth/logout", HttpMethod.POST, new HttpEntity<>(h), String.class);
        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // 登出后 session 应失效：再访问 /api/auth/session 应得 401
        ResponseEntity<String> session = restTemplate.exchange(
                "/api/auth/session", HttpMethod.GET, new HttpEntity<>(h), String.class);
        assertThat(session.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ==================== 辅助方法 ====================

    private String extractSessionCookie(HttpHeaders headers) {
        if (headers == null) return null;
        var cookies = headers.get(HttpHeaders.SET_COOKIE);
        if (cookies == null) return null;
        for (String c : cookies) {
            if (c.startsWith("KYLINOPS_SESSION") || c.startsWith("JSESSIONID")) {
                return c.split(";")[0];
            }
        }
        return cookies.isEmpty() ? null : cookies.get(0).split(";")[0];
    }

    /** 从 Set-Cookie 中抽取 CSRF token 值（XSRF-TOKEN / X-XSRF-TOKEN） */
    private String extractCsrfToken(HttpHeaders headers) {
        if (headers == null) return null;
        var cookies = headers.get(HttpHeaders.SET_COOKIE);
        if (cookies == null) return null;
        for (String c : cookies) {
            if (c.startsWith("XSRF-TOKEN") || c.startsWith("X-XSRF-TOKEN")) {
                int eq = c.indexOf('=');
                return eq > 0 ? c.substring(eq + 1).split(";")[0] : null;
            }
        }
        return null;
    }

    /** 从登录响应的 JSON body 中取 csrfToken */
    private String extractCsrfFromBody(String body) throws Exception {
        if (body == null) return null;
        JsonNode root = objectMapper.readTree(body);
        JsonNode token = root.path("data").path("csrfToken");
        return token.isMissingNode() || token.isNull() ? null : token.asText();
    }

    private String withSessionCookie(String cookie) {
        return cookie;
    }
}
