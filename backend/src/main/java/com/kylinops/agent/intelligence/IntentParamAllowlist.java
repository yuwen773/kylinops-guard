package com.kylinops.agent.intelligence;

import com.kylinops.common.enums.IntentType;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * LLM 允许输出的参数 key 白名单（P3-T2）。
 *
 * <p>每个 IntentType 独立定义允许的参数 key 集合。
 * LLM 返回的 params 中，任何不在该 IntentType 白名单内的 key 一律丢弃。
 * 这避免 LLM 通过 params 注入未授权参数影响 ToolPlanningService。</p>
 *
 * <p><strong>设计原则</strong>：</p>
 * <ul>
 *   <li>最小授权：每个 IntentType 只暴露必需的参数 key</li>
 *   <li>与 ToolPlanningService 实际消费的 key 对齐</li>
 *   <li>规则路径提取的参数（PID、serviceName）不受此限制 ——
 *       它们的提取逻辑在 {@code AgentOrchestrator.extractParams} 中</li>
 * </ul>
 */
public final class IntentParamAllowlist {

    private static final Set<String> EMPTY = Set.of();
    private static final Set<String> SYSTEM_CHECK = Set.of("scope");
    private static final Set<String> DISK_DIAGNOSIS = Set.of("path", "topN");
    private static final Set<String> SERVICE_DIAGNOSIS = Set.of("serviceName", "operation");
    private static final Set<String> PROCESS_QUERY = Set.of("pid", "sortBy", "limit");
    private static final Set<String> NETWORK_QUERY = Set.of("port", "protocol", "state");
    private static final Set<String> FILE_OPERATION = Set.of("path", "operation", "previewOnly");
    private static final Set<String> LOG_QUERY = Set.of("path", "unit", "since", "level", "limit");
    private static final Set<String> GENERAL_CHAT = Set.of("topic");

    private static final Map<IntentType, Set<String>> ALLOWLIST;

    static {
        Map<IntentType, Set<String>> m = new HashMap<>();
        m.put(IntentType.SYSTEM_CHECK, SYSTEM_CHECK);
        m.put(IntentType.DISK_DIAGNOSIS, DISK_DIAGNOSIS);
        m.put(IntentType.SERVICE_DIAGNOSIS, SERVICE_DIAGNOSIS);
        m.put(IntentType.PROCESS_QUERY, PROCESS_QUERY);
        m.put(IntentType.NETWORK_QUERY, NETWORK_QUERY);
        m.put(IntentType.FILE_OPERATION, FILE_OPERATION);
        m.put(IntentType.LOG_QUERY, LOG_QUERY);
        m.put(IntentType.GENERAL_CHAT, GENERAL_CHAT);
        // COMMAND_EXECUTION / UNKNOWN 不在 LLM 白名单中 → 永远不暴露参数
        m.put(IntentType.COMMAND_EXECUTION, EMPTY);
        m.put(IntentType.UNKNOWN, EMPTY);
        ALLOWLIST = Collections.unmodifiableMap(m);
    }

    private IntentParamAllowlist() {
        // utility
    }

    /**
     * 获取 IntentType 对应的参数白名单。
     *
     * @param intent 意图类型
     * @param <V>    参数 value 类型（未使用，仅为类型签名一致性）
     * @return 允许的 key 集合（不可变）；COMMAND_EXECUTION/UNKNOWN 返回空集
     */
    public static Set<String> keysFor(IntentType intent) {
        return ALLOWLIST.getOrDefault(intent, EMPTY);
    }
}
