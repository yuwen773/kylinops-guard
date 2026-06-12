package com.kylinops.security;

import com.kylinops.common.enums.RiskDecision;
import com.kylinops.common.enums.RiskLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 安全事件（仅 BLOCK）只读视图。
 * <p>
 * 用于 GET /api/security/events 的响应；
 * 故意不暴露用户原始输入或内部异常信息，避免泄露敏感内容。
 * </p>
 */
@Data
@Builder
@AllArgsConstructor
public class SecurityEventView {

    /** 审计 ID（与 AuditLog.auditId 对应） */
    private String auditId;

    /** 风险等级 */
    private RiskLevel riskLevel;

    /** 风险决策（恒为 BLOCK） */
    private RiskDecision decision;

    /** 命中的规则 ID 列表 */
    private List<String> matchedRules;

    /** 阻断原因（中文） */
    private String reason;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 关联工具名（可选） */
    private String toolName;
}
