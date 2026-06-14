package com.kylinops.agent.intelligence;

import com.kylinops.common.enums.IntentType;
import lombok.Getter;

import java.util.Collections;
import java.util.Map;

/**
 * 意图解析结果（P3-T2）。
 *
 * <p>封装意图分类的三要素：</p>
 * <ul>
 *   <li>{@code intentType} — 最终的意图枚举（永不返回 null）</li>
 *   <li>{@code confidence} — 置信度，规则命中固定为 1.0，LLM 路径来自模型输出</li>
 *   <li>{@code source} — 解析来源（RULE / LLM / FALLBACK），用于审计追溯</li>
 *   <li>{@code params} — 提取出的结构化参数（仅包含 allowlist 内的 key）</li>
 * </ul>
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>不可变：构造后字段不可变，便于安全传递到 AgentOrchestrator</li>
 *   <li>FALLBACK 时 confidence=0.0 + IntentType.UNKNOWN，调用方无需额外判断</li>
 *   <li>params 永远是有效 Map（不可变），永不为 null</li>
 * </ul>
 */
@Getter
public final class IntentResolution {

    /**
     * 解析来源。
     * <p>用于审计追溯，让审计日志能精确展示「这条意图来自规则还是 LLM」。</p>
     */
    public enum Source {
        /** 命中规则匹配 */
        RULE,
        /** LLM 解析成功且置信度达到阈值 */
        LLM,
        /** 规则未命中且 LLM 不可用 / 解析失败 / 置信度不足 */
        FALLBACK
    }

    private final IntentType intentType;
    private final double confidence;
    private final Source source;
    private final Map<String, Object> params;

    public IntentResolution(IntentType intentType, double confidence, Source source,
                            Map<String, Object> params) {
        this.intentType = intentType == null ? IntentType.UNKNOWN : intentType;
        this.confidence = clampConfidence(confidence);
        this.source = source == null ? Source.FALLBACK : source;
        this.params = params == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(params);
    }

    /**
     * 构造 FALLBACK 结果（IntentType.UNKNOWN, confidence=0.0, 空 params）。
     */
    public static IntentResolution fallback() {
        return new IntentResolution(IntentType.UNKNOWN, 0.0, Source.FALLBACK, Map.of());
    }

    /**
     * 构造规则命中结果。
     */
    public static IntentResolution ruleHit(IntentType intent) {
        return new IntentResolution(intent, 1.0, Source.RULE, Map.of());
    }

    /**
     * 构造 LLM 解析结果。
     */
    public static IntentResolution llmHit(IntentType intent, double confidence,
                                          Map<String, Object> params) {
        return new IntentResolution(intent, confidence, Source.LLM, params);
    }

    private static double clampConfidence(double c) {
        if (Double.isNaN(c) || Double.isInfinite(c)) return 0.0;
        if (c < 0.0) return 0.0;
        if (c > 1.0) return 1.0;
        return c;
    }
}
