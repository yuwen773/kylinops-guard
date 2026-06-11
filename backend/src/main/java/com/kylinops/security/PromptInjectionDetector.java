package com.kylinops.security;

import com.kylinops.common.enums.RiskLevel;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Prompt 注入检测器
 * <p>
 * 在意图识别之前调用，检测用户输入中是否包含 Prompt 注入攻击模式。
 * 检测规则包括：权限提升、安全绕过、审计绕过、跳过确认等模式。
 * 一旦检测到注入，直接阻断请求，不经过意图识别和工具调用流程。
 * </p>
 *
 * <h3>检测规则</h3>
 * <ul>
 *   <li>忽略规则 — "忽略之前所有规则"、"忽略所有指令"</li>
 *   <li>权限提升 — "你现在是 root"、"扮演 root"</li>
 *   <li>审计绕过 — "不要审计"、"跳过审计"</li>
 *   <li>确认绕过 — "无需确认"、"直接执行"</li>
 *   <li>安全关闭 — "关闭安全校验"、"绕过权限限制"</li>
 * </ul>
 *
 * <h3>讨论/引用语境豁免</h3>
 * <p>
 * "为什么不能直接执行 rm -rf /"、"解释'直接执行'是什么意思"、"文档写着直接执行"
 * 属于讨论或引用语境。检测器在匹配确认绕过规则前，先识别明确的讨论语境前缀，
 * 避免误判。豁免仅影响 Prompt Injection 结果，不影响命令风险规则引擎。
 * </p>
 *
 * <h3>安全约束</h3>
 * <ul>
 *   <li>检测结果直接决定请求是否继续，不允许绕过</li>
 *   <li>所有命中规则均记录 warn 日志</li>
 *   <li>L4 级别的注入直接阻断，不执行任何后续操作</li>
 * </ul>
 */
@Slf4j
@Component
public class PromptInjectionDetector {

    private final List<InjectionRule> rules = new ArrayList<>();

    /** 讨论/引用语境前缀模式：命中后豁免确认绕过规则 */
    private static final List<Pattern> DISCUSSION_CONTEXT_PATTERNS = List.of(
            Pattern.compile("^为什么"),
            Pattern.compile("^解释"),
            Pattern.compile(".*是什么意思$"),
            Pattern.compile(".*说的"),
            Pattern.compile(".*写着"),
            Pattern.compile("^如果.*要.*需要"),
            Pattern.compile("^能否.*解释"),
            Pattern.compile("^请问"),
            Pattern.compile(".*是不是说"),
            Pattern.compile(".*怎么理解")
    );

    /** 确认绕过规则名称 — 当命中讨论语境时，这些规则被豁免 */
    private static final List<String> CONFIRM_BYPASS_RULE_NAMES = List.of(
            "prompt_injection_no_confirm"
    );

    public PromptInjectionDetector() {
        initRules();
    }

    /**
     * 初始化注入检测规则
     */
    private void initRules() {
        // L4 — 忽略规则 / 指令替换
        rules.add(new InjectionRule("prompt_injection_ignore",
                Pattern.compile("忽略(之前所有|所有|全部)?(规则|指令)"),
                "检测到 Prompt 注入攻击「忽略规则」", RiskLevel.L4));

        // L4 — 角色扮演 / 权限提升
        rules.add(new InjectionRule("prompt_injection_root",
                Pattern.compile("你现在是(系统)?root|扮演.*root|以root身份|你是root"),
                "检测到 Prompt 注入攻击「权限提升」", RiskLevel.L4));

        // L4 — 审计绕过
        rules.add(new InjectionRule("prompt_injection_no_audit",
                Pattern.compile("不要审计|跳过审计|不记录日志|无需(审计|记录)|不(要|用)审计"),
                "检测到 Prompt 注入攻击「跳过审计」", RiskLevel.L4));

        // L3 — 确认绕过
        rules.add(new InjectionRule("prompt_injection_no_confirm",
                Pattern.compile("无需确认|自动确认|直接执行|不要(问我|提示|确认)|不用确认|不需要确认"),
                "检测到 Prompt 注入攻击「跳过确认」", RiskLevel.L3));

        // L4 — 安全校验关闭
        rules.add(new InjectionRule("prompt_injection_disable_security",
                Pattern.compile("关闭(安全|所有)?(校验|检查|保护)|绕过(安全|权限)(限制|检查|校验)"),
                "检测到 Prompt 注入攻击「关闭安全校验」", RiskLevel.L4));

        // L4 — 通用绕过
        rules.add(new InjectionRule("prompt_injection_bypass",
                Pattern.compile("绕过.*(限制|检查|规则)|忽略.*(检查|安全|规则)"),
                "检测到 Prompt 注入攻击「绕过限制」", RiskLevel.L4));

        log.info("PromptInjectionDetector 初始化完成: 共 {} 条规则", rules.size());
    }

