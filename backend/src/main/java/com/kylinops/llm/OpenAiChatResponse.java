package com.kylinops.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * OpenAI Chat Completions API 响应体（兼容 DeepSeek / Qwen）。
 *
 * <p>所有未知字段被忽略（{@code @JsonIgnoreProperties(ignoreUnknown=true)}），
 * 兼容不同厂商返回的额外 metadata。{@code choices} 缺位或为空 → 视为
 * 无效响应，触发 {@link LlmClientException.Reason#INVALID_RESPONSE}。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAiChatResponse(
        @JsonProperty("id") String id,
        @JsonProperty("model") String model,
        @JsonProperty("choices") List<Choice> choices,
        @JsonProperty("usage") Usage usage) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(
            @JsonProperty("index") Integer index,
            @JsonProperty("message") Message message,
            @JsonProperty("finish_reason") String finishReason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(
            @JsonProperty("role") String role,
            @JsonProperty("content") String content) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Usage(
            @JsonProperty("prompt_tokens") Integer promptTokens,
            @JsonProperty("completion_tokens") Integer completionTokens,
            @JsonProperty("total_tokens") Integer totalTokens) {
    }
}