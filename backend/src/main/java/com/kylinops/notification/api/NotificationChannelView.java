package com.kylinops.notification.api;

import com.kylinops.notification.ChannelType;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * 通道响应视图 — secret 永不回传。
 * <p>
 * 用 {@code secretConfigured} 替代实际 secret 值，
 * 避免明文泄到前端。
 * </p>
 *
 * <p>{@code lastTestResult} 字段是 P1-01 Plan 01 Task 7 新增 —
 * 同一通道最近一次连接测试结果（可能为 null，表示尚无测试记录）。</p>
 */
@Builder
public record NotificationChannelView(
        String id,
        ChannelType type,
        boolean enabled,
        String url,
        boolean secretConfigured,
        int timeoutMs,
        long version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        NotificationTestRecordSummary lastTestResult) {
}
