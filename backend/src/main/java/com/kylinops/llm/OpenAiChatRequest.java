package com.kylinops.llm;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * OpenAI Chat Completions API 请求体。
 *
 * <p>遵循 OpenAI 官方 schema（兼容 DeepSeek / Qwen / 其它 OpenAI-compatible 实现）。</p>
 *
 * <p>{@code messages} 字段使用 {@code List<ChatMessage>} 而非
 * {@code List<Map<String, Object>>}，避免 Jackson 反序列化歧义
 * （参见 spec 关键契约清单）。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OpenAiChatRequest(
        @JsonProperty("model") String model,
        @JsonProperty("messages") List<ChatMessage> messages,
        @JsonProperty("stream") Boolean stream,
        @JsonProperty("temperature") Double temperature) {

    public OpenAiChatRequest(String model, List<ChatMessage> messages) {
        this(model, messages, false, null);
    }
}