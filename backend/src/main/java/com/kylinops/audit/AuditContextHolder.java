package com.kylinops.audit;

/**
 * 当前请求的 auditId / LLM 调用阶段 ThreadLocal 容器（P3-T5）。
 *
 * <p>由 {@link com.kylinops.agent.AgentOrchestrator} 在 process() 入口
 * 设置，try/finally 在结束（含异常）时清理。</p>
 *
 * <p>由 {@link com.kylinops.llm.AuditingLlmClient} 调用前读取，
 * 用于关联 {@link LlmCallRecord#auditId} / {@link LlmCallRecord#stage}。</p>
 *
 * <h3>线程模型</h3>
 * <ul>
 *   <li>当前 Spring MVC 默认同步 servlet（Tomcat thread-per-request），
 *       故 ThreadLocal 在请求范围内安全</li>
 *   <li>异步任务（{@code @Async} / parallel tool execution）必须显式传递
 *       auditId，<strong>不得</strong>依赖 ThreadLocal 跨线程传递</li>
 * </ul>
 *
 * <h3>使用契约</h3>
 * <pre>{@code
 *   AuditContextHolder.set(auditId);
 *   try {
 *       ...
 *   } finally {
 *       AuditContextHolder.clear();
 *   }
 * }</pre>
 */
public final class AuditContextHolder {

    private static final ThreadLocal<String> AUDIT_ID = new ThreadLocal<>();
    private static final ThreadLocal<LlmCallStage> STAGE = new ThreadLocal<>();

    private AuditContextHolder() {
        // static utility
    }

    /**
     * 设置当前线程的 auditId。
     * <p>同一线程重复 set 会覆盖；并发场景必须使用 try/finally 配对 clear。</p>
     */
    public static void set(String auditId) {
        AUDIT_ID.set(auditId);
    }

    /**
     * 获取当前线程的 auditId（可能为 null — 表示不在 Agent 请求范围内）。
     */
    public static String get() {
        return AUDIT_ID.get();
    }

    /**
     * 安全获取（null-safe 同 {@link #get()}）。保留命名以表达"期望可能为 null"的语义。
     */
    public static String getOrNull() {
        return AUDIT_ID.get();
    }

    /**
     * 设置当前线程的 LLM 调用阶段。
     */
    public static void setStage(LlmCallStage stage) {
        STAGE.set(stage);
    }

    /**
     * 获取当前线程的 LLM 调用阶段（可能为 null — 调用方应 fallback 到默认）。
     */
    public static LlmCallStage getStage() {
        return STAGE.get();
    }

    /**
     * 同时清理 auditId 与 stage。
     * <p>调用方在 try/finally 收尾处必须调用，避免 ThreadLocal 泄漏。</p>
     */
    public static void clear() {
        AUDIT_ID.remove();
        STAGE.remove();
    }
}