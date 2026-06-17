package com.kylinops.report;

import com.kylinops.common.enums.RiskLevel;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 报告实体。
 * <p>
 * 字段严格对应任务卡 Task 12 §实现要点：reportId / reportType / title / sessionId /
 * auditId / riskLevel / bodyMarkdown / timestamps。仅从 AuditLogDetail 确定性组装，
 * 报告绝不通过 LLM 补事实。
 * </p>
 *
 * <p>
 * 数据库中立：仅使用 JPA 标准注解，未使用 H2 / PostgreSQL 私有语法，
 * 仓库层亦不绑定具体方言。
 * </p>
 */
@Entity
@Table(name = "kylin_report",
        indexes = {
                @Index(name = "idx_report_audit_id", columnList = "auditId"),
                @Index(name = "idx_report_session_created", columnList = "sessionId, createdAt")
        })
@Getter
@Setter
public class Report {

    /** 自增主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 报告唯一标识（UUID，对外暴露）。 */
    @Column(nullable = false, unique = true, length = 36)
    private String reportId;

    /** 报告类型（HEALTH / DISK / SERVICE / SECURITY / AUDIT）。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ReportType reportType;

    /** 报告标题。 */
    @Column(nullable = false, length = 256)
    private String title;

    /** 所属会话 ID。 */
    @Column(length = 36)
    private String sessionId;

    /** 来源审计 ID（贯穿 Agent → Audit → Report 全链路）。 */
    @Column(nullable = false, length = 36)
    private String auditId;

    /** 风险等级（L0–L4），来自源审计。 */
    @Enumerated(EnumType.STRING)
    @Column(length = 8)
    private RiskLevel riskLevel;

    /** 报告 Markdown 正文（确定性从 AuditLogDetail 组装）。 */
    @Lob
    @Column(nullable = false, columnDefinition = "TEXT")
    private String bodyMarkdown;

    /** 创建时间。 */
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 更新时间。 */
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        this.updatedAt = now;
        if (this.reportId == null || this.reportId.isBlank()) {
            this.reportId = java.util.UUID.randomUUID().toString();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /** 根因分析链 JSON 字符串（Lob TEXT，仅演示场景填充） */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String rootCauseChainJson;
}