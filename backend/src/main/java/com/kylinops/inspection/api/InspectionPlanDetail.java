package com.kylinops.inspection.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.kylinops.inspection.model.InspectionNotificationPolicy;
import com.kylinops.inspection.model.InspectionScheduleType;
import com.kylinops.inspection.model.InspectionTemplateType;

import java.time.LocalDateTime;

/**
 * 巡检计划详情(P1-02 Task 7)。
 *
 * <p>GET /api/inspections/plans/{planId} 返回;含 {@code templateParamsJson /
 * thresholdsJson / scheduleConfigJson} 三个大字段,以及 {@code createdAt / updatedAt}。
 * Service 层序列化后此 DTO 透传给前端。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InspectionPlanDetail(
        String planId,
        String name,
        String description,
        InspectionTemplateType templateType,
        InspectionScheduleType scheduleType,
        String timezone,
        InspectionNotificationPolicy notificationPolicy,
        boolean enabled,
        LocalDateTime nextRunAt,
        LocalDateTime lastRunAt,
        long version,
        String templateParamsJson,
        String thresholdsJson,
        String scheduleConfigJson,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
