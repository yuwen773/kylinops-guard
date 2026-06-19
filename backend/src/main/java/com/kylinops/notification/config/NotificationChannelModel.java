package com.kylinops.notification.config;

import com.kylinops.notification.ChannelType;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * 通道响应模型(供 API 出口使用)。secret 在此<b>不</b>回传 — 管理 API
 * 永远只暴露是否已配置,避免明文泄到前端。
 *
 * <p>{@code lastTestResult} 字段是 P1-01 Plan 01 Task 7 新增 — 同一通道
 * 最近一次连接测试结果(可能为 null,表示尚无测试记录)。</p>
 */
@Builder
public record NotificationChannelModel(
        String id,
        ChannelType type,
        boolean enabled,
        String url,
        boolean hasSecret,
        int timeoutMs,
        long version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        com.kylinops.notification.api.NotificationTestRecordSummary lastTestResult) {
}
