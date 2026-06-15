package com.kylinops.audit;

/**
 * LLM 调用审计状态（P3-T5）。
 *
 * <ul>
 *   <li>{@link #SUCCESS} — 调用成功，返回有效 LlmCallResult</li>
 *   <li>{@link #DEGRADED} — 调用成功但结果有问题（如空 content）；目前未启用，保留扩展</li>
 *   <li>{@link #FAILED} — 调用抛异常（{@link com.kylinops.llm.LlmClientException}）</li>
 * </ul>
 */
public enum LlmCallStatus {
    SUCCESS,
    DEGRADED,
    FAILED
}