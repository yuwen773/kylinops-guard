package com.kylinops.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 报告生成请求 DTO。
 * <p>
 * 至少需要 auditId 或 sessionId 之一作为来源；二者皆缺失时 {@code ReportService.generate}
 * 抛出 {@link IllegalArgumentException}。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportGenerateRequest {

    /** 来源审计 ID（可选，与 sessionId 二选一或同时存在）。 */
    private String auditId;

    /** 所属会话 ID（可选；单独使用时取该会话最新 audit）。 */
    private String sessionId;

    /** 报告类型（HEALTH / DISK / SERVICE / SECURITY / AUDIT）。 */
    private ReportType reportType;
}