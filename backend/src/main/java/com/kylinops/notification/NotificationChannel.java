package com.kylinops.notification;

/**
 * 通知通道接口 — <b>通道类型处理器</b>(不是通道实例)。
 *
 * <p><b>设计原则</b>:</p>
 * <ul>
 *   <li>实现类必须是 Spring @Component,自动注册到 {@link NotificationChannelRegistry}</li>
 *   <li>WebhookChannel bean 处理所有 type=WEBHOOK 的配置实例</li>
 *   <li>FeishuChannel bean 处理所有 type=FEISHU 的配置实例</li>
 *   <li>具体通道实例由 {@link NotificationConfig.ChannelConfig} 标识(通过 channelConfig.id)</li>
 *   <li>supports() 返回 false 时 dispatcher 跳过该通道</li>
 *   <li>send() 抛出的异常由 dispatcher 统一捕获并写 FAILED 记录</li>
 * </ul>
 */
public interface NotificationChannel {

    /**
     * 通道类型(用于 registry 按 ChannelType 索引)。
     */
    ChannelType type();

    /**
     * 是否支持处理该事件与通道配置。默认返回 true;子类可按 eventType / severity / channelConfig 过滤。
     */
    default boolean supports(NotificationEvent event, NotificationConfig.ChannelConfig channelConfig) {
        return true;
    }

    /**
     * 发送通知。<b>任何异常必须抛回 dispatcher 统一处理</b>,不在实现类内 catch。
     *
     * @param event          事件
     * @param maskedPayload  脱敏后的 payload(dispatcher 已处理)
     * @param channelConfig  通道配置(含 url / secret / timeout / id)
     * @return 发送结果(含 responseCode / responseBody)
     */
    NotificationSendResult send(NotificationEvent event, String maskedPayload,
                                NotificationConfig.ChannelConfig channelConfig);
}
