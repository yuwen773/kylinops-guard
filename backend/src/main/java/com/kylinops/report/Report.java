package com.kylinops.report;

import com.kylinops.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 报告实体
 * <p>
 * 存储 Agent 生成的各类运维报告，
 * 如系统健康检查报告、磁盘诊断报告、服务诊断报告等。
 * </p>
 */
@Entity
@Table(name = "kylin_report")
@Getter
@Setter
public class Report extends BaseEntity {

    /** 报告唯一标识（UUID，对外暴露） */
    @Column(nullable = false, unique = true, length = 36)
    private String reportId;

    /** 会话 ID */
    @Column(length = 36)
    private String sessionId;

    /** 报告类型 */
    @Column(nullable = false, length = 32)
    private String type;

    /** 报告标题 */
    @Column(nullable = false, length = 256)
    private String title;

    /** 报告内容（完整 Markdown 文本） */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** 报告摘要（用于列表展示） */
    @Column(columnDefinition = "TEXT")
    private String summary;

    /** 报告生成时间 */
    @Column(nullable = false)
    private LocalDateTime generatedAt;

    @PrePersist
    @Override
    protected void onCreate() {
        super.onCreate();
        if (this.reportId == null || this.reportId.isBlank()) {
            this.reportId = java.util.UUID.randomUUID().toString();
        }
        if (this.generatedAt == null) {
            this.generatedAt = LocalDateTime.now();
        }
    }
}
