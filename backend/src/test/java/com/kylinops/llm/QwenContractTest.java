package com.kylinops.llm;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Qwen provider 契约测试 (P3-T6).
 *
 * <p>使用共享的 {@link OpenAiCompatibleLlmClient} 实现 — 与 {@link DeepSeekContractTest}
 * 共享同一实现类，仅 fixture 与 model 名不同。这印证 OpenAI-Compatible 抽象对多个
 * 厂商响应的兼容性。</p>
 *
 * <h3>Qwen 差异点</h3>
 * <ul>
 *   <li>model 名固定为 {@code qwen-turbo}</li>
 *   <li>response 模型字段回填默认 model（Qwen 在某些 path 可能省略 model 字段）</li>
 *   <li>finishReason 可省略（Qwen 兼容模式下可选）</li>
 * </ul>
 */
@DisplayName("Qwen Provider — 契约测试")
class QwenContractTest {

    /** 故意使用易于识别的 API key 前缀，断言该字符串不应泄漏。 */
    private static final String SECRET_KEY = "sk-test-fake-qwen-abcdef1234567890xyz";

    private static final Path QWEN_SUCCESS = Paths.get("src/test/resources/fixtures/qwen-success.json");

    private MockWebServer server;
    private OpenAiCompatibleLlmClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        client = new OpenAiCompatibleLlmClient(
                RestClient.builder(),
                server.url("/v1").toString(),
                SECRET_KEY,
                "qwen-turbo",
                /* intentTimeoutMs */ 3000,
                /* responseTimeoutMs */ 5000);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    // ==================== Happy path ====================

