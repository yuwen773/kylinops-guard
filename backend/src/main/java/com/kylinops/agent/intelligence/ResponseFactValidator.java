package com.kylinops.agent.intelligence;

import com.kylinops.common.enums.IntentType;
import com.kylinops.common.enums.RiskDecision;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 事实校验器（P3-T4）— 防止 LLM 编造回复内容。
 *
 * <p><b>设计原则</b>：窄范围、结构化拒绝。校验维度仅覆盖
 * 演示视频脚本中可能导致"幻觉"的四类典型错误：</p>
 * <ol>
 *   <li>数字（百分数 / 绝对值 / 端口）必须来自 sanitized context</li>
 *   <li>服务状态词必须与 context 一致（不能把 inactive 说成 active）</li>
 *   <li>CONFIRM / BLOCK 路径不允许声称"已执行"动作</li>
 *   <li>命令建议必须命中安全白名单或根本不出现具体命令</li>
 * </ol>
 *
 * <p>本类不做 NLP 通用校验 — 那会引入额外脆弱性。
 * 仅校验可机器判定的事实。</p>
 */
@Component
public class ResponseFactValidator {

    /** 百分数（如 "45%" 或 "45.5%"） */
    private static final Pattern PERCENT_PATTERN = Pattern.compile(
            "\\b(\\d{1,3}(?:\\.\\d+)?)\\s*%");

    /** 端口 / 绝对数字（≥3 位），避免误伤 "1.2" "5ms" 等 */
    private static final Pattern ABSOLUTE_NUMBER_PATTERN = Pattern.compile(
            "\\b(\\d{3,})\\b");

    /** 服务状态词 */
    private static final List<String> SERVICE_STATE_WORDS = List.of(
            "active", "inactive", "running", "stopped", "failed",
            "enabled", "disabled", "dead", "unknown");

    /** 已被允许的状态词（"unknown" 视为允许但需校验数字等） */
    private static final Pattern SERVICE_STATE_PATTERN = Pattern.compile(
            "(?i)\\b(active|inactive|running|stopped|failed|enabled|disabled|dead|unknown)\\b");

    /** 已执行类强声明（CONFIRM/BLOCK 路径禁止出现） */
    private static final List<Pattern> EXECUTION_CLAIM_PATTERNS = List.of(
            Pattern.compile("(?i)已\\s*(?:重启|启动|停止|删除|清理|执行|修复|终止)"),
            Pattern.compile("(?i)(?:已|已经)\\s*(?:restarted|started|stopped|deleted|executed|fixed|killed)"),
            Pattern.compile("(?i)successfully\\s+(?:restarted|started|deleted|executed)"),
            Pattern.compile("(?i)重启\\s*(?:了|完成|成功)"),
            Pattern.compile("(?i)完成\\s*(?:了)?\\s*重启")
    );

    /** 安全命令建议白名单（精确匹配） */
    private static final List<Pattern> SAFE_COMMAND_PATTERNS = List.of(
            Pattern.compile("(?i)\\bdf\\s+-h\\b"),
            Pattern.compile("(?i)\\buptime\\b"),
            Pattern.compile("(?i)\\bfree\\s+-h\\b"),
            Pattern.compile("(?i)\\btop\\b"),
            Pattern.compile("(?i)\\bps\\s+aux\\b"),
            Pattern.compile("(?i)\\bsystemctl\\s+status\\s+[a-zA-Z0-9_.-]+"),
            Pattern.compile("(?i)\\bsystemctl\\s+is-active\\s+[a-zA-Z0-9_.-]+"),
            Pattern.compile("(?i)\\bsystemctl\\s+list-units\\b"),
            Pattern.compile("(?i)\\bjournalctl\\b"),
            Pattern.compile("(?i)\\bss\\s+-tulnp\\b"),
            Pattern.compile("(?i)\\bnetstat\\s+-tulnp\\b")
    );

    /** 危险命令锚点（绝对禁止作为行动建议） */
    private static final List<Pattern> DANGEROUS_COMMAND_PATTERNS = List.of(
            Pattern.compile("(?i)rm\\s+-rf?\\s+/"),
            Pattern.compile("(?i)rm\\s+-rf?\\s+\\*"),
            Pattern.compile("(?i)chmod\\s+-R\\s+777"),
            Pattern.compile("(?i)chown\\s+-R\\b"),
            Pattern.compile("(?i)\\bmkfs\\b"),
            Pattern.compile("(?i)\\bfdisk\\b"),
            Pattern.compile("(?i)\\bdd\\s+if="),
            Pattern.compile(":\\(\\)\\s*\\{\\s*:\\|:&\\s*\\};:"),
            Pattern.compile("(?i)\\b:\\(\\)\\s*\\{")
    );