    /**
     * 检测用户输入中是否包含 Prompt 注入攻击。
     *
     * @param userInput 用户输入
     * @return 检测结果（永远不会返回 null）
     */
    public DetectionResult detect(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return DetectionResult.builder()
                    .injectionDetected(false)
                    .matchedPatterns(List.of())
                    .riskLevel(RiskLevel.L0)
                    .reason("")
                    .build();
        }

        List<String> matchedPatterns = new ArrayList<>();
        RiskLevel maxLevel = RiskLevel.L0;
        StringBuilder reasonBuilder = new StringBuilder();

        // 判断是否为讨论/引用语境
        boolean isDiscussionContext = isDiscussionOrQuotation(userInput);

        for (InjectionRule rule : rules) {
            if (rule.pattern().matcher(userInput).find()) {
                // 讨论语境下豁免确认绕过规则
                if (isDiscussionContext && CONFIRM_BYPASS_RULE_NAMES.contains(rule.ruleName())) {
                    log.debug("讨论语境豁免确认绕过规则: rule={}, input='{}'",
                            rule.ruleName(), truncate(userInput, 80));
                    continue;
                }

                matchedPatterns.add(rule.ruleName());
                if (rule.riskLevel().ordinal() > maxLevel.ordinal()) {
                    maxLevel = rule.riskLevel();
                }
                if (!reasonBuilder.isEmpty()) {
                    reasonBuilder.append("；");
                }
                reasonBuilder.append(rule.description());
                log.warn("Prompt 注入检测命中: rule={}, input='{}'", rule.ruleName(), truncate(userInput, 80));
            }
        }

        boolean detected = !matchedPatterns.isEmpty();
        if (detected) {
            log.warn("Prompt 注入检测结果: detected={}, patterns={}, level={}",
                    detected, matchedPatterns, maxLevel);
        }

        return DetectionResult.builder()
                .injectionDetected(detected)
                .matchedPatterns(matchedPatterns)
                .riskLevel(detected ? maxLevel : RiskLevel.L0)
                .reason(reasonBuilder.toString())
                .build();
    }

    /**
     * 判断输入是否为讨论或引用语境。
     * <p>
     * 检查输入是否以讨论前缀开头或包含引号包裹的命令短语。
     * </p>
     */
    private boolean isDiscussionOrQuotation(String input) {
        if (input == null || input.isBlank()) return false;

        String trimmed = input.trim();

        // 检查讨论语境前缀
        for (Pattern pattern : DISCUSSION_CONTEXT_PATTERNS) {
            if (pattern.matcher(trimmed).find()) {
                return true;
            }
        }

        // 检查引号引用（如 '直接执行'、"直接执行"）
        if (trimmed.contains("'直接执行") || trimmed.contains("\"直接执行")
                || trimmed.contains("'忽略") || trimmed.contains("\"忽略")) {
            return true;
        }

        return false;
    }

    /**
     * 截断长字符串用于日志
     */
    private String truncate(String str, int maxLen) {
        if (str == null) return null;
        return str.length() <= maxLen ? str : str.substring(0, maxLen) + "...";
    }

    // ==================== 内部类型 ====================

    /**
     * 注入检测结果
     */
    @Data
    @Builder
    public static class DetectionResult {
        /** 是否检测到注入 */
        private final boolean injectionDetected;

        /** 命中的规则名称列表 */
        private final List<String> matchedPatterns;

        /** 风险等级（未命中为 L0） */
        private final RiskLevel riskLevel;

        /** 人类可读的检测原因 */
        private final String reason;
    }

    /**
     * 注入检测规则
     */
    private record InjectionRule(String ruleName, Pattern pattern, String description, RiskLevel riskLevel) {
    }
}
