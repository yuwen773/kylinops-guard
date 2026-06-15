package com.kylinops.auth;

import com.kylinops.common.ApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 安全边界集成测试 — 验证 SecurityConfig 对 API 端点的保护规则。
 * <p>
 * 测试场景：
 * <ul>
 *   <li>公开端点（/api/health, /api/health/live, /api/health/ready, /api/auth/login）→ 无需认证</li>
 *   <li>受保护端点（/api/audit/logs, /api/tools）→ 返回 401</li>
 *   <li>401 响应必须是 JSON 格式且包含 code/message/traceId</li>
 * </ul>
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.security.user.name=admin",
            "spring.security.user.password=test"
        })
@ActiveProfiles("test")
@DisplayName("Security Boundary Integration Test")
class SecurityBoundaryIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    private TestRestTemplate authRestTemplate;

    @BeforeEach
    void setUp() {
        authRestTemplate = restTemplate.withBasicAuth("admin", "test");
    }

    @Test
    @DisplayName("GET /api/health -> 200 (permitAll)")
    void healthEndpointShouldBePublic() {
        ResponseEntity<ApiResponse> response = restTemplate
                .getForEntity("/api/health", ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("GET /api/health/live -> 200 (permitAll)")
    void healthLiveEndpointShouldBePublic() {
        ResponseEntity<ApiResponse> response = restTemplate
                .getForEntity("/api/health/live", ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("GET /api/health/ready -> 非 401 (permitAll)")
    void healthReadyEndpointShouldBePublic() {
        ResponseEntity<String> response = restTemplate
                .getForEntity("/api/health/ready", String.class);

        // 不应返回 401 — 安全配置放行了该路径
        // (可以 200 或 503，取决于 DB 状态，但绝不能 401)
        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("GET /api/audit/logs -> 401 (需要认证)")
    void auditLogsEndpointShouldRequireAuth() {
        ResponseEntity<String> response = restTemplate
                .getForEntity("/api/audit/logs", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).contains("code");
        assertThat(response.getBody()).contains("message");
        assertThat(response.getBody()).contains("traceId");
    }

    @Test
    @DisplayName("GET /api/tools -> 401 (需要认证)")
    void toolsEndpointShouldRequireAuth() {
        ResponseEntity<String> response = restTemplate
                .getForEntity("/api/tools", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).contains("\"code\":401");
    }

    @Test
    @DisplayName("POST /api/auth/login -> 非 401 (permitAll, 即使路由未实现)")
    void loginEndpointShouldBePublic() {
        ResponseEntity<String> response = restTemplate
                .postForEntity("/api/auth/login", null, String.class);

        // 不应返回 401 — 安全配置放行了该路径
        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("401 响应体是 JSON 且包含 code/message/traceId")
    void unauthorizedResponseShouldContainTraceId() {
        ResponseEntity<String> response = restTemplate
                .getForEntity("/api/audit/logs?page=0&size=10", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        String body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).contains("\"code\":401");
        assertThat(body).contains("\"message\"");
        assertThat(body).contains("\"traceId\"");
    }
}
