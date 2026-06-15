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
 * DeepSeek provider 契约测试 (P3-T6).
 *
 * <p>使用共享的 {@link OpenAiCompatibleLlmClient} 实现，<strong>不复制实现逻辑</strong>。
 * 仅 fixture 不同 — 验证 DeepSeek 风格响应能正确解析。</p>
 *
 * <h3>覆盖</h3>
 * <ul>
 *   <li>200 + deepseek-success fixture → 正确解析 content/model/usage</li>
 *   <li>429 + deepseek-429 fixture → 抛 LlmClientException(RATE_LIMITED)，不重试</li>
 *   <li>401/403 → AUTH</li>
 *   <li>500/503 → SERVER_ERROR，不重试</li>
 *   <li>超时 → TIMEOUT</li>
 *   <li>网络异常 → NETWORK</li>
 *   <li>错误 JSON → INVALID_RESPONSE</li>
 *   <li>不重试：enqueue 3 次 5xx，只发出 1 次请求</li>
 *   <li>异常 message 不含 apiKey</li>
 * </ul>
 *
 * <p>fixtures 在 {@code src/test/resources/fixtures/}。</p>
 */
@DisplayName("DeepSeek Provider — 契约测试")
class DeepSeekContractTest {

    /** 故意使用易于识别的 API key 前缀，断言该字符串不应泄漏。 */
    private static final String SECRET_KEY = "sk-test-fake-deepseek-1234567890abcdef";

