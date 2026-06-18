package com.kylinops.notification.config;

/**
 * 通知配置乐观锁冲突 — 专用异常,以便 Task 5 在 ControllerAdvice 中映射为 HTTP 409。
 *
 * <p>P1-01 Plan 01 — Task 3。消息中<b>绝不</b>包含明文 secret、密文或 URL。</p>
 */
public class NotificationConfigurationConflictException extends RuntimeException {

    public NotificationConfigurationConflictException(String message) {
        super(message);
    }
}