package com.kylinops.report;

import com.kylinops.common.enums.RiskLevel;
import com.kylinops.rca.RootCauseChain;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 报告详情（含 Markdown 正文）。
 * <p>
 * 用于 {@code GET /api/reports/{reportId}} 和 {@code POST /api/reports/generate} 的响应。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportDetail {

    /** 报告唯一标识。 */
    private String reportId;

    /** 报告标题。 */
    private String title;

    /** 报告类型。 */
    private ReportType reportType;

    /** 风险等级。 */
    private RiskLevel riskLevel;

    /** 所属会话 ID。 */
    private String sessionId;

    /** 来源审计 ID。 */
    private String auditId;

    /** Markdown 正文（确定性从 AuditLogDetail 组装）。 */
    private String bodyMarkdown;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    /** 根因分析链（反序列化自 rootCauseChainJson） */
    private RootCauseChain rootCauseChain;
}