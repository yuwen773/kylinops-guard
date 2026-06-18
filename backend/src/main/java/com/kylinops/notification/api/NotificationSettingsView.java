package com.kylinops.notification.api;

import lombok.Builder;

import java.util.List;

/**
 * 通知设置 + 全量通道列表响应。
 * <p>
 * GET /api/notification/settings 以及 PUT /api/notification/settings
 * 的统一返回类型。
 * </p>
 */
@Builder
public record NotificationSettingsView(
        boolean enabled,
        boolean dryRun,
        long version,
        List<NotificationChannelView> channels) {
}
