package com.kylinops.llm;

/**
 * LLM 调用失败的统一异常类型。
 *
 * <p>调用方按 {@link Reason} 分支决定降级策略：</p>
 * <ul>
 *   <li>{@link Reason#TIMEOUT} / {@link Reason#NETWORK} / {@link Reason#RATE_LIMITED}
 *       / {@link Reason#SERVER_ERROR} — 一律回退到规则/模板（不阻塞主链路）</li>
 *   <li>{@link Reason#INVALID_RESPONSE} — 视为模型输出不可信，记录后回退模板</li>
 *   <li>{@link Reason#AUTH} — 启动配置错误，应当立即上报而非静默回退</li>
 * </ul>
 *
 * <p><strong>安全红线</strong>：构造本异常的 message 不得包含 API key 原文。
 * 调用方在拼装错误上下文时也应使用 {@code apiKeyMasked} 或干脆省略。</p>
 */
public class LlmClientException extends RuntimeException {

    /**
     * 失败原因枚举。
     * <p>调用方依据此 reason 决定降级策略（不允许通过 message 文本匹配）。</p>
     */
    public enum Reason {
        /** 阶段超时（连接 / 读取超时） */
        TIMEOUT,
        /** 429 限流 */
        RATE_LIMITED,
        /** 5xx 服务端错误 */
        SERVER_ERROR,
        /** 网络层失败（DNS / 连接拒绝 / 断流） */
        NETWORK,
        /** 响应体无法解析为有效 Chat Completions 结构 */
        INVALID_RESPONSE,
        /** 401 / 403 鉴权失败 */
        AUTH
    }

    private final Reason reason;

    public LlmClientException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public LlmClientException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}