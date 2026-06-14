package com.kylinops.llm;

/**
 * LLM 调用阶段。
 *
 * <p>不同阶段使用不同的超时与日志语义：</p>
 * <ul>
 *   <li>{@link #INTENT} — 意图识别，超时短（默认 3s），失败回退规则匹配</li>
 *   <li>{@link #RESPONSE} — 回复生成，超时略长（默认 5s），失败回退模板</li>
 * </ul>
 */
public enum LlmStage {
    INTENT,
    RESPONSE
}