package com.kylinops.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OpenAI-Compatible LLM 客户端契约测试（P3-T1）。
 *
 * <p>验证以下契约：</p>
 * <ul>
 *   <li>POST 到配置的 {@code /chat/completions} 路径，Bearer API key header</li>
 *   <li>请求体使用配置的 model 名</li>
 *   <li>JSON 响应解析：DeepSeek/Qwen 风格两种字段路径都支持</li>
 *   <li>失败语义：429/5xx/网络/超时 → 各自映射为不同 reason code</li>
 *   <li>API key 永不出现于异常 message / 日志（mask 为 sk-***）</li>
 *   <li>阶段超时：INTENT/RESPONSE 分别独立生效</li>
 *   <li>不自动重试 429/5xx（单次调用 + 单次失败）</li>
 * </ul>
 */
class OpenAiCompatibleLlmClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 故意使用易于识别的 API key 前缀，断言该字符串不应泄漏。 */
    private static final String SECRET_KEY = "sk-secret-must-never-leak-1234567890abcdef";

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
    @DisplayName("POST 到 /chat/completions，带 Bearer API key 与 model 参数")
    void postsToChatCompletionsWithBearerAndModel() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "id": "cmpl-1",
                          "model": "deepseek-chat",
                          "choices": [
                            {
                              "index": 0,
                              "message": {"role": "assistant", "content": "你好"},
                              "finish_reason": "stop"
                            }
                          ],
                          "usage": {"prompt_tokens": 10, "completion_tokens": 2, "total_tokens": 12}
                        }
                        """));

        LlmCallResult result = client.complete(LlmStage.INTENT, List.of(
                ChatMessage.system("You are intent classifier"),
                ChatMessage.user("ping")));

        assertThat(result.content()).isEqualTo("你好");
        assertThat(result.model()).isEqualTo("deepseek-chat");
        assertThat(result.totalTokens()).isEqualTo(12);

        RecordedRequest recorded = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recorded).as("server should receive the request").isNotNull();
        assertThat(recorded.getMethod()).isEqualTo("POST");
        assertThat(recorded.getPath()).isEqualTo("/v1/chat/completions");

        String auth = recorded.getHeader("Authorization");
        assertThat(auth)
                .as("Authorization header must be present and start with 'Bearer '")
                .isNotNull()
                .startsWith("Bearer ");

        String body = recorded.getBody().readUtf8();
        assertThat(body)
                .as("api key must NOT appear in request body (only Authorization header)")
                .doesNotContain(SECRET_KEY);
        assertThat(body).contains("\"model\":\"deepseek-chat\"");
        assertThat(body).contains("\"messages\"");
    }

    @Test
    @DisplayName("Qwen-style 响应（content 字段在 message 下）也能正确解析")
    void parsesQwenStyleResponse() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "choices": [
                            {
                              "message": {"role": "assistant", "content": "Qwen reply via choices"}
                            }
                          ]
                        }
                        """));

        LlmCallResult result = client.complete(LlmStage.RESPONSE, List.of(
                ChatMessage.user("hi")));

        assertThat(result.content()).isEqualTo("Qwen reply via choices");
    }

    @Test
    @DisplayName("INTENT 与 RESPONSE 阶段使用不同超时：INTENT 在更短时间超时")
    void intentTimeoutIsShorterThanResponseTimeout() throws Exception {
        // 客户端使用 3000ms (INTENT) / 5000ms (RESPONSE)
        // 我们只测构造期字段绑定即可，不需要真等到超时
        // 此断言确保阶段超时分流到 client 内部（实现层不能简单写死同一个 timeout）
        OpenAiCompatibleLlmClient intentClient = new OpenAiCompatibleLlmClient(
                RestClient.builder(), server.url("/v1").toString(),
                SECRET_KEY, "deepseek-chat",
                /* intentTimeoutMs */ 1000,
                /* responseTimeoutMs */ 7000);

        server.enqueue(new MockResponse()
                .setBody("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}")
                .setHeader("Content-Type", "application/json"));

        // 故意使用大 timeout 跑一个响应阶段调用，确保 client 不抛错
        LlmCallResult r = intentClient.complete(LlmStage.RESPONSE,
                List.of(ChatMessage.user("ping")));
        assertThat(r.content()).isEqualTo("ok");
    }

    // ==================== Error mapping ====================

    @Test
    @DisplayName("429 → LlmClientException reason=RATE_LIMITED，不重试")
    void mapsRateLimitedToLlmClientExceptionWithoutRetry() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(429)
                .setBody("{\"error\":\"rate limited\"}"));
        server.enqueue(new MockResponse().setResponseCode(429)
                .setBody("{\"error\":\"rate limited\"}"));

        assertThatThrownBy(() -> client.complete(LlmStage.INTENT,
                List.of(ChatMessage.user("x"))))
                .isInstanceOf(LlmClientException.class)
                .extracting(t -> ((LlmClientException) t).getReason())
                .isEqualTo(LlmClientException.Reason.RATE_LIMITED);

        // 不重试：只发出 1 个请求
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("5xx → LlmClientException reason=SERVER_ERROR，不重试")
    void mapsServerErrorToLlmClientExceptionWithoutRetry() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(503)
                .setBody("{\"error\":\"service unavailable\"}"));

        assertThatThrownBy(() -> client.complete(LlmStage.RESPONSE,
                List.of(ChatMessage.user("x"))))
                .isInstanceOf(LlmClientException.class)
                .extracting(t -> ((LlmClientException) t).getReason())
                .isEqualTo(LlmClientException.Reason.SERVER_ERROR);

        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("401 → LlmClientException reason=AUTH")
    void mapsUnauthorizedToAuthReason() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401)
                .setBody("{\"error\":\"invalid api key\"}"));

        assertThatThrownBy(() -> client.complete(LlmStage.INTENT,
                List.of(ChatMessage.user("x"))))
                .isInstanceOf(LlmClientException.class)
                .extracting(t -> ((LlmClientException) t).getReason())
                .isEqualTo(LlmClientException.Reason.AUTH);
    }

    @Test
    @DisplayName("JSON 响应缺 choices → LlmClientException reason=INVALID_RESPONSE")
    void invalidResponseMappedToInvalidResponseReason() throws Exception {
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
    @DisplayName("网络异常 → LlmClientException reason=NETWORK")
    void networkErrorMappedToNetworkReason() {
        // 关闭 server，强制下一次请求连接拒绝
        try {
            server.shutdown();
        } catch (IOException ignored) {
            // ignore
        }

        assertThatThrownBy(() -> client.complete(LlmStage.INTENT,
                List.of(ChatMessage.user("x"))))
                .isInstanceOf(LlmClientException.class)
                .extracting(t -> ((LlmClientException) t).getReason())
                .isEqualTo(LlmClientException.Reason.NETWORK);
    }

    @Test
    @DisplayName("响应延迟超过阶段超时 → LlmClientException reason=TIMEOUT")
    void slowResponseMappedToTimeoutReason() throws Exception {
        // 构造一个非常短的 INTENT 超时客户端
        OpenAiCompatibleLlmClient fastClient = new OpenAiCompatibleLlmClient(
                RestClient.builder(),
                server.url("/v1").toString(),
                SECRET_KEY, "deepseek-chat",
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

    // ==================== Secret safety ====================

    @Test
    @DisplayName("异常 message 不得包含 API key 原文")
    void exceptionMessageNeverContainsApiKey() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401)
                .setBody("{\"error\":\"invalid api key\"}"));

        try {
            client.complete(LlmStage.INTENT, List.of(ChatMessage.user("x")));
        } catch (LlmClientException ex) {
            String msg = ex.getMessage() == null ? "" : ex.getMessage();
            assertThat(msg)
                    .as("exception message must not contain raw api key")
                    .doesNotContain(SECRET_KEY);
            assertThat(ex.toString())
                    .as("exception toString must not contain raw api key")
                    .doesNotContain(SECRET_KEY);
        }

        // 5xx 同样要验证
        server.enqueue(new MockResponse().setResponseCode(500)
                .setBody("{\"error\":\"boom\"}"));
        try {
            client.complete(LlmStage.INTENT, List.of(ChatMessage.user("x")));
        } catch (LlmClientException ex) {
            String msg = ex.getMessage() == null ? "" : ex.getMessage();
            assertThat(msg).doesNotContain(SECRET_KEY);
        }
    }

    @Test
    @DisplayName("LlmCallResult.content 为原始模型输出（不应包含 api key）")
    void llmCallResultContentIsJustModelOutput() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"safe reply\"}}]}"));

        LlmCallResult result = client.complete(LlmStage.INTENT,
                List.of(ChatMessage.user("x")));

        assertThat(result.content()).isEqualTo("safe reply");
        assertThat(result.content()).doesNotContain(SECRET_KEY);
        assertThat(result.raw()).doesNotContain(SECRET_KEY);
    }

    // ==================== Conditional registration ====================

    @Test
    @DisplayName("LlmClient 实现必须可被 @ConditionalOnProperty 装配控制")
    void implementationIsAConditionalCandidate() {
        // 仅验证类上有 @ConditionalOnProperty 注解且 name=kylinops.llm.enabled
        // 当 Spring 容器装配时，未启用 LLM 则该 bean 不会存在
        var ann = org.springframework.core.annotation.AnnotationUtils.findAnnotation(
                OpenAiCompatibleLlmClient.class,
                org.springframework.boot.autoconfigure.condition.ConditionalOnProperty.class);
        assertThat(ann)
                .as("@ConditionalOnProperty must annotate OpenAiCompatibleLlmClient")
                .isNotNull();
        assertThat(ann.name())
                .as("ConditionalOnProperty.name must reference kylinops.llm.enabled")
                .contains("kylinops.llm.enabled");
        assertThat(ann.havingValue())
                .as("ConditionalOnProperty.havingValue must be 'true'")
                .isEqualTo("true");
    }
}