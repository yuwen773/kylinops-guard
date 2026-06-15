package com.kylinops.agent.intelligence;

import com.kylinops.tool.OpsTool;
import com.kylinops.tool.ToolRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 工具上下文策略注册中心 (P3-T3).
 *
 * <p>启动时：</p>
 * <ol>
 *   <li>扫描 Spring 容器中所有 {@link LlmToolContextPolicy} bean</li>
 *   <li>扫描 {@link ToolRegistry} 中所有 OpsTool bean</li>
 *   <li>校验：每个 OpsTool 都有 policy → 否则启动失败（fail-closed）</li>
 * </ol>
 *
 * <p>{@link #getPolicy(String)}：</p>
 * <ul>
 *   <li>存在 → 返回 policy</li>
 *   <li>不存在（未注册工具） → 返回 null；由调用方判定是否 fail-closed</li>
 * </ul>
 */
@Slf4j
@Component
public class LlmToolContextPolicyRegistry {

    /**
     * 不参与 policy 校验的工具名前缀（测试 / Mock 工具）。
     * <p>这些工具的输出永远不会注入 LLM（Mock 仅供开发自测），因此不需要 policy。</p>
     */
    private static final List<String> EXCLUDED_TOOL_PREFIXES = List.of(
            "mock_", "failing_"
    );

    private final List<LlmToolContextPolicy> policies;
    private final ToolRegistry toolRegistry;
    private final Map<String, LlmToolContextPolicy> policyMap = new LinkedHashMap<>();

    public LlmToolContextPolicyRegistry(List<LlmToolContextPolicy> policies,
                                        ToolRegistry toolRegistry) {
        this.policies = policies;
        this.toolRegistry = toolRegistry;
    }

    @PostConstruct
    public void init() {
        // 1. 按 toolName 索引所有 policy
        for (LlmToolContextPolicy policy : policies) {
            LlmToolContextPolicy existing = policyMap.put(policy.toolName(), policy);
            if (existing != null) {
                log.warn("工具 [{}] 的 LlmToolContextPolicy 被覆盖: {} -> {}",
                        policy.toolName(), existing.getClass().getSimpleName(),
                        policy.getClass().getSimpleName());
            }
        }

        // 2. 校验每个已注册 OpsTool 都有 policy（排除 Mock / 测试工具）
        List<String> missingPolicies = new ArrayList<>();
        for (OpsTool tool : toolRegistry.getAllToolDefinitions().stream()
                .map(def -> toolRegistry.getTool(def.getToolName()))
                .collect(Collectors.toList())) {
            String toolName = tool.definition().getToolName();
            if (isExcluded(toolName)) {
                continue;
            }
            if (!policyMap.containsKey(toolName)) {
                missingPolicies.add(toolName);
            }
        }

        if (!missingPolicies.isEmpty()) {
            String msg = "以下 OpsTool 缺少 LlmToolContextPolicy（fail-closed）: " + missingPolicies;
            log.error(msg);
            throw new IllegalStateException(msg);
        }

        log.info("LlmToolContextPolicyRegistry 初始化完成: 共 {} 个策略（已校验所有生产 OpsTool）",
                policyMap.size());
        if (log.isDebugEnabled()) {
            log.debug("已注册策略: {}", policyMap.keySet());
        }
    }

    private boolean isExcluded(String toolName) {
        for (String prefix : EXCLUDED_TOOL_PREFIXES) {
            if (toolName != null && toolName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 按 toolName 获取 policy。
     *
     * @param toolName 工具名称
     * @return policy；未注册返回 null（由调用方判 fail-closed）
     */
    public LlmToolContextPolicy getPolicy(String toolName) {
        if (toolName == null) {
            return null;
        }
        return policyMap.get(toolName);
    }

    /**
     * 按 toolName 获取 policy（Optional 形式）。
     */
    public Optional<LlmToolContextPolicy> findPolicy(String toolName) {
        return Optional.ofNullable(getPolicy(toolName));
    }

    /**
     * 返回所有已注册策略的工具名称（不可变视图）。
     */
    public List<String> getRegisteredToolNames() {
        return Collections.unmodifiableList(new ArrayList<>(policyMap.keySet()));
    }

    /**
     * 返回已注册策略数量。
     */
    public int count() {
        return policyMap.size();
    }
}