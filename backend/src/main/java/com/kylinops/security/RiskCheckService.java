package com.kylinops.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kylinops.agent.ToolPlanningService.ToolPlan;
import com.kylinops.agent.ToolPlanningService.ToolStep;
import com.kylinops.common.enums.RiskDecision;
import com.kylinops.common.enums.RiskLevel;
import com.kylinops.common.enums.ToolStatus;
import com.kylinops.tool.ToolDefinition;
import com.kylinops.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 风险校验服务
 * <p>
 * 对 Agent 编排的工具计划在 <b>执行前</b> 进行安全风险评估。
 * 编排内容风险规则引擎 + 工具定义风险等级，
 * 输出 ALLOW / CONFIRM / BLOCK 决策。
 * </p>
 *
 * <h3>执行顺序（从审计创建后开始）</h3>
 * <ol>
 *   <li>检测 Prompt 注入</li>
 *   <li>评估用户输入内容命中危险规则</li>
 *   <li>评估每个工具的风险等级</li>
 *   <li>合并内容风险和工具风险，取最高等级</li>
 *   <li>持久化 RiskCheckRecord</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RiskCheckService {

    private final ToolRegistry toolRegistry;
    private final PromptInjectionDetector injectionDetector;
    private final RiskRuleEngine riskRuleEngine;
    private final RiskCheckRecordRepository recordRepository;
    private final ObjectMapper objectMapper;

    /**
     * 对工具计划执行前置风险校验。
     * <p>
     * 风险校验发生在工具执行之前，是 Agent 安全闭环的决策边界。
     * </p>
     *
     * @param plan      工具调用计划
     * @param userInput 原始用户输入
     * @return 风险校验结果（永远不会返回 null）
     */
    public RiskCheckResult checkPlan(ToolPlan plan, String userInput, String auditId) {
        Objects.requireNonNull(auditId, "auditId must not be null");
        // 1. Prompt 注入检测
        PromptInjectionDetector.DetectionResult injection = injectionDetector.detect(userInput);
        if (injection.isInjectionDetected()) {
            log.warn("风险校验检测到 Prompt 注入: patterns={}", injection.getMatchedPatterns());
            RiskCheckResult result = RiskCheckResult.builder()
                    .riskLevel(injection.getRiskLevel())
                    .decision(RiskDecision.BLOCK)
                    .matchedRules(injection.getMatchedPatterns())
                    .reason(injection.getReason())
                    .safeSuggestion("系统已拦截该请求，请不要尝试绕过安全规则")
                    .build();
            persistCheck("prompt", injection.getReason(), result, auditId);
            return result;
        }

        // 2. 内容风险校验（用户输入/命令文本）
        RiskCheckResult contentResult = isDiscussionContext(userInput)
                ? RiskCheckResult.allow(RiskLevel.L0, "解释或讨论场景，不创建执行计划")
                : evaluateContent(userInput, auditId);

        // 3. 工具级风险校验
        RiskCheckResult toolResult = evaluateTools(plan);

        RiskCheckResult actionResult = evaluateAction(plan);

        // 4. 合并内容、工具和受控动作风险
        RiskCheckResult merged = mergeResults(mergeResults(contentResult, toolResult), actionResult);

        // 5. 持久化
        persistCheck("plan", plan.toString(), merged, auditId);

        log.info("前置风险校验完成: level={}, decision={}, rules={}",
                merged.getRiskLevel(), merged.getDecision(), merged.getMatchedRules());

        return merged;
    }

    public RiskCheckResult check(RiskEvaluationContext context, String auditId) {
        Objects.requireNonNull(auditId, "auditId must not be null");
        RiskCheckResult result;
        try {
            result = riskRuleEngine.evaluate(context);
        } catch (Exception e) {
            result = RiskCheckResult.block(RiskLevel.L3, List.of("risk_evaluation_error"),
                    "风险规则评估异常", "请稍后重试或联系管理员");
        }
        persistCheck(context.getTargetType(), context.getContent(), result, auditId);
        return result;
    }

    /**
     * 对用户输入内容执行风险规则评估。
     */
    private RiskCheckResult evaluateContent(String userInput, String auditId) {
        if (userInput == null || userInput.isBlank()) {
            return RiskCheckResult.allow(RiskLevel.L0, "无输入内容");
        }

        RiskEvaluationContext ctx = new RiskEvaluationContext("command", userInput, null, null);
        RiskCheckResult ruleResult = riskRuleEngine.evaluate(ctx);

        // 持久化内容级检查
        persistCheck("content", userInput, ruleResult, auditId);

        return ruleResult;
    }

    /**
     * 评估计划中所有工具的风险等级。
     * <p>
     * 未注册、禁用、查找失败、风险为 null 的工具统一返回 L3/BLOCK。
     * </p>
     */
    private RiskCheckResult evaluateTools(ToolPlan plan) {
        if (plan == null || plan.getSteps() == null || plan.getSteps().isEmpty()) {
            return RiskCheckResult.allow(RiskLevel.L0, "空计划");
        }

        RiskLevel maxToolLevel = RiskLevel.L0;
        boolean hasUnregistered = false;
        boolean hasDisabled = false;
        List<String> toolDetails = new ArrayList<>();

        for (ToolStep step : plan.getSteps()) {
            String toolName = step.getToolName();
            try {
                if (!toolRegistry.contains(toolName)) {
                    hasUnregistered = true;
                    toolDetails.add(toolName + "=unregistered");
                    continue;
                }
                ToolDefinition def = toolRegistry.getTool(toolName).definition();
                if (def.getToolStatus() != ToolStatus.ENABLED) {
                    hasDisabled = true;
                    toolDetails.add(toolName + "=disabled");
                    continue;
                }
                RiskLevel level = def.getRiskLevel();
                if (level == null) {
                    // null 风险等级视为 L3/BLOCK
                    toolDetails.add(toolName + "=null_risk");
                    if (RiskLevel.L3.ordinal() > maxToolLevel.ordinal()) {
                        maxToolLevel = RiskLevel.L3;
                    }
                    continue;
                }
                if (level.ordinal() > maxToolLevel.ordinal()) {
                    maxToolLevel = level;
                }
                toolDetails.add(toolName + "=" + level);
            } catch (Exception e) {
                log.warn("评估工具风险异常: toolName={}", toolName, e);
                toolDetails.add(toolName + "=error");
                if (RiskLevel.L3.ordinal() > maxToolLevel.ordinal()) {
                    maxToolLevel = RiskLevel.L3;
                }
            }
        }

        // 未注册工具 → L3/BLOCK
        if (hasUnregistered) {
            return RiskCheckResult.builder()
                    .riskLevel(RiskLevel.L3)
                    .decision(RiskDecision.BLOCK)
                    .matchedRules(List.of("unregistered_tool"))
                    .reason("计划包含未注册的工具，系统已自动拦截")
                    .safeSuggestion("请使用已注册的可用工具，或联系系统管理员注册新工具。")
                    .build();
        }

        // 禁用工具 → L3/BLOCK
        if (hasDisabled) {
            return RiskCheckResult.builder()
                    .riskLevel(RiskLevel.L3)
                    .decision(RiskDecision.BLOCK)
                    .matchedRules(List.of("disabled_tool"))
                    .reason("计划包含已被禁用的工具，系统已自动拦截")
                    .safeSuggestion("请在系统设置中启用相关工具后重试。")
                    .build();
        }

        // 根据工具风险等级做决策
        switch (maxToolLevel) {
            case L0:
                return RiskCheckResult.allow(RiskLevel.L0, "信息查询工具，无安全风险");
            case L1:
                return RiskCheckResult.allow(RiskLevel.L1, "轻度风险工具，已记录审计日志");
            case L2: {
                // 找到计划中第一个 L2 工具
                ToolStep firstL2 = plan.getSteps().stream()
                        .filter(s -> {
                            try {
                                if (!toolRegistry.contains(s.getToolName())) return false;
                                RiskLevel l = toolRegistry.getTool(s.getToolName()).definition().getRiskLevel();
                                return l == RiskLevel.L2;
                            } catch (Exception e) {
                                return false;
                            }
                        })
                        .findFirst().orElse(null);
                if (firstL2 != null) {
                    RiskCheckResult.PendingAction pa = RiskCheckResult.PendingAction.builder()
                            .actionId(UUID.randomUUID().toString())
                            .toolName(firstL2.getToolName())
                            .params(firstL2.getParams() != null ? firstL2.getParams() : Map.of())
                            .description("执行操作: " + firstL2.getToolName())
                            .build();
                    return RiskCheckResult.confirm(RiskLevel.L2,
                            "该操作存在中等风险，需用户确认后执行", "请确认是否继续执行该操作。", pa);
                }
                return RiskCheckResult.allow(RiskLevel.L0, "未找到 L2 工具");
            }
            case L3:
                return RiskCheckResult.block(RiskLevel.L3, List.of("tool_level_L3"),
                        "高风险工具，系统已自动拦截", "该操作可能对系统稳定性造成严重影响。");
            case L4:
                return RiskCheckResult.block(RiskLevel.L4, List.of("tool_level_L4"),
                        "严重风险工具，系统已自动拦截", "该操作已被系统禁止。");
            default:
                return RiskCheckResult.allow(RiskLevel.L0, "未识别到风险");
        }
    }

    private RiskCheckResult evaluateAction(ToolPlan plan) {
        if (plan == null || plan.getAction() == null) {
            return RiskCheckResult.allow(RiskLevel.L0, "无受控动作");
        }
        String actionType = plan.getAction().getActionType();
        RiskEvaluationContext context = new RiskEvaluationContext(
                "action", actionType, actionType, plan.getAction().getParams());
        return riskRuleEngine.evaluate(context);
    }

    /**
     * 合并内容风险和工具风险。
     * <p>
     * 取两者中更高的风险等级。同等级下按 BLOCK > CONFIRM > ALLOW。
     * </p>
     */
    private RiskCheckResult mergeResults(RiskCheckResult contentResult, RiskCheckResult toolResult) {
        RiskLevel contentLevel = contentResult.getRiskLevel();
        RiskLevel toolLevel = toolResult.getRiskLevel();

        // 取更高等级
        RiskLevel mergedLevel = contentLevel.ordinal() >= toolLevel.ordinal() ? contentLevel : toolLevel;

        // 收集所有匹配规则
        List<String> mergedRules = new ArrayList<>();
        if (contentResult.getMatchedRules() != null) mergedRules.addAll(contentResult.getMatchedRules());
        if (toolResult.getMatchedRules() != null) mergedRules.addAll(toolResult.getMatchedRules());

        // 合并决策
        RiskDecision mergedDecision;
        String mergedReason;
        String mergedSuggestion;
        RiskCheckResult.PendingAction mergedPending = null;

        if (contentLevel.ordinal() > toolLevel.ordinal()) {
            // 内容风险更高 — 使用内容风险的结果
            mergedDecision = contentResult.getDecision();
            mergedReason = contentResult.getReason();
            mergedSuggestion = contentResult.getSafeSuggestion();
        } else if (toolLevel.ordinal() > contentLevel.ordinal()) {
            // 工具风险更高 — 使用工具风险的结果
            mergedDecision = toolResult.getDecision();
            mergedReason = toolResult.getReason();
            mergedSuggestion = toolResult.getSafeSuggestion();
            mergedPending = toolResult.getPendingAction();
        } else {
            // 同级 — 取更严格的决策
            mergedDecision = stricterDecision(contentResult.getDecision(), toolResult.getDecision());
            mergedReason = contentResult.getReason() != null ? contentResult.getReason() : toolResult.getReason();
            mergedSuggestion = contentResult.getSafeSuggestion() != null
                    ? contentResult.getSafeSuggestion() : toolResult.getSafeSuggestion();
            mergedPending = toolResult.getPendingAction() != null ? toolResult.getPendingAction()
                    : contentResult.getPendingAction();
        }

        return RiskCheckResult.builder()
                .riskLevel(mergedLevel)
                .decision(mergedDecision)
                .matchedRules(mergedRules)
                .reason(mergedReason)
                .safeSuggestion(mergedSuggestion)
                .pendingAction(mergedPending)
                .build();
    }

    /**
     * 取更严格的决策: BLOCK > CONFIRM > ALLOW。
     */
    private RiskDecision stricterDecision(RiskDecision a, RiskDecision b) {
        if (a == RiskDecision.BLOCK || b == RiskDecision.BLOCK) return RiskDecision.BLOCK;
        if (a == RiskDecision.CONFIRM || b == RiskDecision.CONFIRM) return RiskDecision.CONFIRM;
        return RiskDecision.ALLOW;
    }

    /**
     * 持久化风险校验记录。
     */
    private void persistCheck(String targetType, String targetContent, RiskCheckResult result, String auditId) {
        RiskCheckRecord record = new RiskCheckRecord();
        record.setRiskCheckId(UUID.randomUUID().toString());
        record.setTargetType(targetType);
        record.setTargetContent(truncate(targetContent, 500));
        record.setRiskLevel(result.getRiskLevel());
        record.setRiskDecision(result.getDecision());
        record.setMatchedRules(toJson(result.getMatchedRules()));
        record.setReason(result.getReason());
        record.setSafeSuggestion(result.getSafeSuggestion());
        record.setAuditId(auditId);
        record.setCheckedAt(LocalDateTime.now());
        recordRepository.save(record);
    }

    // ==================== 工具方法 ====================

    private boolean isDiscussionContext(String input) {
        if (input == null) {
            return false;
        }
        String normalized = input.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("为什么")
                || normalized.startsWith("为何")
                || normalized.startsWith("解释")
                || normalized.startsWith("说明")
                || normalized.contains("是什么意思")
                || normalized.contains("文档");
    }

    private String toJson(Object obj) {
        if (obj == null) return "[]";
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return obj.toString();
        }
    }

    private String truncate(String str, int maxLen) {
        if (str == null) return null;
        return str.length() <= maxLen ? str : str.substring(0, maxLen) + "...";
    }
}
