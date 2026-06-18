package com.kylinops.notification.config;

import com.kylinops.notification.ChannelType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 通知通道运行时配置实体({@code notification_channels})。
 *
 * <p>P1-01 Plan 01 — Task 3。每行对应一个活跃通道实例;软删除通过
 * {@code deleted_at} 标记,严禁重用已被软删除的 channel_id。</p>
 *
 * <h3>安全约束</h3>
 * <ul>
 *   <li>{@code encryptedSecret} 仅存密文(由 {@link NotificationSecretCipher} 加密);
 *       明文由 dispatcher 按需解密,从不写回 DB</li>
 *   <li>不与 {@code notification_records} 建外键,避免跨表 cascade 影响发送历史</li>
 * </ul>
 */
@Entity
@Table(name = "notification_channels")
@Getter
@Setter
@NoArgsConstructor
public class NotificationChannelEntity {

    /** 通道实例 ID(全局唯一;软删除后 ID 不可重用)。 */
    @Id
    @Column(name = "channel_id", length = 100)
    private String channelId;

    /** 通道类型(WEBHOOK / FEISHU)。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "channel_type", nullable = false, length = 20)
    private ChannelType channelType;

    /** 是否启用。 */
    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    /** 通道 URL(http/https,无 userinfo)。 */
    @Column(name = "url", nullable = false, length = 2048)
    private String url;

    /** AES-256-GCM 加密后的密钥密文,WEBHOOK 可为 null。 */
    @Column(name = "encrypted_secret", columnDefinition = "TEXT")
    private String encryptedSecret;

    /** 连接/读超时(ms,500-30000)。 */
    @Column(name = "timeout_ms", nullable = false)
    private Integer timeoutMs;

    /** 软删除时间戳;非空表示已删除,不可重用。 */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /** JPA 乐观锁。 */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @jakarta.persistence.PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @jakarta.persistence.PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}