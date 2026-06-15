package com.kylinops.agent.intelligence;

import com.kylinops.tool.ToolResult;

/**
 * LlmToolContextPolicy 抽象基类 (P3-T3).
 *
 * <p>提供公共工具方法：</p>
 * <ul>
 *   <li>{@link #empty()} — 空 / 失败 / 阻断结果占位</li>
 *   <li>{@link #truncate(String, int)} — 字节上限截断</li>
 *   <li>{@link #sanitizeText(String)} — 通过 {@link LlmContextSanitizer} 二次脱敏</li>
 * </ul>
 */
public abstract class AbstractLlmToolContextPolicy implements LlmToolContextPolicy {

    /** 空 / 失败结果的占位符 */
    protected static final String EMPTY_PLACEHOLDER = "（无数据）";

    /** 截断后缀 */
    protected static final String TRUNCATED_SUFFIX = "...[truncated]";

    private final LlmContextSanitizer sanitizer;

    protected AbstractLlmToolContextPolicy(LlmContextSanitizer sanitizer) {
        this.sanitizer = sanitizer;
    }

    /**
     * 空结果占位符（"（无数据）"）。
     */
    protected String empty() {
        return EMPTY_PLACEHOLDER;
    }

    /**
     * 截断字符串到不超过 {@code maxBytes} UTF-8 字节。
     * <p>如果超过，则保留前 (maxBytes - suffix.length) 字符并附加 "...[truncated]"。</p>
     *
     * @param text     原文
     * @param maxBytes 最大字节数（< 1 视为 1）
     */
    protected String truncate(String text, int maxBytes) {
        if (text == null) {
            return EMPTY_PLACEHOLDER;
        }
        if (maxBytes < 1) {
            maxBytes = 1;
        }
        if (text.isEmpty()) {
            return "";
        }
        // 以 Java char 数粗略估算（UTF-8 下中文可能占 3 字节，保守按 char 计）
        if (text.length() <= maxBytes) {
            return text;
        }
        int suffixLen = TRUNCATED_SUFFIX.length();
        int cutAt = Math.max(1, maxBytes - suffixLen);
        return text.substring(0, cutAt) + TRUNCATED_SUFFIX;
    }

    /**
     * 通过 LlmContextSanitizer 对文本执行间接注入 / 凭据 / 环境变量脱敏。
     *
     * @param text 待脱敏文本
     * @return 脱敏后文本（null 输入返回 null）
     */
    protected String sanitizeText(String text) {
        if (text == null) {
            return null;
        }
        return sanitizer.sanitize(text);
    }

    /**
     * 判断 ToolResult 是否"无有效数据"（失败 / 超时 / 阻断 / 空 data）。
     */
    protected boolean isEmpty(ToolResult result) {
        if (result == null) {
            return true;
        }
        if (!result.isSuccess()) {
            return true;
        }
        Object data = result.getData();
        if (data == null) {
            return true;
        }
        if (data instanceof java.util.Map<?, ?> map) {
            return map.isEmpty();
        }
        if (data instanceof java.util.Collection<?> col) {
            return col.isEmpty();
        }
        return false;
    }
}