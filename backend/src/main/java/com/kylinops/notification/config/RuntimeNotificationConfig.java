package com.kylinops.notification.config;

import com.kylinops.notification.NotificationConfig;

import java.util.List;

/**
 * 通知中心运行时不可变快照 — 由 {@link NotificationConfigurationService} 持有,
 * 通过 {@link java.util.concurrent.atomic.AtomicReference} 发布,供 dispatcher 读取。
 *
 * <p>P1-01 Plan 01 — Task 3。"快照"语义:</p>
 * <ul>
 *   <li>仅在事务 after-commit 后才发布新值 — 杜绝读到事务回滚前状态</li>
 *   <li>channels 列表为 {@code List.copyOf(...)} 防御性拷贝,调用方不可改</li>
 *   <li>secret 在发布前解密;内部列表不再含密文</li>
 * </ul>
 */
public record RuntimeNotificationConfig(
        boolean enabled,
        boolean dryRun,
        List<NotificationConfig.ChannelConfig> channels) {

    public RuntimeNotificationConfig {
        // 防御性拷贝 + 不允许 null 列表
        channels = channels == null ? List.of() : List.copyOf(channels);
    }

    /** 工厂:空快照。 */
    public static RuntimeNotificationConfig empty() {
        return new RuntimeNotificationConfig(false, false, List.of());
    }
}