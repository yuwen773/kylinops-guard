package com.kylinops.notification.api;

import com.kylinops.notification.NotificationEventType;
import com.kylinops.notification.NotificationRecord;
import com.kylinops.notification.NotificationStatus;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * 测试记录摘要 — 管理端「最近测试记录」面板与「上次测试」标签的展示 DTO。
 *
 * <p>故意<b>不含</b> {@code requestPayload} / {@code responseBody} / {@code auditId}
 * (后者对 TEST 类型本就为 null),仅暴露诊断与展示需要的最小信息。</p>
 */
@Builder
public record NotificationTestRecordSummary(
        String recordId,
        String channelId,
        NotificationEventType eventType,
        NotificationStatus status,
        Integer responseCode,
        String errorMessage,
        LocalDateTime sentAt,
        Long durationMs) {

    public static NotificationTestRecordSummary from(NotificationRecord entity, long durationMs) {
        return NotificationTestRecordSummary.builder()
                .recordId(entity.getRecordId())
                .channelId(entity.getChannelId())
                .eventType(entity.getEventType())
                .status(entity.getStatus())
                .responseCode(entity.getResponseCode())
                .errorMessage(entity.getErrorMessage())
                .sentAt(entity.getSentAt() != null ? entity.getSentAt() : entity.getCreatedAt())
                .durationMs(durationMs)
                .build();
    }
}
