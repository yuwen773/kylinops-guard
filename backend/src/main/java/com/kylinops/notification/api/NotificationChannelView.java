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
        LocalDateTime updatedAt) {
}
