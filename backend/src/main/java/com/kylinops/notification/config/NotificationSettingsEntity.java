package com.kylinops.notification.config;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 通知中心运行时全局设置(单例表 {@code notification_settings})。
 *
 * <p>P1-01 Plan 01 — Task 3。该表与现有发送平面 {@code NotificationConfig}
 * 解耦;启动时若表为空则从 YAML 一次性导入,之后数据库为唯一权威。</p>
 *
 * <ul>
 *   <li>{@code id} 固定为 1(SQL CHECK id=1 约束)</li>
 *   <li>{@code enabled} 全局开关</li>
 *   <li>{@code dryRun} dry-run 模式(true 时不真发)</li>
 *   <li>{@code version} JPA 乐观锁</li>
 * </ul>
 */
@Entity
@Table(name = "notification_settings")
@Getter
@Setter
@NoArgsConstructor
public class NotificationSettingsEntity {

    /** 单例主键 — 由 SQL CHECK 约束保证仅有一行 id=1。 */
    @Id
    @Column(name = "id")
    private Short id = 1;

    /** 全局开关。 */
    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    /** dry-run 模式。 */
    @Column(name = "dry_run", nullable = false)
    private boolean dryRun;

    /** JPA 乐观锁(管理 API 用)。 */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * 由 JPA 生命周期回调在持久化前填充时间戳。{@code @PrePersist} 必须在
     * JPA 框架下工作 — 我们保留 JPA 习惯用法,不在构造函数中赋值 createdAt,
     * 以便数据库 DEFAULT 与 Java 字段同步。
     */
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