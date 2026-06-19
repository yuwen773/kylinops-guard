package com.kylinops.notification.config;

import com.kylinops.notification.ChannelType;
import lombok.Builder;

/**
 * 通道创建/更新命令。
 *
 * <h3>字段语义</h3>
 * <ul>
 *   <li>{@code id} — 仅创建时必填,更新时由路径参数传入</li>
 *   <li>{@code secret} — null/空 表示 "保持原值"(更新场景);非空表示替换</li>
 *   <li>{@code clearSecret} — 仅更新场景使用;true 时强制把 secret 置 null
 *       (FEISHU 类型校验会拒绝此操作)</li>
 * </ul>
 */
@Builder
public record NotificationChannelCommand(
        String id,
        ChannelType type,
        boolean enabled,
        String url,
        String secret,
        boolean clearSecret,
        int timeoutMs,
        long version) {
}