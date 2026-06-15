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
 * 执行结果记录（与 attempt 1:1）。
 * <p>
 * 对应 V2 表 {@code kylin_execution_outcome}，在 {@link ActionConfirmService#recordOutcome}
 * 阶段写入。status 为 SUCCEEDED / FAILED / DEGRADED。
 * </p>
 * <p>
 * 注意：V2 表仅有 {@code created_at} 而无 {@code updated_at}，
 * 故本实体不继承 {@link com.kylinops.common.BaseEntity}。
 * </p>
 */
@Entity
@Table(name = "kylin_execution_outcome")
@Getter
@Setter
public class ExecutionOutcomeRecord {

    /** 自增主键 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 创建时间（不可更新） */
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 执行结果唯一标识（UUID） */
    @Column(nullable = false, unique = true, length = 36)
    private String outcomeId;

    /** 关联的 ExecutionAttempt.attemptId（1:1） */
    @Column(nullable = false, unique = true, length = 36)
    private String attemptId;

    /** 执行状态：SUCCEEDED / FAILED / DEGRADED */
    @Column(nullable = false, length = 16)
    private String status;

    /** 执行摘要 */
    @Column(columnDefinition = "TEXT")
    private String summary;

    /** 执行证据（JSON，含命令/退出码/时间戳；不含 LLM 链式思考） */
    @Column(columnDefinition = "TEXT")
    private String evidenceJson;

    /** 执行完成时间 */
    @Column(nullable = false)
    private LocalDateTime finishedAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
        if (this.outcomeId == null || this.outcomeId.isBlank()) {
            this.outcomeId = java.util.UUID.randomUUID().toString();
        }
        if (this.finishedAt == null) {
            this.finishedAt = LocalDateTime.now();
        }
    }
}
