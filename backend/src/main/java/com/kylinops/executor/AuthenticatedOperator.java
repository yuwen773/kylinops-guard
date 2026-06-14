package com.kylinops.executor;

/**
 * 已认证操作者身份值对象
 * <p>
 * 封装认证用户的 principal（用户名）和 authSessionId（HTTP 会话 ID）。
 * 用于 L2 待确认动作的创建者归属校验，防止跨会话提权确认。
 * </p>
 *
 * <h3>来源</h3>
 * <ul>
 *   <li>{@code principal} — 来自 {@code SecurityContextHolder.getContext().getAuthentication().getName()}</li>
 *   <li>{@code authSessionId} — 来自 {@code HttpServletRequest.getSession(false).getId()}</li>
 * </ul>
 *
 * <h3>安全约束</h3>
 * <ul>
 *   <li>不从请求 JSON 反序列化（防伪造）</li>
 *   <li>服务端从 SecurityContext + HttpSession 中提取</li>
 *   <li>匿名身份不可对 L2 动作进行 claim/confirm/cancel</li>
 * </ul>
 */
public record AuthenticatedOperator(String principal, String authSessionId) {

    /** 匿名操作者（未认证请求的兜底标识） */
    public static final AuthenticatedOperator ANONYMOUS = new AuthenticatedOperator("anonymous", "anonymous");

    /**
     * 是否已认证。
     * <p>
     * P2-T2 保证所有 /api/** 请求必须认证，因此正常流程中不会出现 ANONYMOUS。
     * 此检查用于防御性编程。
     * </p>
     */
    public boolean isAuthenticated() {
        return principal != null && !principal.isBlank()
                && authSessionId != null && !authSessionId.isBlank()
                && !"anonymous".equals(principal);
    }
}
