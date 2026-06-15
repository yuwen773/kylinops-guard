package com.kylinops.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 单管理员认证配置（kylinops.auth.*）。
 * <p>
 * 与 kylinops.security.*（运行时 L2 限额）解耦：auth.* 控制登录与会话生命周期。
 * </p>
 *
 * <h3>配置项</h3>
 * <ul>
 *   <li>username / passwordHash：单管理员凭据（BCrypt）</li>
 *   <li>idleTimeout：Spring Session 空闲超时（默认 30m）</li>
 *   <li>absoluteTimeout：登录时间戳硬性上限（默认 8h）</li>
 *   <li>maxFailures：连续失败锁定阈值（默认 5）</li>
 *   <li>lockDuration：锁定时长（默认 15m）</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "kylinops.auth")
public record AuthProperties(
        String username,
        String passwordHash,
        Duration idleTimeout,
        Duration absoluteTimeout,
        int maxFailures,
        Duration lockDuration
) {
    public AuthProperties {
        if (username == null || username.isBlank()) {
            username = "admin";
        }
        if (idleTimeout == null) {
            idleTimeout = Duration.ofMinutes(30);
        }
        if (absoluteTimeout == null) {
            absoluteTimeout = Duration.ofHours(8);
        }
        if (maxFailures <= 0) {
            maxFailures = 5;
        }
        if (lockDuration == null) {
            lockDuration = Duration.ofMinutes(15);
        }
    }
}
