package com.kylinops.notification;

import lombok.Builder;

import java.time.LocalDateTime;

/**
 * 通知通道连接测试结果 — P1-01 Plan 01 Task 7。
 *
 * <p>同步返回。HTTP 200 始终表示「测试请求被处理」(即使是 FAILED
 * 也算成功,因为外部 HTTP 失败是预期结果)。{@link #status()}
 * 决定实际成功/失败:</p>
 * <ul>
 *   <li>SENT — 外部接收端 2xx</li>
 *   <li>FAILED — 外部接收端 4xx/5xx 或网络异常</li>
 * </ul>
 */
@Builder
public record NotificationTestResult(
        String recordId,
        String channelId,
        NotificationEventType eventType,
        NotificationStatus status,
        Integer responseCode,
        String errorMessage,
        LocalDateTime sentAt,
        long durationMs) {
}
