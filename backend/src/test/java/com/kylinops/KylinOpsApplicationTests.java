package com.kylinops;

import com.kylinops.common.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 麒麟安全智能运维 Agent — 集成测试
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class KylinOpsApplicationTests {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("GET /api/health 应返回 200 且 status 为 UP")
    void healthEndpointShouldReturnUp() {
        @SuppressWarnings("unchecked")
        ResponseEntity<ApiResponse<Map<String, Object>>> response =
                (ResponseEntity<ApiResponse<Map<String, Object>>>) (Object)
                        restTemplate.getForEntity("/api/health", ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(200);
        assertThat(response.getBody().getData())
                .isNotNull()
                .containsEntry("status", "UP");
    }

    @Test
    @DisplayName("GET /api/health 应返回版本号、服务名和时间戳")
    void healthEndpointShouldReturnMetadata() {
        @SuppressWarnings("unchecked")
        ResponseEntity<ApiResponse<Map<String, Object>>> response =
                (ResponseEntity<ApiResponse<Map<String, Object>>>) (Object)
                        restTemplate.getForEntity("/api/health", ApiResponse.class);

        assertThat(response.getBody()).isNotNull();
        Map<String, Object> data = response.getBody().getData();
        assertThat(data).containsKeys("service", "version", "timestamp", "appName");
        assertThat(data.get("service")).isEqualTo("kylin-ops-guard");
    }

    @Test
    @DisplayName("GET /api/health 应包含 JVM 信息")
    void healthEndpointShouldContainJvmInfo() {
        @SuppressWarnings("unchecked")
        ResponseEntity<ApiResponse<Map<String, Object>>> response =
                (ResponseEntity<ApiResponse<Map<String, Object>>>) (Object)
                        restTemplate.getForEntity("/api/health", ApiResponse.class);

        assertThat(response.getBody()).isNotNull();
        Map<String, Object> data = response.getBody().getData();

        @SuppressWarnings("unchecked")
        Map<String, Object> jvm = (Map<String, Object>) data.get("jvm");
        assertThat(jvm).isNotNull();
        assertThat(jvm).containsKeys("javaVersion", "availableProcessors",
                "totalMemory", "freeMemory", "maxMemory");
    }

    @Test
    @DisplayName("GET /api/nonexistent 应返回 404")
    void nonexistentEndpointShouldReturn404() {
        ResponseEntity<?> response =
                restTemplate.getForEntity("/api/nonexistent", ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).isInstanceOf(ApiResponse.class);
        ApiResponse<?> body = (ApiResponse<?>) response.getBody();
        assertThat(body.getCode()).isEqualTo(404);
    }
}
