package com.kylinops.notification;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 通知记录摘要 — API 出口 DTO（P1-01 Plan 01 — Task 2；设计文档 §4.7）。
 *
 * <p><b>安全约束</b>：</p>
 * <ul>
 *   <li>不含 {@code requestPayload}（防止 secret / 内部 payload 通过 API 泄露到前端）</li>
 *   <li>不含 {@code responseBody}（仅运维诊断用，API 不暴露）</li>
 *   <li>{@code errorMessage} 已脱敏（dispatcher 在 C5 写入时调用 Sanitizer）</li>
 * </ul>
 */
@Data
@Builder
public class NotificationRecordSummary {

    private String recordId;
    private String eventId;
    private String auditId;
    private String channelId;
    private ChannelType channelType;
    private NotificationStatus status;
    private Integer responseCode;
    private String errorMessage;
    private int retryCount;
    private LocalDateTime sentAt;
    private LocalDateTime createdAt;
    private NotificationEventType eventType;

    /**
     * Entity → Summary 映射。显式列举字段，避免通过反射将敏感字段意外暴露。
     */
    public static NotificationRecordSummary from(NotificationRecord entity) {
        return NotificationRecordSummary.builder()
                .recordId(entity.getRecordId())
                .eventId(entity.getEventId())
                .auditId(entity.getAuditId())
                .channelId(entity.getChannelId())
                .channelType(entity.getChannelType())
                .status(entity.getStatus())
                .responseCode(entity.getResponseCode())
                .errorMessage(entity.getErrorMessage())
                .retryCount(entity.getRetryCount())
                .sentAt(entity.getSentAt())
                .createdAt(entity.getCreatedAt())
                .eventType(entity.getEventType())
                .build();
    }
}
