package com.kylinops.notification.config;

import lombok.Builder;

/**
 * 设置更新命令(API 入参 → service 层契约)。
 *
 * <p>Task 5 把 HTTP request body 映射到此 record;此处不绑定 JSON 注解,
 * 保持 service 层独立。</p>
 */
@Builder
public record NotificationSettingsCommand(
        boolean enabled,
        boolean dryRun,
        long version) {
}