    private static final Path DEEPSEEK_SUCCESS = Paths.get("src/test/resources/fixtures/deepseek-success.json");
    private static final Path DEEPSEEK_429 = Paths.get("src/test/resources/fixtures/deepseek-429.json");
    private static final Path DEEPSEEK_500 = Paths.get("src/test/resources/fixtures/deepseek-500.json");

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
                "deepseek-chat",
                /* intentTimeoutMs */ 3000,
                /* responseTimeoutMs */ 5000);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    // ==================== Happy path ====================

    @Test
    @DisplayName("200 + deepseek-success.json fixture → 正确解析 content/model/usage")
    void parsesDeepSeekSuccessFixture() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(Files.readString(DEEPSEEK_SUCCESS)));

        LlmCallResult result = client.complete(LlmStage.INTENT, List.of(
                ChatMessage.system("You are intent classifier"),
                ChatMessage.user("ping")));

        assertThat(result.content())
                .isEqualTo("{\"intent\":\"SYSTEM_CHECK\",\"confidence\":0.92,\"params\":{}}");
        assertThat(result.model()).isEqualTo("deepseek-chat");
        assertThat(result.promptTokens()).isEqualTo(100);
        assertThat(result.completionTokens()).isEqualTo(30);
        assertThat(result.totalTokens()).isEqualTo(130);
        assertThat(result.finishReason()).isEqualTo("stop");

        // 请求契约：POST /v1/chat/completions, Bearer header, model=deepseek-chat
        RecordedRequest recorded = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recorded).isNotNull();
        assertThat(recorded.getMethod()).isEqualTo("POST");
        assertThat(recorded.getPath()).isEqualTo("/v1/chat/completions");
        assertThat(recorded.getHeader("Authorization")).startsWith("Bearer ");
        assertThat(recorded.getBody().readUtf8()).contains("\"model\":\"deepseek-chat\"");
    }

    // ==================== Error mapping ====================

    @Test
    @DisplayName("429 + deepseek-429.json fixture → LlmClientException(RATE_LIMITED), 不重试")
    void rateLimitedWithoutRetry() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(429)
                .setHeader("Content-Type", "application/json")
                .setBody(Files.readString(DEEPSEEK_429)));
        server.enqueue(new MockResponse()
                .setResponseCode(429)
                .setBody(Files.readString(DEEPSEEK_429)));
        server.enqueue(new MockResponse()
                .setResponseCode(429)
                .setBody(Files.readString(DEEPSEEK_429)));

        assertThatThrownBy(() -> client.complete(LlmStage.INTENT,
                List.of(ChatMessage.user("x"))))
                .isInstanceOf(LlmClientException.class)
                .extracting(t -> ((LlmClientException) t).getReason())
                .isEqualTo(LlmClientException.Reason.RATE_LIMITED);

        // 不重试：只发出 1 个请求
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("401 → LlmClientException(AUTH)")
    void unauthorizedMapsToAuth() {
        server.enqueue(new MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"invalid api key\"}"));

        assertThatThrownBy(() -> client.complete(LlmStage.INTENT,
                List.of(ChatMessage.user("x"))))
                .isInstanceOf(LlmClientException.class)
                .extracting(t -> ((LlmClientException) t).getReason())
                .isEqualTo(LlmClientException.Reason.AUTH);

        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("500 + deepseek-500.json fixture → LlmClientException(SERVER_ERROR), 不重试")
    void serverErrorWithoutRetry() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody(Files.readString(DEEPSEEK_500)));
        server.enqueue(new MockResponse()
                .setResponseCode(502)
                .setBody(Files.readString(DEEPSEEK_500)));
        server.enqueue(new MockResponse()
                .setResponseCode(503)
                .setBody(Files.readString(DEEPSEEK_500)));

        assertThatThrownBy(() -> client.complete(LlmStage.RESPONSE,
                List.of(ChatMessage.user("x"))))
                .isInstanceOf(LlmClientException.class)
                .extracting(t -> ((LlmClientException) t).getReason())
                .isEqualTo(LlmClientException.Reason.SERVER_ERROR);

        // 不重试：3 次 enqueue 5xx，只发出 1 个请求
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    // ==================== Timeout ====================

    @Test
    @DisplayName("响应延迟超过 INTENT 超时 → LlmClientException(TIMEOUT)")
    void slowResponseMappedToTimeout() throws Exception {
        OpenAiCompatibleLlmClient fastClient = new OpenAiCompatibleLlmClient(
                RestClient.builder(),
                server.url("/v1").toString(),
                SECRET_KEY, "deepseek-chat",
                /* intentTimeoutMs */ 200,
                /* responseTimeoutMs */ 5000);

        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(Files.readString(DEEPSEEK_SUCCESS))
                .setBodyDelay(1500, TimeUnit.MILLISECONDS));

        assertThatThrownBy(() -> fastClient.complete(LlmStage.INTENT,
                List.of(ChatMessage.user("x"))))
                .isInstanceOf(LlmClientException.class)
                .extracting(t -> ((LlmClientException) t).getReason())
                .isEqualTo(LlmClientException.Reason.TIMEOUT);
    }

    // ==================== Network ====================

    @Test
    @DisplayName("网络层异常（连接拒绝） → LlmClientException(NETWORK)")
    void networkErrorMapsToNetworkReason() throws IOException {
        // 关闭 server 强制下一次请求连接失败
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
                .setBody("{\"unexpected\":\"shape\"}"));

        assertThatThrownBy(() -> client.complete(LlmStage.INTENT,
                List.of(ChatMessage.user("x"))))
                .isInstanceOf(LlmClientException.class)
                .extracting(t -> ((LlmClientException) t).getReason())
                .isEqualTo(LlmClientException.Reason.INVALID_RESPONSE);
    }

    @Test
    @DisplayName("响应 choices.message.content 为空 → LlmClientException(INVALID_RESPONSE)")
    void emptyContentMapsToInvalidResponse() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\"},\"finish_reason\":\"stop\"}]}"));

        assertThatThrownBy(() -> client.complete(LlmStage.INTENT,
                List.of(ChatMessage.user("x"))))
                .isInstanceOf(LlmClientException.class)
                .extracting(t -> ((LlmClientException) t).getReason())
                .isEqualTo(LlmClientException.Reason.INVALID_RESPONSE);
    }

    // ==================== API key safety ====================

    @Test
    @DisplayName("5xx 异常 message 不含 api key 原文")
    void exceptionMessageNeverContainsApiKey() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody(Files.readString(DEEPSEEK_500)));

        try {
            client.complete(LlmStage.INTENT, List.of(ChatMessage.user("x")));
        } catch (LlmClientException ex) {
            String msg = ex.getMessage() == null ? "" : ex.getMessage();
            assertThat(msg).doesNotContain(SECRET_KEY);
            assertThat(ex.toString()).doesNotContain(SECRET_KEY);
        }
    }
}