    /**
     * 校验 LLM 输出。
     *
     * @param llmOutput         LLM 输出的回复文本
     * @param sanitizedContext  已 sanitized 的工具结果上下文（注入 LLM 的字符串）
     * @param intent            当前意图
     * @param decision          当前风险决策
     * @return 校验结果（valid=true 表示通过）
     */
    public ValidationResult validate(String llmOutput, String sanitizedContext,
                                     IntentType intent, RiskDecision decision) {
        if (llmOutput == null || llmOutput.isBlank()) {
            return ValidationResult.fail("LLM 输出为空");
        }
        if (sanitizedContext == null) {
            sanitizedContext = "";
        }

        // 1) 数字必须来自 context
        String reason = checkNumbers(llmOutput, sanitizedContext);
        if (reason != null) {
            return ValidationResult.fail(reason);
        }

        // 2) 服务状态词必须与 context 一致（且 LLM 必须引述与 context 完全相同的状态）
        reason = checkServiceStateWords(llmOutput, sanitizedContext);
        if (reason != null) {
            return ValidationResult.fail(reason);
        }

        // 3) 任何路径都禁止"已执行"声明（LLM 无权声称已修改系统状态 — 那是 CONFIRM 执行后才发生的事）
        reason = checkExecutionClaims(llmOutput);
        if (reason != null) {
            return ValidationResult.fail(reason);
        }

        // 4) 危险命令不应作为行动建议
        reason = checkDangerousCommands(llmOutput);
        if (reason != null) {
            return ValidationResult.fail(reason);
        }

        // 5) 若包含具体命令建议，必须命中安全白名单
        reason = checkCommandSuggestions(llmOutput);
        if (reason != null) {
            return ValidationResult.fail(reason);
        }

        return ValidationResult.pass();
    }

    // ==================== 校验子项 ====================

    /**
     * 提取 LLM 输出中的所有百分数和绝对数字，每个都必须出现在 sanitizedContext 中。
     * 若 LLM 输出未提及任何数字 → 通过。
     */
    private String checkNumbers(String llmOutput, String sanitizedContext) {
        Set<String> contextNumbers = extractNumbersFromContext(sanitizedContext);

        Matcher pctMatcher = PERCENT_PATTERN.matcher(llmOutput);
        while (pctMatcher.find()) {
            String num = pctMatcher.group(1);
            if (!contextNumbers.contains(num)) {
                return "数字 " + num + "% 不在 context 中（疑似编造）";
            }
        }

        Matcher absMatcher = ABSOLUTE_NUMBER_PATTERN.matcher(llmOutput);
        while (absMatcher.find()) {
            String num = absMatcher.group(1);
            // 一些 LLM 输出包含日期/版本（如 "2024"），不强制校验；仅当 context 中也含此数才算合规
            // 这里采取宽松策略：context 没有此数 → 拒绝
            if (!contextNumbers.contains(num)) {
                return "数字 " + num + " 不在 context 中（疑似编造）";
            }
        }

        return null;
    }

    private Set<String> extractNumbersFromContext(String context) {
        Set<String> numbers = new LinkedHashSet<>();
        Matcher m1 = PERCENT_PATTERN.matcher(context);
        while (m1.find()) {
            numbers.add(m1.group(1));
        }
        Matcher m2 = ABSOLUTE_NUMBER_PATTERN.matcher(context);
        while (m2.find()) {
            numbers.add(m2.group(1));
        }
        return numbers;
    }

