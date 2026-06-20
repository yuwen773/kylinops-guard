package com.kylinops.inspection.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.kylinops.inspection.model.InspectionNotificationPolicy;
import com.kylinops.inspection.model.InspectionScheduleType;
import com.kylinops.inspection.model.InspectionTemplateType;

import java.time.LocalDateTime;

/**
 * 巡检计划列表项(P1-02 Task 7)。
 *
 * <p>GET /api/inspections/plans 列表项;不含 JSON 大字段(templateParams / thresholds /
 * scheduleConfig 完整 JSON),由 {@link InspectionPlanDetail} 在详情接口提供。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InspectionPlanSummary(
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
        long version
) {
}
