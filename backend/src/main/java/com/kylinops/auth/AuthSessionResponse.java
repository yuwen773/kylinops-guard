package com.kylinops.auth;

import java.time.Instant;

/**
 * 登录成功 / 会话查询返回结构。
 *
 * @param username     管理员用户名
 * @param csrfToken    CSRF token（用于非 GET 请求携带 X-XSRF-TOKEN）
 * @param loginAt      登录时间戳（Instant）
 * @param expiresAt    绝对到期时间戳
 * @param idleTimeout 空闲超时（秒）
 */
public record AuthSessionResponse(
        String username,
        String csrfToken,
        Instant loginAt,
        Instant expiresAt,
        long idleTimeout
) {
}
