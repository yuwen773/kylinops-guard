package com.kylinops.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 通知发送记录 JPA 实体（P1-01 Plan 01 — Task 2）。
 *
 * <p>每条记录对应"一次事件 → 一个通道实例"的发送尝试（含 dry-run）。表结构：</p>
 * <ul>
 *   <li>联合唯一 (event_id, channel_id) — 防止同一事件对同一通道重复发送
 *       （C5 的 dispatcher 在重试场景下用 {@code findFirstByEventIdAndChannelId} 做幂等）</li>
 *   <li>索引 {@code idx_audit_id} — 与 AuditLog.auditId 关联查询</li>
 *   <li>索引 {@code idx_created_at} — 按时间窗排查</li>
 * </ul>
 *
 * <p><b>安全约束</b>：</p>
 * <ul>
 *   <li>{@code requestPayload} 已是脱敏后 JSON（dispatcher 在 C5 写入）</li>
 *   <li>{@code responseBody} 仅运维诊断用，API 出口（{@link NotificationRecordSummary}）不暴露</li>
 *   <li>{@code retryCount} 本期固定 0，P1-02 启用重试时再扩展枚举 {@link NotificationStatus}</li>
 * </ul>
 */
@Entity
@Table(name = "notification_records",
        uniqueConstraints = @UniqueConstraint(name = "uk_event_channel",
                columnNames = {"event_id", "channel_id"}),
        indexes = {
                @Index(name = "idx_audit_id", columnList = "audit_id"),
                @Index(name = "idx_created_at", columnList = "created_at")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationRecord {

    /** 记录 ID（UUID，业务主键；不依赖 DB 自增，便于跨库迁移） */
    @Id
    @Column(length = 36)
    private String recordId;

    /** 关联事件 ID（联合唯一） */
    @Column(nullable = false, length = 36)
    private String eventId;

    /** 审计 ID（与 AuditLog.auditId 关联，索引）；TEST 类型记录允许为 NULL */
    @Column(nullable = true)
    private String auditId;

    /** 通道实例 ID（如 "webhook-prod"），联合唯一 */
    @Column(nullable = false, length = 100)
    private String channelId;

    /** 通道类型（WEBHOOK / FEISHU） */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChannelType channelType;

    /** 发送状态（PENDING / SENT / FAILED / SKIPPED） */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationStatus status;

    /** 脱敏后的请求 payload（JSON 字符串） */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String requestPayload;

    /** 响应体（截断到 1KB） */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String responseBody;

    /** HTTP 响应码 */
    private Integer responseCode;

    /** 错误信息（脱敏后） */
    @Column(length = 500)
    private String errorMessage;

    /** 重试次数（本期固定 0） */
    @Column(nullable = false)
    private int retryCount;

    /** 实际发送完成时间 */
    private LocalDateTime sentAt;

    /** 记录创建时间（Clock 注入） */
    @Column(nullable = false)
    private LocalDateTime createdAt;

    /** 事件类型判别（L4_BLOCK / PROMPT_INJECTION_BLOCK / L2_CONFIRM_REQUIRED /
     *  SERVICE_ABNORMAL / DISK_RISK / TEST）。
     *  用于"按事件类型筛选最新 N 条"以及管理端"测试连接"记录识别。 */
    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private NotificationEventType eventType;
}