    /**
     * 服务状态词必须与 context 一致，且 LLM 不能声称互斥的状态。
     * <p>校验规则：</p>
     * <ul>
     *   <li>若 LLM 输出含状态词 active/inactive/running/stopped/failed/enabled/disabled，
     *       该词必须作为独立词出现在 sanitizedContext 中（用 {@code \\b} 边界）</li>
     *   <li>若 LLM 同时输出 active 和 inactive（或 running 和 stopped 等） → 矛盾</li>
     * </ul>
     */
    private String checkServiceStateWords(String llmOutput, String sanitizedContext) {
        // 互斥对
        String[][] mutex = {
                {"active", "inactive"},
                {"running", "stopped"},
                {"enabled", "disabled"}
        };
        for (String[] pair : mutex) {
            boolean inOutput = containsWord(llmOutput, pair[0]);
            boolean inOutputAlt = containsWord(llmOutput, pair[1]);
            if (inOutput && inOutputAlt) {
                return "状态词 " + pair[0] + " 与 " + pair[1] + " 互斥";
            }
            // LLM 声称状态 a，但 context 不含独立词 a → 矛盾
            if (inOutput && !containsWord(sanitizedContext, pair[0])) {
                return "状态词 " + pair[0] + " 不在 context 中（疑似编造）";
            }
            if (inOutputAlt && !containsWord(sanitizedContext, pair[1])) {
                return "状态词 " + pair[1] + " 不在 context 中（疑似编造）";
            }
        }

        // 其他状态词（failed/dead）只要不在 context 中就拒绝
        Matcher m = SERVICE_STATE_PATTERN.matcher(llmOutput);
        while (m.find()) {
            String word = m.group(1).toLowerCase(Locale.ROOT);
            if (isMutexMember(word)) {
                continue;
            }
            if (!containsWord(sanitizedContext, word)) {
                return "状态词 " + word + " 与 context 矛盾";
            }
        }
        return null;
    }

    private boolean containsWord(String text, String word) {
        if (text == null || word == null) return false;
        String pattern = "(?i)\\b" + java.util.regex.Pattern.quote(word) + "\\b";
        return Pattern.compile(pattern).matcher(text).find();
    }

    private boolean isMutexMember(String word) {
        return word.equals("active") || word.equals("inactive")
                || word.equals("running") || word.equals("stopped")
                || word.equals("enabled") || word.equals("disabled");
    }

    /**
     * CONFIRM / BLOCK 路径下，LLM 不应声称已执行任何动作。
     */
    private String checkExecutionClaims(String llmOutput) {
        for (Pattern p : EXECUTION_CLAIM_PATTERNS) {
            if (p.matcher(llmOutput).find()) {
                return "CONFIRM/BLOCK 路径下不应出现已执行声明";
            }
        }
        return null;
    }

    /**
     * 检测危险命令（即使被嵌入到建议中也不允许）。
     */
    private String checkDangerousCommands(String llmOutput) {
        for (Pattern p : DANGEROUS_COMMAND_PATTERNS) {
            if (p.matcher(llmOutput).find()) {
                return "回复中包含禁止的危险命令锚点";
            }
        }
        return null;
    }

    /**
     * 检测"具体命令建议" — 若 LLM 输出形如 `$ xxx` 或 "请执行 xxx" 等引导性短语，
     * 后跟的命令必须命中白名单。
     */
    private String checkCommandSuggestions(String llmOutput) {
        // 仅当 LLM 输出明显给出"执行"或"建议运行"等引导语句时，才校验命令
        Pattern suggestPattern = Pattern.compile(
                "(?i)(?:(?:请|建议|可以|尝试)?\\s*(?:执行|运行|输入|使用)\\s+"
                        + "|[$`]\\s*)([^\\s`$]+(?:\\s+[^\\s`$]+){0,3})");
        Matcher m = suggestPattern.matcher(llmOutput);
        while (m.find()) {
            String command = m.group(1).trim();
            if (looksLikeShellCommand(command)) {
                if (!matchesSafeCommand(command)) {
                    return "回复中给出非白名单命令建议: " + command;
                }
            }
        }
        return null;
    }

    private boolean looksLikeShellCommand(String text) {
        if (text == null || text.isBlank()) return false;
        String first = text.split("\\s+")[0];
        // 常见 shell 命令前缀
        return first.matches("[a-zA-Z]{2,}");
    }

    private boolean matchesSafeCommand(String command) {
        for (Pattern p : SAFE_COMMAND_PATTERNS) {
            if (p.matcher(command).find()) {
                return true;
            }
        }
        return false;
    }

    // ==================== 校验结果 ====================

    /**
     * 校验结果。
     */
    public static final class ValidationResult {
        private final boolean valid;
        private final String reason;

        private ValidationResult(boolean valid, String reason) {
            this.valid = valid;
            this.reason = reason;
        }

        public static ValidationResult pass() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult fail(String reason) {
            return new ValidationResult(false, reason);
        }

        public boolean isValid() {
            return valid;
        }

        public String getReason() {
            return reason;
        }
    }
}