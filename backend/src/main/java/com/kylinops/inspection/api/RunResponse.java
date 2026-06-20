package com.kylinops.inspection.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.kylinops.inspection.model.InspectionExecutionStatus;

/**
 * 立即执行响应(P1-02 Task 7)。
 *
 * <p>POST /api/inspections/plans/{planId}/run 立即返回;executionId 是后续轮询的
 * 主键。{@code status} 在响应时通常为 RUNNING(异步),但若同步快速收敛也可能为
 * SUCCESS / FAILED(取决于模板规模和工具执行时长),前端不应假设固定终态。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunResponse(
        String executionId,
        InspectionExecutionStatus status
) {
}
