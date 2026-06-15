package com.kylinops.executor;

import com.kylinops.common.BaseEntity;
import com.kylinops.common.enums.RiskLevel;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 待确认动作实体
 * <p>
 * L2 风险决策的确认记录。用户确认后，才通过 SafeExecutor 执行。
 * actionId 为对外暴露的唯一标识，贯穿确认和审计链路。
 * </p>
 */
@Entity
@Table(name = "kylin_pending_action")
@Getter
@Setter
public class PendingAction extends BaseEntity {

    /** 动作唯一标识（UUID，对外暴露） */
    @Column(nullable = false, unique = true, length = 36)
    private String actionId;

    /** 关联的审计日志 ID */
    @Column(nullable = false, length = 64)
    private String auditId;

    /** 会话 ID（聊天会话，用于展示/跟踪，非归属校验） */
    @Column(length = 64)
    private String sessionId;

    /** 创建者身份（认证 principal，如 admin） */
    @Column(length = 64)
    private String creatorPrincipal;

    /** 创建者认证会话 ID（HTTP session id，非 chat sessionId） */
    @Column(length = 64)
    private String creatorAuthSessionId;

    /** 动作类型（如 safe_service_restart） */
    @Column(nullable = false, length = 64)
    private String actionType;

    /** 目标工具/服务名称 */
    @Column(length = 128)
    private String toolName;

    /** 参数（JSON 格式，服务端保存，不可替换） */
    @Column(columnDefinition = "TEXT")
    private String paramsJson;

    /** 风险等级 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private RiskLevel riskLevel;

    /** 生命周期状态 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PendingActionStatus status;

    /** 过期时间（超时未确认自动标记 EXPIRED） */
    @Column(nullable = false)
    private LocalDateTime expiresAt;

    /** 执行结果摘要 */
    @Column(columnDefinition = "TEXT")
    private String executionResult;

    @PrePersist
    @Override
    protected void onCreate() {
        super.onCreate();
        if (this.actionId == null || this.actionId.isBlank()) {
            this.actionId = java.util.UUID.randomUUID().toString();
        }
        if (this.status == null) {
            this.status = PendingActionStatus.WAITING;
        }
    }
}
