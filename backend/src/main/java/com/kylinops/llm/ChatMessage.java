package com.kylinops.llm;

/**
 * OpenAI 风格对话消息。
 *
 * <p>采用 record 以保证不可变；{@code role} 与 {@code content} 是 OpenAI
 * Chat Completions API 的官方字段名，Jackson 默认即可正确序列化，
 * 无需显式 {@code @JsonProperty}。</p>
 */
public record ChatMessage(String role, String content) {

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content);
    }
}