package com.kylinops.agent.intelligence;

import com.kylinops.agent.AgentResponseBuilder;
import com.kylinops.audit.AuditContextHolder;
import com.kylinops.audit.LlmCallStage;
import com.kylinops.common.enums.IntentType;
import com.kylinops.common.enums.RiskDecision;
import com.kylinops.common.enums.RiskLevel;
import com.kylinops.llm.ChatMessage;
import com.kylinops.llm.LlmCallResult;
import com.kylinops.llm.LlmClient;
import com.kylinops.llm.LlmClientException;
import com.kylinops.llm.LlmStage;
import com.kylinops.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 混合回复生成服务（P3-T4）— 模板优先 + LLM 增强。
 *
 * <h3>设计目标</h3>
 * <ul>
 *   <li>LLM 永远不绕过 RiskCheck / ToolPlanner / 安全决策（BLOCK/CONFIRM/GENERAL_CHAT
 *       /UNKNOWN/空 results 一律走 {@link AgentResponseBuilder} 模板）</li>
 *   <li>LLM 失败/超时/缺 LlmClient → 立即回退模板，<strong>不阻塞主链路</strong></li>
 *   <li>LLM 输出必须经过 {@link ResponseFactValidator} — 拒绝编造数字 / 矛盾状态 /
 *       CONFIRM 路径"已执行"声明 / 非白名单命令</li>
 *   <li>注入 LLM 的工具结果必须经过 per-tool policy（{@link LlmToolContextPolicyRegistry}）
 *       二次 sanitization — 间接注入文本视为数据而非指令</li>
 * </ul>
 *
 * <h3>fail-closed 触发条件</h3>
 * <ol>
 *   <li>decision ∈ {BLOCK, CONFIRM}</li>
 *   <li>intent ∈ {GENERAL_CHAT, UNKNOWN}</li>
 *   <li>toolResults 为空</li>
 *   <li>任何工具缺 policy（{@link LlmToolContextPolicyRegistry#getPolicy} 返回 null）</li>
 *   <li>LlmClient 不可用（null）或调用抛异常</li>
 *   <li>{@link ResponseFactValidator} 校验失败</li>
 * </ol>
 *
 * <h3>安全红线</h3>
 * <ul>
 *   <li>本类不抛任何 RuntimeException 阻断主链路</li>
 *   <li>本类不修改 RiskDecision / RiskLevel / IntentType</li>
 *   <li>LLM 输出仅作为最终回复的"润色"层，不参与决策路径</li>
 * </ul>
 */
@Slf4j
@Service
public class HybridResponseService {

    /** 单个工具结果注入 LLM 的字节上限 */
    private static final int DEFAULT_MAX_BYTES = 4096;

    @Nullable
    private final LlmClient llmClient;

    private final LlmToolContextPolicyRegistry policyRegistry;
    private final LlmContextSanitizer sanitizer;
    private final ResponseFactValidator validator;
    private final AgentResponseBuilder fallbackBuilder;

    /**
     * 构造器（同时支持 Spring 装配和测试直构造）。
     * <p>允许传入 null {@code llmClient} 表示 LLM 关闭。</p>
     * <p>当 LlmClient bean 不存在（{@code kylinops.llm.enabled=false}）时，
     * 自动走"仅模板"路径。</p>
     */
    @Autowired
    public HybridResponseService(@Nullable LlmClient llmClient,
                                 LlmToolContextPolicyRegistry policyRegistry,
                                 LlmContextSanitizer sanitizer,
                                 ResponseFactValidator validator,
                                 AgentResponseBuilder fallbackBuilder) {
        this.llmClient = llmClient;
        this.policyRegistry = policyRegistry;
        this.sanitizer = sanitizer;
        this.validator = validator;
        this.fallbackBuilder = fallbackBuilder;
    }

    /**
     * 主入口：根据意图 / 工具结果 / 风险决策生成最终回复文本。
     *
     * <p><b>永不上抛异常</b>：任意失败 → 回退 AgentResponseBuilder.build()</p>
     *
     * @param intent     意图类型
     * @param results    工具执行结果列表（可为空）
     * @param decision   风险决策
     * @param riskReason 风险原因（仅用于 fallback 模板）
     * @param riskLevel  风险等级（仅用于 fallback 模板）
     * @return 最终回复文本
     */
    public String build(IntentType intent, List<ToolResult> results,
                        RiskDecision decision, String riskReason, RiskLevel riskLevel) {
        // 1) fail-closed 短路：BLOCK / CONFIRM / GENERAL_CHAT / UNKNOWN / 空 results
        if (shouldSkipLlm(intent, decision, results)) {
            log.debug("HybridResponseService 跳过 LLM 路径, 走模板: intent={}, decision={}, results.size={}",
                    intent, decision, results == null ? 0 : results.size());
            return fallbackBuilder.build(intent, results, decision, riskReason, riskLevel);
        }

        // 2) 缺 LlmClient → fail-closed
        if (llmClient == null) {
            log.debug("LlmClient 不可用, 走模板回复");
            return fallbackBuilder.build(intent, results, decision, riskReason, riskLevel);
        }

        try {
            // 3) 构造 sanitized context（按 per-tool policy）
            String sanitizedContext = buildSanitizedContext(results);
            if (sanitizedContext == null || sanitizedContext.isBlank()) {
                // 任一工具缺 policy → fail-closed
                log.warn("工具结果 sanitization 失败（缺 policy 或全部为空），走模板回复");
                return fallbackBuilder.build(intent, results, decision, riskReason, riskLevel);
            }

            // 4) 调用 LLM — P3-T5: 设置 stage=RESPONSE 让 AuditingLlmClient 正确归类
            List<ChatMessage> messages = buildMessages(intent, sanitizedContext);
            LlmCallStage previousStage = AuditContextHolder.getStage();
            AuditContextHolder.setStage(LlmCallStage.RESPONSE);
            LlmCallResult result;
            try {
                result = llmClient.complete(LlmStage.RESPONSE, messages);
            } finally {
                if (previousStage != null) {
                    AuditContextHolder.setStage(previousStage);
                } else {
                    AuditContextHolder.setStage(null);
                }
            }

            if (result == null || result.content() == null || result.content().isBlank()) {
                log.warn("LLM 返回空 content, 走模板回复");
                return fallbackBuilder.build(intent, results, decision, riskReason, riskLevel);
            }

            String llmOutput = result.content();

            // 5) 事实校验
            ResponseFactValidator.ValidationResult validation =
                    validator.validate(llmOutput, sanitizedContext, intent, decision);
            if (!validation.isValid()) {
                log.warn("LLM 输出未通过事实校验: reason={}, 走模板回复", validation.getReason());
                return fallbackBuilder.build(intent, results, decision, riskReason, riskLevel);
            }

            log.info("HybridResponseService 走 LLM 增强路径, 模型={}, tokens={}",
                    result.model(), result.totalTokens());
            return llmOutput;

        } catch (LlmClientException e) {
            log.warn("LLM 调用失败 (reason={}): 走模板回复, 不阻塞主链路", e.getReason());
            return fallbackBuilder.build(intent, results, decision, riskReason, riskLevel);
        } catch (RuntimeException e) {
            // 防御性：吞掉所有非预期异常，不阻塞主链路
            log.warn("HybridResponseService 抛未预期异常: {}, 走模板回复", e.getMessage());
            return fallbackBuilder.build(intent, results, decision, riskReason, riskLevel);
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 是否应跳过 LLM 路径。短路条件：
     * <ul>
     *   <li>decision == BLOCK / CONFIRM</li>
     *   <li>intent == GENERAL_CHAT / UNKNOWN</li>
     *   <li>results 为空或全为失败</li>
     * </ul>
     */
    private boolean shouldSkipLlm(IntentType intent, RiskDecision decision,
                                  List<ToolResult> results) {
        if (decision == RiskDecision.BLOCK || decision == RiskDecision.CONFIRM) {
            return true;
        }
        if (intent == IntentType.GENERAL_CHAT || intent == IntentType.UNKNOWN) {
            return true;
        }
        if (results == null || results.isEmpty()) {
            return true;
        }
        // 全部失败也不算有效输入 → 跳过 LLM
        boolean anySuccess = results.stream().anyMatch(ToolResult::isSuccess);
        if (!anySuccess) {
            return true;
        }
        return false;
    }

    /**
     * 按 per-tool policy sanitization 各工具结果，拼接成 LLM 可见的 context。
     * <p>任一工具缺 policy → 返回 null（调用方 fail-closed）。</p>
     */
    private String buildSanitizedContext(List<ToolResult> results) {
        StringBuilder sb = new StringBuilder();
        for (ToolResult tr : results) {
            if (tr == null) {
                continue;
            }
            String toolName = tr.getToolName();
            LlmToolContextPolicy policy = policyRegistry.getPolicy(toolName);
            if (policy == null) {
                // 缺 policy → fail-closed
                return null;
            }
            String sanitized = policy.sanitize(tr, DEFAULT_MAX_BYTES);
            // policy 已通过 AbstractLlmToolContextPolicy.sanitizeText() 调用 sanitizer；
            // 这里再二次 sanitize 作为 defense-in-depth
            sanitized = sanitizer.sanitize(sanitized);
            sb.append("[").append(toolName).append("]\n")
                    .append(sanitized).append("\n\n");
        }
        return sb.toString().trim();
    }

    /**
     * 构造 system + user messages。
     */
    private List<ChatMessage> buildMessages(IntentType intent, String sanitizedContext) {
        String systemPrompt = buildSystemPrompt(intent);
        String userPrompt = buildUserPrompt(sanitizedContext);
        List<ChatMessage> messages = new ArrayList<>(2);
        messages.add(ChatMessage.system(systemPrompt));
        messages.add(ChatMessage.user(userPrompt));
        return messages;
    }

    /**
     * System prompt — 明确告知 LLM：仅基于 context，禁止编造，禁止建议危险命令。
     */
    private String buildSystemPrompt(IntentType intent) {
        return "你是麒麟安全智能运维 Agent 的回复润色助手。"
                + "你必须且只能基于 <context> 标签内提供的工具结果数据生成回复。\n\n"
                + "硬性约束：\n"
                + "1. 数字（百分数/绝对值）必须来自 context — 禁止编造\n"
                + "2. 服务状态词（active/inactive/running/stopped/failed 等）必须与 context 一致\n"
                + "3. 不得声称已执行任何修改动作（重启/删除/清理等），只描述观测到的事实\n"
                + "4. 不得建议危险命令（rm -rf /、chmod -R 777、mkfs、dd if= 等）\n"
                + "5. 若给出具体命令建议，必须限于安全白名单（df -h、uptime、free -h、"
                + "systemctl status X、journalctl、ss -tulnp 等）\n"
                + "6. 回复保持中文，简洁专业\n\n"
                + "如果 context 不包含足够信息，请明确说明\"工具未提供 X 数据\"，不要自行推断。";
    }

    /**
     * User prompt — 注入 sanitized context。
     */
    private String buildUserPrompt(String sanitizedContext) {
        return "以下是工具返回的真实数据（已脱敏），请基于此生成回复：\n\n"
                + "<context>\n" + sanitizedContext + "\n</context>";
    }
}