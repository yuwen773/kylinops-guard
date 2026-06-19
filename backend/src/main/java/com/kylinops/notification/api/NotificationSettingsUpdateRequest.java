package com.kylinops.notification.api;

import jakarta.validation.constraints.NotNull;

/**
 * 设置更新请求。
 *
 * @param enabled 是否启用通知
 * @param dryRun  是否为 dry-run 模式
 * @param version 乐观锁版本（必须与当前一致）
 */
public record NotificationSettingsUpdateRequest(
        @NotNull Boolean enabled,
        @NotNull Boolean dryRun,
        @NotNull Long version) {
}
