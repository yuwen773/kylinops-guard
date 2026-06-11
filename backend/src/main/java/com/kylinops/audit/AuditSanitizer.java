package com.kylinops.audit;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 审计数据脱敏工具
 * <p>
 * 提供敏感字段的掩码处理和长文本截断。
 * 在审计日志持久化和响应前调用。
 * </p>
 */
public final class AuditSanitizer {

    /** 敏感键名（匹配时不区分大小写） */
    private static final List<Pattern> SENSITIVE_KEY_PATTERNS = List.of(
            Pattern.compile("password", Pattern.CASE_INSENSITIVE),
            Pattern.compile("secret", Pattern.CASE_INSENSITIVE),
            Pattern.compile("token", Pattern.CASE_INSENSITIVE),
            Pattern.compile("apikey", Pattern.CASE_INSENSITIVE),
            Pattern.compile("api_key", Pattern.CASE_INSENSITIVE),
            Pattern.compile("authorization", Pattern.CASE_INSENSITIVE),
            Pattern.compile("privatekey", Pattern.CASE_INSENSITIVE),
            Pattern.compile("private_key", Pattern.CASE_INSENSITIVE),
            Pattern.compile("credential", Pattern.CASE_INSENSITIVE),
            Pattern.compile("accesskey", Pattern.CASE_INSENSITIVE),
            Pattern.compile("access_key", Pattern.CASE_INSENSITIVE)
    );

    /** 最大审计输入摘要长度 */
    private static final int MAX_INPUT_LENGTH = 2000;

    /** 最大审计输出长度 */
    private static final int MAX_OUTPUT_LENGTH = 5000;

    private AuditSanitizer() {
        // 工具类，禁止实例化
    }

    /**
     * 脱敏字符串中的敏感字段。
     * <p>
     * 将 "password=abc123" 替换为 "password=******"。
     * 适用于日志行的键值对格式。
     * </p>
     *
     * @param text 原始文本
     * @return 脱敏后的文本
     */
    public static String sanitize(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }

        String result = text;
        for (Pattern pattern : SENSITIVE_KEY_PATTERNS) {
            // 替换 key=value 格式
            result = pattern.matcher(result).replaceAll(match -> {
                String key = match.group();
                return key + "=******";
            });
            // 替换 JSON 格式: "key":"value"
            result = result.replaceAll(
                    "(?i)(\"" + pattern.pattern() + "\"\\s*:\\s*\")[^\"]+(\")",
                    "$1******$2");
        }

        return result;
    }

    /**
     * 截断长文本，用于审计日志存储。
     *
     * @param text  原始文本
     * @param maxLen 最大长度
     * @return 截断后的文本（含截断标记）
     */
    public static String truncate(String text, int maxLen) {
        if (text == null) return null;
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...[truncated " + (text.length() - maxLen) + " chars]";
    }

    /**
     * 截断审计输入。
     */
    public static String truncateInput(String input) {
        return truncate(sanitize(input), MAX_INPUT_LENGTH);
    }

    /**
     * 截断审计输出。
     */
    public static String truncateOutput(String output) {
        return truncate(sanitize(output), MAX_OUTPUT_LENGTH);
    }
}
