package com.kylinops.inspection.api;

import com.kylinops.inspection.model.InspectionNotificationPolicy;
import com.kylinops.inspection.model.InspectionScheduleType;
import com.kylinops.inspection.model.InspectionTemplateType;
import jakarta.validation.constraints.NotNull;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Map;

/**
 * 巡检计划更新请求体(P1-02 Task 7)。
 *
 * <p>PUT /api/inspections/plans/{planId} 请求体;所有字段可选(部分更新),但
 * {@link #version} <b>必填</b>用于乐观锁。null 字段被 controller 透传到 Service,
 * 由 Service 决定是否覆盖现有值(避免误清空)。</p>
 */
public record InspectionPlanUpdateRequest(

        @NotNull(message = "[version] 不能为空")
        Long version,

        String description,

        InspectionTemplateType templateType,

        Map<String, Object> templateParams,

        Map<String, Object> thresholds,

        InspectionScheduleType scheduleType,

        String localTime,

        String timezone,

        DayOfWeek dayOfWeek,

        Integer dayOfMonth,

        InspectionNotificationPolicy notificationPolicy
) {
}
