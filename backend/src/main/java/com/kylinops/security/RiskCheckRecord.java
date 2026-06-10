package com.kylinops.security;

import com.kylinops.common.BaseEntity;
import com.kylinops.common.enums.RiskDecision;
import com.kylinops.common.enums.RiskLevel;
import com.kylinops.tool.ToolCallRecord;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 风险校验记录实体
 * <p>
 * 记录 RiskCheckService 对每次工具调用的安全校验结果。
 * 包含匹配的安全规则、决策原因和建议。
 * </p>
 */
@Entity
@Table(name = "kylin_risk_check_record")
@Getter
@Setter
public class RiskCheckRecord extends BaseEntity {

    /** 风险校验唯一标识（UUID，对外暴露） */
    @Column(nullable = false, unique = true, length = 36)
    private String riskCheckId;

    /** 关联的工具调用记录 */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tool_call_record_id", nullable = false)
    private ToolCallRecord toolCallRecord;

    /** 风险等级 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private RiskLevel riskLevel;

    /** 风险决策 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private RiskDecision riskDecision;

    /** 匹配的规则（JSON 数组） */
    @Column(columnDefinition = "TEXT")
    private String matchedRules;

    /** 决策原因 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    /** 安全建议 */
    @Column(columnDefinition = "TEXT")
    private String safeSuggestion;

    /** 关联的审计日志 ID（贯穿全链路） */
    @Column(length = 36)
    private String auditId;

    /** 校验时间 */
    @Column(nullable = false)
    private LocalDateTime checkedAt;

    @PrePersist
    @Override
    protected void onCreate() {
        super.onCreate();
        if (this.riskCheckId == null || this.riskCheckId.isBlank()) {
            this.riskCheckId = java.util.UUID.randomUUID().toString();
        }
        if (this.checkedAt == null) {
            this.checkedAt = LocalDateTime.now();
        }
    }
}
