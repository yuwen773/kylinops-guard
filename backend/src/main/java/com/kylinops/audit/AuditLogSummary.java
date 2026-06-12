package com.kylinops.audit;

import com.kylinops.common.enums.AuditStatus;
import com.kylinops.common.enums.IntentType;
import com.kylinops.common.enums.RiskDecision;
import com.kylinops.common.enums.RiskLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审计日志列表 DTO
 * <p>
 * 用于 GET /api/audit/logs 的响应。
 * 不暴露 JPA 实体内部结构。
 * </p>
 */
@Data
@Builder
@AllArgsConstructor
public class AuditLogSummary {

    /** 审计 ID */
    private String auditId;

    /** 会话 ID */
    private String sessionId;

    /** 用户输入摘要（已脱敏截断） */
    private String userInput;

    /** 意图类型 */
    private IntentType intentType;

    /** 风险等级 */
    private RiskLevel riskLevel;

    /** 风险决策 */
    private RiskDecision riskDecision;

    /** 审计状态 */
    private AuditStatus status;

    /** 是否需确认 */
    private boolean confirmationRequired;

    /** 确认状态 */
    private String confirmationStatus;

    /** 审计消息 */
    private String message;

    /**
     * 关联工具调用次数。
     * <p>
     * 该字段由 AuditLogService 通过一次 grouped aggregate
     * （{@code ToolCallRecordRepository.countByAuditIdInGrouped}）填充，
     * 避免按行循环 {@code findByAuditId} 的 N+1 查询。
     * </p>
     */
    private long toolCallCount;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
