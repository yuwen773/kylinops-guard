package com.kylinops.report;

import com.kylinops.common.enums.RiskLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 报告摘要（用于列表展示）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportSummary {

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

    /** 创建时间。 */
    private LocalDateTime createdAt;
}