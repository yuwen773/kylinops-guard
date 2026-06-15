package com.kylinops.audit;

/**
 * LLM 调用阶段（P3-T5）。
 *
 * <p>与 {@link com.kylinops.llm.LlmStage} 语义一致（INTENT / RESPONSE），
 * 独立枚举避免 audit 模块反向依赖 llm 模块。</p>
 */
public enum LlmCallStage {
    INTENT,
    RESPONSE
}