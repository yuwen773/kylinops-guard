package com.kylinops.agent.intelligence;

import com.kylinops.agent.IntentClassifier;
import com.kylinops.audit.AuditContextHolder;
import com.kylinops.audit.LlmCallStage;
import com.kylinops.common.enums.IntentType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;


/**
 * 混合意图识别服务（P3-T2）。
 *
 * <p>主入口：{@link #resolve(String)}，返回 {@link IntentResolution}。</p>
 *
 * <h3>解析策略</h3>
 * <ol>
 *   <li><strong>规则优先</strong>：调用 {@link IntentClassifier} 分类；命中非 UNKNOWN → 直接返回 source=RULE</li>
 *   <li><strong>LLM 后备</strong>：规则命中 UNKNOWN → 委托 {@link LlmIntentParser} 解析
 *       <ul>
 *         <li>解析成功 + 置信度 ≥ 阈值 → source=LLM</li>
 *         <li>解析失败 / 置信度不足 → source=FALLBACK, IntentType.UNKNOWN</li>
 *       </ul>
 *   </li>
 *   <li><strong>fail-closed</strong>：LLM 不可用（null LlmClient）或任意异常 → 不阻塞主链路</li>
 * </ol>
 *
 * <h3>安全约束</h3>
 * <ul>
 *   <li>LLM 不得覆盖 COMMAND_EXECUTION（由 {@link IntentTypeAllowlist} 强制）</li>
 *   <li>LLM 返回 params key 严格走 {@link IntentParamAllowlist}</li>
 *   <li>LLM 调用失败一律回退 UNKNOWN，<strong>不抛异常</strong></li>
 *   <li>LlmClient 注入用 {@code @Nullable}，LLM 关闭时仍能工作</li>
 * </ul>
 */
@Slf4j
@Service
public class HybridIntentService {

    private final IntentClassifier intentClassifier;
    private final LlmIntentParser llmParser;

    public HybridIntentService(IntentClassifier intentClassifier, LlmIntentParser llmParser) {
        this.intentClassifier = intentClassifier;
        this.llmParser = llmParser;
    }

    /**
     * 解析用户输入的意图。
     *
     * <p>永不返回 null；任意失败回退 {@link IntentResolution#fallback()}。</p>
     */
    public IntentResolution resolve(String userInput) {
        // 1) 规则优先
        IntentType ruleIntent = intentClassifier.classify(userInput);
        if (ruleIntent != null && ruleIntent != IntentType.UNKNOWN) {
            log.debug("意图识别走规则路径: input_len={}, intent={}",
                    inputLength(userInput), ruleIntent);
            return IntentResolution.ruleHit(ruleIntent);
        }

        // 2) LLM 后备 — P3-T5: 设置 stage=INTENT 让 AuditingLlmClient 正确归类
        LlmIntentParser.ParsedLlmIntent parsed;
        LlmCallStage previousStage = AuditContextHolder.getStage();
        AuditContextHolder.setStage(LlmCallStage.INTENT);
        try {
            parsed = llmParser.parse(userInput);
        } finally {
            // 恢复原 stage（避免污染外层调用方）
            if (previousStage != null) {
                AuditContextHolder.setStage(previousStage);
            } else {
                AuditContextHolder.setStage(null);
            }
        }
        if (parsed != null && parsed.isValid()) {
            log.info("意图识别走 LLM 路径: intent={}, confidence={}",
                    parsed.getIntent(), parsed.getConfidence());
            return IntentResolution.llmHit(parsed.getIntent(), parsed.getConfidence(),
                    parsed.getParams());
        }

        // 3) Fallback
        log.debug("意图识别回退 FALLBACK: input_len={}", inputLength(userInput));
        return IntentResolution.fallback();
    }

    private int inputLength(String input) {
        return input == null ? 0 : input.length();
    }
}
