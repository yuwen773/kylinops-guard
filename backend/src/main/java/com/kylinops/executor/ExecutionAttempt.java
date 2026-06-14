package com.kylinops.executor;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 执行开始记录（追加写，永不更新）。
 * <p>
 * 对应 V2 表 {@code kylin_execution_attempt}，在 {@link ActionConfirmService#recordAttempt}
 * 阶段写入。一旦写入永不更新 — 即使后续流程失败，此记录仍反映「尝试已发起」的事实。
 * {@code status} 概念隐含（记录存在 = 已 STARTED），表中无对应列。
 * </p>
 * <p>
 * 注意：V2 表仅有 {@code created_at} 而无 {@code updated_at}，
 * 故本实体不继承 {@link com.kylinops.common.BaseEntity}。
 * </p>
 */
@Entity
@Table(name = "kylin_execution_attempt")
@Getter
@Setter
public class ExecutionAttempt {

    /** 自增主键 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 创建时间（不可更新） */
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 执行尝试唯一标识（UUID） */
    @Column(nullable = false, unique = true, length = 36)
    private String attemptId;

    /** 关联的审计日志 ID */
    @Column(nullable = false, length = 64)
    private String auditId;

    /** 待确认动作 ID */
    @Column(length = 64)
    private String actionId;

    /** 动作类型（如 safe_service_restart） */
    @Column(nullable = false, length = 64)
    private String actionType;

    /** 执行目标摘要 */
    @Column(length = 256)
    private String targetSummary;

    /** 执行开始时间 */
    @Column(nullable = false)
    private LocalDateTime startedAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
        if (this.attemptId == null || this.attemptId.isBlank()) {
            this.attemptId = java.util.UUID.randomUUID().toString();
        }
        if (this.startedAt == null) {
            this.startedAt = LocalDateTime.now();
        }
    }
}
