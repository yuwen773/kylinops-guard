package com.kylinops.inspection.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.kylinops.inspection.model.InspectionExecutionStatus;
import com.kylinops.inspection.model.InspectionTriggerType;

import java.time.LocalDateTime;

/**
 * 巡检执行记录摘要(P1-02 Task 7)。
 *
 * <p>GET /api/inspections/executions 列表项;不含大字段 planSnapshotJson,
 * 详情由 {@link InspectionExecutionDetail} 提供。{@code summary} 字段已由
 * {@link com.kylinops.inspection.InspectionExecutionService} 截断到 1024 字符。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InspectionExecutionSummary(
        String planId,
        String executionId,
        InspectionExecutionStatus status,
        InspectionTriggerType triggerType,
        String operator,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        boolean abnormal,
        String summary
) {
}
