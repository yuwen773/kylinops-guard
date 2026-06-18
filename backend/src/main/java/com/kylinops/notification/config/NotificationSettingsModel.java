package com.kylinops.notification.config;

import lombok.Builder;

/**
 * 设置响应模型(供 API 出口使用)。
 */
@Builder
public record NotificationSettingsModel(
        boolean enabled,
        boolean dryRun,
        long version) {
}