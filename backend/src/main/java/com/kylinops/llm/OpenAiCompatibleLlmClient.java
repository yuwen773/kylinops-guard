package com.kylinops.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kylinops.config.KylinOpsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;

/**
 * OpenAI 兼容 LLM 客户端实现（P3-T1）。
 *
 * <h3>装配条件</h3>
 * <p>仅当 {@code kylinops.llm.enabled=true} 时注册。无 key / 关闭时不注册。
 * 该约束由 {@link ConditionalOnProperty} 在容器装配阶段强制。</p>
 *
 * <h3>阶段超时</h3>
 * <p>构造期分别为 INTENT / RESPONSE 阶段构建独立的 RestClient
 * （携带各自的连接/读取超时），避免运行期动态切换带来的歧义。</p>
 *
 * <h3>重试策略</h3>
 * <p><strong>不自动重试</strong>。429 / 5xx / 网络 / 超时一律直接抛出
 * {@link LlmClientException}，由 Agent 层决定回退方案。</p>
 *
 * <h3>API key 保密</h3>
 * <ul>
 *   <li>仅用于构造 {@code Authorization: Bearer ...} 头</li>
 *   <li>日志中始终打印 mask 后版本（{@code sk-***}），原始值不出现在
 *       任何 toString / exception message</li>
 *   <li>异常 message 由 status code + reason 组成，不含原始 key</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "kylinops.llm.enabled", havingValue = "true")
public class OpenAiCompatibleLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);

    /** OpenAI Chat Completions 路径。多数 OpenAI-compatible 实现也使用同一路径。 */
    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    private final RestClient intentClient;
    private final RestClient responseClient;
    private final String model;
    private final String apiKeyMasked;
    private final ObjectMapper objectMapper;

    public OpenAiCompatibleLlmClient(
            KylinOpsConfig.Llm properties,
            ObjectMapper objectMapper) {
        this(properties.getBaseUrl(),
                properties.getApiKey(),
                properties.getModel(),
                properties.getIntentTimeoutMs(),
                properties.getResponseTimeoutMs(),
                objectMapper);
    }

    /**
     * 构造期注入式 — 用于测试和直接 new 的场景。
     *
     * <p>生产代码请使用 {@link #OpenAiCompatibleLlmClient(KylinOpsLlmProperties, ObjectMapper)}
     * 让 Spring 注入 {@link KylinOpsLlmProperties}。</p>
     */
    public OpenAiCompatibleLlmClient(
            RestClient.Builder restClientBuilder,
            String baseUrl,
            String apiKey,
            String model,
            int intentTimeoutMs,
            int responseTimeoutMs) {
        this(baseUrl, apiKey, model, intentTimeoutMs, responseTimeoutMs,
                new ObjectMapper());
    }

    private OpenAiCompatibleLlmClient(
            String baseUrl,
            String apiKey,
            String model,
            int intentTimeoutMs,
            int responseTimeoutMs,
            ObjectMapper objectMapper) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl must not be blank");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey must not be blank");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        this.intentClient = buildClient(baseUrl, apiKey, intentTimeoutMs);
        this.responseClient = buildClient(baseUrl, apiKey, responseTimeoutMs);
        this.model = model;
        this.apiKeyMasked = maskApiKey(apiKey);
        this.objectMapper = objectMapper;
    }

    private static RestClient buildClient(String baseUrl, String apiKey, int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofMillis(Math.max(1, timeoutMs));
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);

        String safeKey = apiKey == null ? "" : apiKey;
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + safeKey)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .requestFactory(factory)
                .build();
    }

    @Override
    public LlmCallResult complete(LlmStage stage, List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        RestClient client = (stage == LlmStage.INTENT) ? intentClient : responseClient;

        OpenAiChatRequest request = new OpenAiChatRequest(model, messages);

        log.debug("LLM 调用开始: stage={}, model={}, messages={}, key={}",
                stage, model, messages.size(), apiKeyMasked);

        try {
            OpenAiChatResponse response = client.post()
                    .uri(CHAT_COMPLETIONS_PATH)
                    .body(request)
                    .retrieve()
                    .body(OpenAiChatResponse.class);

            if (response == null || response.choices() == null || response.choices().isEmpty()) {
                throw new LlmClientException(
                        LlmClientException.Reason.INVALID_RESPONSE,
                        "LLM response has no choices (stage=" + stage + ")");
            }

            OpenAiChatResponse.Choice first = response.choices().get(0);
            if (first.message() == null || first.message().content() == null) {
                throw new LlmClientException(
                        LlmClientException.Reason.INVALID_RESPONSE,
                        "LLM response choice missing message.content (stage=" + stage + ")");
            }

            String raw = toJsonSafely(response);
            log.debug("LLM 调用成功: stage={}, finishReason={}, tokens={}",
                    stage, first.finishReason(),
                    response.usage() != null ? response.usage().totalTokens() : null);

            return new LlmCallResult(
                    first.message().content(),
                    response.model() != null ? response.model() : model,
                    response.usage() != null ? response.usage().promptTokens() : null,
                    response.usage() != null ? response.usage().completionTokens() : null,
                    response.usage() != null ? response.usage().totalTokens() : null,
                    first.finishReason(),
                    raw);

        } catch (LlmClientException ex) {
            throw ex;
        } catch (RestClientResponseException ex) {
            // HTTP 4xx / 5xx — 含 status code 但不含 apiKey
            int status = ex.getStatusCode().value();
            LlmClientException.Reason reason = mapHttpStatus(status);
            log.warn("LLM 调用失败: stage={}, status={}, reason={}", stage, status, reason);
            throw new LlmClientException(reason,
                    "LLM HTTP " + status + " (stage=" + stage + ")", ex);

        } catch (ResourceAccessException ex) {
            // 网络层失败 — 超时是其中一种，区分靠 cause
            LlmClientException.Reason reason = isTimeout(ex)
                    ? LlmClientException.Reason.TIMEOUT
                    : LlmClientException.Reason.NETWORK;
            log.warn("LLM 网络失败: stage={}, reason={}, cause={}",
                    stage, reason, ex.getCause() != null ? ex.getCause().getClass().getSimpleName() : "n/a");
            throw new LlmClientException(reason,
                    "LLM " + reason + " (stage=" + stage + ")", ex);

        } catch (org.springframework.web.client.RestClientException ex) {
            // 部分 Spring 版本 / 配置下，IOException 会作为 RestClientException 而非 ResourceAccessException 抛出
            // 兜底：检查 cause chain 是否是超时，再决定 TIMEOUT 还是 INVALID_RESPONSE
            if (isTimeout(ex)) {
                log.warn("LLM 超时（RestClientException 路径）: stage={}", stage);
                throw new LlmClientException(
                        LlmClientException.Reason.TIMEOUT,
                        "LLM TIMEOUT (stage=" + stage + ")", ex);
            }
            log.warn("LLM 调用异常（RestClientException 兜底）: stage={}, type={}",
                    stage, ex.getClass().getSimpleName());
            throw new LlmClientException(
                    LlmClientException.Reason.INVALID_RESPONSE,
                    "LLM call failed (stage=" + stage + ")", ex);

        } catch (Exception ex) {
            // 兜底：JSON 反序列化失败 / 未知异常 → 视为 INVALID_RESPONSE
            // 仍检查是否超时，避免把超时误归到 INVALID_RESPONSE
            if (isTimeout(ex)) {
                log.warn("LLM 超时（兜底路径）: stage={}", stage);
                throw new LlmClientException(
                        LlmClientException.Reason.TIMEOUT,
                        "LLM TIMEOUT (stage=" + stage + ")", ex);
            }
            log.warn("LLM 调用异常: stage={}, type={}", stage, ex.getClass().getSimpleName());
            throw new LlmClientException(
                    LlmClientException.Reason.INVALID_RESPONSE,
                    "LLM call failed unexpectedly (stage=" + stage + ")", ex);
        }
    }

    private static LlmClientException.Reason mapHttpStatus(int status) {
        if (status == 401 || status == 403) {
            return LlmClientException.Reason.AUTH;
        }
        if (status == 429) {
            return LlmClientException.Reason.RATE_LIMITED;
        }
        if (status >= 500) {
            return LlmClientException.Reason.SERVER_ERROR;
        }
        // 4xx 其它（400/404 等）按 INVALID_RESPONSE 处理 — 上行请求结构不对
        return LlmClientException.Reason.INVALID_RESPONSE;
    }

    private static boolean isTimeout(Throwable ex) {
        Throwable cur = ex;
        while (cur != null) {
            if (cur instanceof SocketTimeoutException) {
                return true;
            }
            // SimpleClientHttpRequestFactory 在 read timeout 时抛 SocketTimeoutException
            // 其它 JDK HttpURLConnection 路径可能用 InterruptedIOException 包装
            String name = cur.getClass().getName();
            if (name.contains("TimeoutException") || name.contains("SocketTimeout")) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private String toJsonSafely(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    /**
     * API key mask：
     * <ul>
     *   <li>保留前 3 个字符（如 {@code sk-}）</li>
     *   <li>替换中间部分为 {@code ***}</li>
     *   <li>保留最后 4 个字符（便于识别 key 而非完全不可读）</li>
     * </ul>
     * 例：{@code sk-abcdef1234567890} → {@code sk-***7890}
     */
    static String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) {
            return "";
        }
        if (apiKey.length() <= 7) {
            return "***";
        }
        String prefix = apiKey.substring(0, 3);
        String suffix = apiKey.substring(apiKey.length() - 4);
        return prefix + "***" + suffix;
    }
}