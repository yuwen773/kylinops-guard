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

    /** 匹配的风险规则 ID（JSON 数组） */
    @Column(columnDefinition = "TEXT")
    private String matchedRules;

    /** 执行计划摘要 */
    @Column(columnDefinition = "TEXT")
    private String actionPlan;

    /** 是否需要用户确认 */
    @Column(nullable = false)
    private boolean confirmationRequired;

    /** 确认状态：WAITING / CONFIRMED / CANCELLED */
    @Column(length = 20)
    private String confirmationStatus;

    /** 执行结果摘要 */
    @Column(columnDefinition = "TEXT")
    private String executionResult;

    /** 最终回复摘要 */
    @Column(columnDefinition = "TEXT")
    private String finalAnswer;

    /** 审计警告信息 */
    @Column(columnDefinition = "TEXT")
    private String warning;

    /** 根因分析链 JSON 字符串（Lob TEXT，仅演示场景填充） */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String rootCauseChainJson;

    /**
     * 触发来源标识（仅巡检路径写入）。
     * <p>普通 chat 审计该字段为 {@code null}；巡检审计写入 {@code SCHEDULED}
     * （定时调度）或 {@code MANUAL}（管理员手动触发）。</p>
     * <p>数据库列由 V7 migration 添加（{@code kylin_audit_log.trigger_type VARCHAR(32)}）。</p>
     */
    @Column(length = 32)
    private String triggerType;

    /**
     * 操作主体（仅巡检路径写入）。
     * <p>普通 chat 审计该字段为 {@code null}；巡检审计写入 {@code SYSTEM_SCHEDULER}
     * （定时调度）或当前管理员用户名（手动触发）。</p>
     * <p>数据库列由 V7 migration 添加（{@code kylin_audit_log.operator VARCHAR(128)}）。</p>
     */
    @Column(length = 128)
    private String operator;

    @PrePersist
    @Override
    protected void onCreate() {
        super.onCreate();
        if (this.auditId == null || this.auditId.isBlank()) {
            this.auditId = java.util.UUID.randomUUID().toString();
        }
    }
}
