package com.kylinops.common;

import com.kylinops.tool.ToolNotRegisteredException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全局异常处理器测试
 * <p>
 * 验证异常脱敏、traceId 注入和 HTTP 状态码正确性。
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("GlobalExceptionHandler — 异常脱敏")
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("不存在的接口返回 404 + 接口不存在")
    void nonExistentEndpointReturns404() throws Exception {
        mockMvc.perform(get("/api/non-existent-path-xyz")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message", containsString("接口不存在")));
    }

    @Test
    @DisplayName("不支持的请求方法返回 405")
    void methodNotAllowedReturns405() throws Exception {
        mockMvc.perform(get("/api/chat/send")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value(405))
                .andExpect(jsonPath("$.message", containsString("不支持的请求方法")));
    }

    @Test
    @DisplayName("无效 JSON 请求体返回 400")
    void invalidJsonBodyReturns400() throws Exception {
        mockMvc.perform(post("/api/chat/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("invalid json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", containsString("请求体解析失败")));
    }

    @Test
    @DisplayName("500 错误包含 traceId 且不泄漏异常消息")
    void serverErrorIncludesTraceId() {
        // 直接测试 GlobalExceptionHandler.handleUnhandledException
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ApiResponse<Void> response = handler.handleUnhandledException(
                new RuntimeException("内部敏感信息: DB_CONNECTION_REFUSED"));

        // 不应包含异常消息
        assertThat(response.getMessage())
                .doesNotContain("DB_CONNECTION_REFUSED")
                .doesNotContain("敏感信息");

        // 应包含 traceId
        assertThat(response.getTraceId()).isNotNull();
        assertThat(response.getTraceId()).hasSize(12);

        // 应为 500
        assertThat(response.getCode()).isEqualTo(500);
    }

    @Test
    @DisplayName("ToolNotRegisteredException 返回 400 + 明确错误")
    void toolNotRegisteredReturns400() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ApiResponse<Void> response = handler.handleToolNotRegisteredException(
                new ToolNotRegisteredException("未知工具: test_tool"));

        assertThat(response.getCode()).isEqualTo(400);
        assertThat(response.getMessage()).contains("未知工具");
    }

    @Test
    @DisplayName("BusinessException 返回自定义错误码")
    void businessExceptionReturnsCustomCode() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ApiResponse<Void> response = handler.handleBusinessException(
                new BusinessException(429, "请求过于频繁"));

        assertThat(response.getCode()).isEqualTo(429);
        assertThat(response.getMessage()).contains("请求过于频繁");
    }
}
