package com.kylinops.inspection.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.kylinops.inspection.model.InspectionExecutionStatus;
import com.kylinops.inspection.model.InspectionTriggerType;

import java.time.LocalDateTime;

/**
 * 巡检执行记录详情(P1-02 Task 7)。
 *
 * <p>GET /api/inspections/executions/{executionId} 返回;含 {@code planSnapshotJson
 * / auditId / reportId} 关联字段。{@code errorMessage} 来自 execution.summary
 * (FAILED 时写入 "巡检 FAILED: ..."),前端可据此渲染失败原因。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InspectionExecutionDetail(
        String planId,
        String executionId,
        InspectionExecutionStatus status,
        InspectionTriggerType triggerType,
        String operator,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        boolean abnormal,
        String summary,
        String planSnapshotJson,
        String auditId,
        String reportId,
        String errorMessage
) {
}
