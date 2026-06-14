package com.kylinops.agent.intelligence;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 上下文脱敏器 (P3-T3).
 *
 * <p>处理三类敏感信息：</p>
 * <ol>
 *   <li><b>API key / token</b>：Bearer / sk- 前缀 / password= / token= 形式</li>
 *   <li><b>间接注入文本</b>：工具输出中夹带的指令覆盖类语句</li>
 *   <li><b>环境变量值</b>：$HOME / %PATH% / ${VAR} 形式</li>
 * </ol>
 *
 * <p>所有测试用 fake token 字符串；本类不硬编码任何真实密钥。</p>
 *
 * <h3>fail-safe</h3>
 * <p>任意输入异常 → 返回原文或空字符串，永不抛 RuntimeException 阻断主链路。</p>
 */
@Component
public class LlmContextSanitizer {

    /** Bearer token（"Authorization: Bearer xxx" 或裸 "Bearer xxx"） */
    private static final Pattern BEARER_PATTERN = Pattern.compile(
            "(?i)Bearer\\s+[A-Za-z0-9._\\-+/=]+");

    /** OpenAI / DeepSeek 风格 key（sk- 前缀） */
    private static final Pattern OPENAI_KEY_PATTERN = Pattern.compile(
            "\\bsk-[A-Za-z0-9\\-]{20,}\\b");

    /** password=xxx 形式 */
    private static final Pattern PASSWORD_PATTERN = Pattern.compile(
            "(?i)(password\\s*=\\s*)([^\\s&;,]+)");

    /** token=xxx 形式 */
    private static final Pattern TOKEN_PATTERN = Pattern.compile(
            "(?i)(token\\s*=\\s*)([^\\s&;,]+)");

    /** 间接注入：中文 */
    private static final Pattern INJECTION_CN_PATTERN = Pattern.compile(
            "(?i)忽略[\\s\\S]{0,8}(以上|之前|先前|上述)[\\s\\S]{0,8}(指令|规则|说明|指示|所有)");

    /** 间接注入：英文 */
    private static final Pattern INJECTION_EN_PATTERN = Pattern.compile(
            "(?i)(?:disregard|ignore|forget)\\s+(?:the\\s+)?(?:above|previous|prior)\\s+(?:instructions?|rules?|context|directives?)");

    /** 间接注入：system prompt override */
    private static final Pattern INJECTION_OVERRIDE_PATTERN = Pattern.compile(
            "(?i)(?:system\\s+prompt\\s+override|new\\s+(?:system\\s+)?instructions?|you\\s+are\\s+now\\s+root)");

    /** Unix 风格环境变量：${VAR}=val 或 $VAR=val（大括号形式优先） */
    private static final Pattern ENV_UNIX_PATTERN = Pattern.compile(
            "(?:\\$\\{([A-Z_][A-Z0-9_]*)|\\$([A-Z_][A-Z0-9_]*))\\s*=\\s*([^\\s;,}]+)");

    /** Windows 风格环境变量：%VAR% */
    private static final Pattern ENV_WIN_PATTERN = Pattern.compile(
            "%([A-Z_][A-Z0-9_]*)%\\s*=\\s*([^\\s;,]+)");

    private static final String REDACTED_BEARER = "[REDACTED-BEARER]";
    private static final String REDACTED_OPENAI_KEY = "[REDACTED-OPENAI-KEY]";
    private static final String REDACTED_PASSWORD = "[REDACTED-PASSWORD]";
    private static final String REDACTED_TOKEN = "[REDACTED-TOKEN]";
    private static final String REDACTED_INJECTION = "[SANITIZED-INJECTION-ATTEMPT]";
    private static final String REDACTED_ENV_VALUE = "[REDACTED-ENV]";

    /**
     * 对输入文本执行全部脱敏规则。
     *
     * <p>处理顺序：API key → 间接注入 → 环境变量值。</p>
     *
     * @param text 原始文本（可空）
     * @return 脱敏后文本；null 输入返回 null；空字符串返回空字符串
     */
    public String sanitize(String text) {
        if (text == null) {
            return null;
        }
        if (text.isEmpty()) {
            return "";
        }

        String result = text;

        // 1. Bearer token
        result = BEARER_PATTERN.matcher(result).replaceAll(REDACTED_BEARER);

        // 2. OpenAI 风格 key
        result = OPENAI_KEY_PATTERN.matcher(result).replaceAll(REDACTED_OPENAI_KEY);

        // 3. password=xxx（保留 key，脱敏 value；quoteReplacement 避免 $ 误解析）
        result = PASSWORD_PATTERN.matcher(result).replaceAll(
                Matcher.quoteReplacement("$1") + REDACTED_PASSWORD);

        // 4. token=xxx（保留 key，脱敏 value）
        result = TOKEN_PATTERN.matcher(result).replaceAll(
                Matcher.quoteReplacement("$1") + REDACTED_TOKEN);

        // 5. 间接注入：中文
        result = INJECTION_CN_PATTERN.matcher(result).replaceAll(REDACTED_INJECTION);

        // 6. 间接注入：英文
        result = INJECTION_EN_PATTERN.matcher(result).replaceAll(REDACTED_INJECTION);

        // 7. 间接注入：system prompt override
        result = INJECTION_OVERRIDE_PATTERN.matcher(result).replaceAll(REDACTED_INJECTION);

        // 8. Unix 环境变量值（保留变量名）
        result = ENV_UNIX_PATTERN.matcher(result).replaceAll(replaceEnvValue());

        // 9. Windows 环境变量值
        result = ENV_WIN_PATTERN.matcher(result).replaceAll(replaceWinEnvValue());

        return result;
    }

    private java.util.function.Function<java.util.regex.MatchResult, String> replaceEnvValue() {
        return m -> {
            // 优先保留大括号形式
            String name = m.group(1) != null ? m.group(1) : m.group(2);
            String prefix = m.group(1) != null ? "${" + name + "}" : "$" + name;
            return Matcher.quoteReplacement(prefix) + "=" + REDACTED_ENV_VALUE;
        };
    }

    private java.util.function.Function<java.util.regex.MatchResult, String> replaceWinEnvValue() {
        return m -> Matcher.quoteReplacement("%" + m.group(1) + "%") + "=" + REDACTED_ENV_VALUE;
    }
}