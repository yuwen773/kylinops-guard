package com.kylinops.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求体。
 *
 * @param username 管理员用户名
 * @param password 管理员密码（明文，HTTPS 保护）
 */
public record LoginRequest(
        @NotBlank(message = "用户名不能为空") String username,
        @NotBlank(message = "密码不能为空") String password
) {
}
