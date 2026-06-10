package com.kylinops.audit;

import com.kylinops.common.BaseEntity;
import com.kylinops.common.enums.*;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * 审计日志实体
 * <p>
 * 审计闭环的中央记录表。
 * auditId 贯穿 Session → Message → ToolCallRecord → RiskCheckRecord 全链路，
 * 提供端到端的请求追踪能力。
 * </p>
 */
@Entity
@Table(name = "kylin_audit_log")
@Getter
@Setter
public class AuditLog extends BaseEntity {

    /** 审计日志唯一标识（UUID，贯穿全链路） */
    @Column(nullable = false, unique = true, length = 36)
    private String auditId;

    /** 会话 ID */
    @Column(length = 36)
    private String sessionId;

    /** 用户输入摘要 */
    @Column(columnDefinition = "TEXT")
    private String userInput;

    /** 识别到的意图类型 */
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private IntentType intentType;

    /** 执行的工具名称 */
    @Column(length = 64)
    private String toolName;

    /** 风险等级 */
    @Enumerated(EnumType.STRING)
    @Column(length = 8)
    private RiskLevel riskLevel;

    /** 风险决策 */
    @Enumerated(EnumType.STRING)
    @Column(length = 12)
    private RiskDecision riskDecision;

    /** 审计状态 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AuditStatus status;

    /** 审计消息 / 决策说明 */
    @Column(columnDefinition = "TEXT")
    private String message;

    /** 执行耗时（毫秒） */
    private Long durationMs;

    @PrePersist
    @Override
    protected void onCreate() {
        super.onCreate();
        if (this.auditId == null || this.auditId.isBlank()) {
            this.auditId = java.util.UUID.randomUUID().toString();
        }
    }
}