    @Test
    @DisplayName("200 + qwen-success.json fixture → 正确解析 content/model/usage")
    void parsesQwenSuccessFixture() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(Files.readString(QWEN_SUCCESS)));

        LlmCallResult result = client.complete(LlmStage.INTENT, List.of(
                ChatMessage.system("You are intent classifier"),
                ChatMessage.user("ping")));

        assertThat(result.content())
                .isEqualTo("{\"intent\":\"DISK_DIAGNOSIS\",\"confidence\":0.88,\"params\":{}}");
        assertThat(result.model()).isEqualTo("qwen-turbo");
        assertThat(result.promptTokens()).isEqualTo(110);
        assertThat(result.completionTokens()).isEqualTo(28);
        assertThat(result.totalTokens()).isEqualTo(138);
        assertThat(result.finishReason()).isEqualTo("stop");

        // 请求契约：model=qwen-turbo
        RecordedRequest recorded = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recorded).isNotNull();
        assertThat(recorded.getMethod()).isEqualTo("POST");
        assertThat(recorded.getPath()).isEqualTo("/v1/chat/completions");
        assertThat(recorded.getHeader("Authorization")).startsWith("Bearer ");
        assertThat(recorded.getBody().readUtf8()).contains("\"model\":\"qwen-turbo\"");
    }

    @Test
    @DisplayName("Qwen 响应缺 usage 字段时 → tokens 为 null, 解析仍成功")
    void qwenResponseWithoutUsageStillParses() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "id": "qwen-no-usage",
                          "model": "qwen-turbo",
                          "choices": [
                            {
                              "index": 0,
                              "message": {"role": "assistant", "content": "no usage"},
                              "finish_reason": "stop"
                            }
                          ]
                        }
                        """));

        LlmCallResult result = client.complete(LlmStage.INTENT, List.of(
                ChatMessage.user("hi")));

        assertThat(result.content()).isEqualTo("no usage");
        assertThat(result.model()).isEqualTo("qwen-turbo");
        assertThat(result.promptTokens()).isNull();
        assertThat(result.completionTokens()).isNull();
        assertThat(result.totalTokens()).isNull();
    }

    // ==================== Error mapping ====================

    @Test
    @DisplayName("429 → LlmClientException(RATE_LIMITED), 不重试")
    void rateLimitedWithoutRetry() {
        server.enqueue(new MockResponse()
                .setResponseCode(429)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"qwen rate limit\"}"));
        server.enqueue(new MockResponse()
                .setResponseCode(429)
                .setBody("{\"error\":\"qwen rate limit\"}"));
        server.enqueue(new MockResponse()
                .setResponseCode(429)
                .setBody("{\"error\":\"qwen rate limit\"}"));

        assertThatThrownBy(() -> client.complete(LlmStage.INTENT,
                List.of(ChatMessage.user("x"))))
                .isInstanceOf(LlmClientException.class)
                .extracting(t -> ((LlmClientException) t).getReason())
                .isEqualTo(LlmClientException.Reason.RATE_LIMITED);

        // 不重试：只发出 1 个请求
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("403 → LlmClientException(AUTH) — Qwen 用 403 而非 401 标识鉴权失败")
    void forbiddenMapsToAuth() {
        server.enqueue(new MockResponse()
                .setResponseCode(403)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"qwen invalid api key\"}"));

        assertThatThrownBy(() -> client.complete(LlmStage.INTENT,
                List.of(ChatMessage.user("x"))))
                .isInstanceOf(LlmClientException.class)
                .extracting(t -> ((LlmClientException) t).getReason())
                .isEqualTo(LlmClientException.Reason.AUTH);

        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("500 → LlmClientException(SERVER_ERROR), 不重试")
    void serverErrorWithoutRetry() {
        server.enqueue(new MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"qwen internal\"}"));
        server.enqueue(new MockResponse()
                .setResponseCode(500)
                .setBody("{\"error\":\"qwen internal\"}"));
        server.enqueue(new MockResponse()
                .setResponseCode(500)
                .setBody("{\"error\":\"qwen internal\"}"));

        assertThatThrownBy(() -> client.complete(LlmStage.RESPONSE,
                List.of(ChatMessage.user("x"))))
                .isInstanceOf(LlmClientException.class)
                .extracting(t -> ((LlmClientException) t).getReason())
                .isEqualTo(LlmClientException.Reason.SERVER_ERROR);

        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    // ==================== Timeout ====================

    @Test
    @DisplayName("响应延迟超过 INTENT 超时 → LlmClientException(TIMEOUT)")
    void slowResponseMappedToTimeout() throws Exception {
        OpenAiCompatibleLlmClient fastClient = new OpenAiCompatibleLlmClient(
                RestClient.builder(),
                server.url("/v1").toString(),
                SECRET_KEY, "qwen-turbo",
                /* intentTimeoutMs */ 200,
                /* responseTimeoutMs */ 5000);

        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"late\"}}]}")
                .setBodyDelay(1500, TimeUnit.MILLISECONDS));

        assertThatThrownBy(() -> fastClient.complete(LlmStage.INTENT,
                List.of(ChatMessage.user("x"))))
                .isInstanceOf(LlmClientException.class)
                .extracting(t -> ((LlmClientException) t).getReason())
                .isEqualTo(LlmClientException.Reason.TIMEOUT);
    }

    // ==================== Network ====================

    @Test
    @DisplayName("连接失败 → LlmClientException(NETWORK)")
    void networkErrorMapsToNetwork() throws IOException {
        server.shutdown();

        assertThatThrownBy(() -> client.complete(LlmStage.INTENT,
                List.of(ChatMessage.user("x"))))
                .isInstanceOf(LlmClientException.class)
                .extracting(t -> ((LlmClientException) t).getReason())
                .isEqualTo(LlmClientException.Reason.NETWORK);
    }

    // ==================== Invalid response ====================

    @Test
    @DisplayName("响应缺 choices → LlmClientException(INVALID_RESPONSE)")
    void invalidResponseMapsToInvalidResponse() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"result\":\"unexpected shape\"}"));

        assertThatThrownBy(() -> client.complete(LlmStage.INTENT,
                List.of(ChatMessage.user("x"))))
                .isInstanceOf(LlmClientException.class)
                .extracting(t -> ((LlmClientException) t).getReason())
                .isEqualTo(LlmClientException.Reason.INVALID_RESPONSE);
    }

    // ==================== API key safety ====================

    @Test
    @DisplayName("429 异常 message 不含 qwen api key 原文")
    void exceptionMessageNeverContainsApiKey() {
        server.enqueue(new MockResponse()
                .setResponseCode(429)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"qwen rate limit\"}"));

        try {
            client.complete(LlmStage.INTENT, List.of(ChatMessage.user("x")));
        } catch (LlmClientException ex) {
            String msg = ex.getMessage() == null ? "" : ex.getMessage();
            assertThat(msg).doesNotContain(SECRET_KEY);
            assertThat(ex.toString()).doesNotContain(SECRET_KEY);
        }
    }
}