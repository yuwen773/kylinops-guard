package com.kylinops.notification;

/**
 * 通知通道类型 — 按"通道类型"索引到对应的 {@code NotificationChannel} 实现
 * （{@code WebhookChannel} / {@code FeishuChannel}，C4 实现）。
 *
 * <p>注意：这是"类型"而非"实例"；同一类型可以有多个配置实例
 * （如两个不同的 Webhook URL），它们通过 {@code channelId}（即
 * {@link NotificationConfig.ChannelConfig#getId()}）区分。</p>
 */
public enum ChannelType {

    /** 通用 Webhook（POST JSON） */
    WEBHOOK,

    /** 飞书机器人 */
    FEISHU
}
