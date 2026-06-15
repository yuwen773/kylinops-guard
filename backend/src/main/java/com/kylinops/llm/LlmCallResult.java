package com.kylinops.llm;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * LLM 调用成功的统一返回结构。
 *
 * <p>{@link #content()} 是模型产出的正文（可能为空字符串但不为 null）；
 * 其余字段为可选元数据（不同厂商可能缺失）。</p>
 *
 * <p>{@link #raw} 字段仅记录响应 JSON 的脱敏文本（不含任何认证信息），
 * 供日志与审计追溯；调用方不应假设其内容稳定。</p>
 */
public record LlmCallResult(
        @JsonIgnore String content,
        String model,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        String finishReason,
        @JsonIgnore String raw) {

    public LlmCallResult(String content, String model,
                         Integer promptTokens, Integer completionTokens, Integer totalTokens,
                         String finishReason, String raw) {
        this.content = content == null ? "" : content;
        this.model = model;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
        this.finishReason = finishReason;
        this.raw = raw == null ? "" : raw;
    }
}