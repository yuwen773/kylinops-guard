package com.kylinops.notification.api;

import com.kylinops.notification.ChannelType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 通道连接测试请求 — P1-01 Plan 01 Task 7。
 *
 * <p>两种模式:</p>
 * <ol>
 *   <li><b>已保存通道</b> — 只填 {@code channelId};其余字段忽略
 *       <pre>{"channelId":"feishu-oncall","message":"测试"}</pre></li>
 *   <li><b>未保存通道(draft)</b> — 不填 {@code channelId},其余字段全填
 *       <pre>{"type":"WEBHOOK","enabled":true,"url":"https://...","secret":"...",
 *        "clearSecret":false,"timeoutMs":3000,"message":"测试"}</pre></li>
 * </ol>
 *
 * <p>判定规则: {@code channelId} 非空 → 已保存模式;否则为 draft 模式
 * (要求 type / url / enabled 必填)。</p>
 */
public record NotificationChannelTestRequest(
        String channelId,

        ChannelType type,

        Boolean enabled,

        @Size(max = 2048) String url,

        @Size(max = 4096) String secret,

        Boolean clearSecret,

        @Min(500) @Max(30000) Integer timeoutMs,

        @Size(max = 500) String message) {

    public boolean isSavedMode() {
        return channelId != null && !channelId.isBlank();
    }

    public boolean isDraftMode() {
        return !isSavedMode();
    }

    public boolean enabledOrDefault() {
        return enabled == null || enabled;
    }

    public int timeoutOrDefault() {
        return timeoutMs == null ? 3000 : timeoutMs;
    }

    public boolean clearSecretOrDefault() {
        return clearSecret != null && clearSecret;
    }
}
