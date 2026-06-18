package com.kylinops.notification.api;

import com.kylinops.notification.ChannelType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 通道创建/更新请求。
 * <p>
 * 字段使用说明：
 * <ul>
 *   <li>{@code channelId} — 仅创建时必填（POST），更新时由路径参数传入</li>
 *   <li>{@code version} — 更新时必填（PUT），创建时可为 null（使用 0）</li>
 *   <li>{@code secret} — 可选；null/空 表示「保持原值」（更新场景）</li>
 *   <li>{@code clearSecret} — 仅更新场景使用；true 时强制 secret 置 null</li>
 * </ul>
 * </p>
 *
 * @param channelId   通道标识（仅创建时使用）
 * @param type        通道类型
 * @param enabled     是否启用
 * @param url         通知 URL
 * @param secret      secret（可选；更新时 null 保留原值）
 * @param clearSecret 是否清除 secret（仅更新）
 * @param timeoutMs   超时毫秒
 * @param version     乐观锁版本（创建时为 null）
 */
public record NotificationChannelUpsertRequest(
        String channelId,

        @NotNull ChannelType type,

        boolean enabled,

        @NotBlank @Size(max = 2048) String url,

        @Size(max = 4096) String secret,

        boolean clearSecret,

        @Min(500) @Max(30000) Integer timeoutMs,

        Long version) {
}
