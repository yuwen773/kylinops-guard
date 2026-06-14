package com.kylinops.agent.intelligence;

import com.kylinops.common.enums.IntentType;

import java.util.EnumSet;
import java.util.Set;

/**
 * LLM 允许输出的 IntentType 白名单（P3-T2）。
 *
 * <p><strong>安全红线</strong>：</p>
 * <ul>
 *   <li>{@link IntentType#COMMAND_EXECUTION} 必须被排除 ——
 *       危险命令的识别必须由 {@code IntentClassifier} 规则路径完成，
 *       绝不允许 LLM 误判为安全意图后绕过 PromptInjectionDetector / RiskRuleEngine</li>
 *   <li>{@link IntentType#UNKNOWN} 排除 —— 模型无法直接输出"我不确定"；
 *       置信度不足时由调用方主动回退</li>
 * </ul>
 *
 * <p>LLM 仅作为规则未命中时的语义补充，最终安全决策永远由
 * {@code PromptInjectionDetector} + {@code RiskRuleEngine} 决定。</p>
 */
public final class IntentTypeAllowlist {

    /**
     * LLM 允许输出的 IntentType 集合。
     */
    public static final Set<IntentType> LLM_ALLOWED_INTENTS = EnumSet.of(
            IntentType.SYSTEM_CHECK,
            IntentType.DISK_DIAGNOSIS,
            IntentType.SERVICE_DIAGNOSIS,
            IntentType.PROCESS_QUERY,
            IntentType.NETWORK_QUERY,
            IntentType.FILE_OPERATION,
            IntentType.LOG_QUERY,
            IntentType.GENERAL_CHAT
    );

    private IntentTypeAllowlist() {
        // utility
    }

    /**
     * 判断给定 IntentType 是否允许 LLM 输出。
     */
    public static boolean isAllowed(IntentType intent) {
        return intent != null && LLM_ALLOWED_INTENTS.contains(intent);
    }
}
