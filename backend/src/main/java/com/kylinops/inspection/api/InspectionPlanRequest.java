package com.kylinops.inspection.api;

import com.kylinops.inspection.model.InspectionNotificationPolicy;
import com.kylinops.inspection.model.InspectionScheduleType;
import com.kylinops.inspection.model.InspectionTemplateType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Map;

/**
 * 创建巡检计划请求体(P1-02 Task 7)。
 *
 * <p>由 controller 接收,转换为 {@code CreatePlanInput} 后委托给
 * {@link com.kylinops.inspection.InspectionPlanService#createPlan}。
 * 业务校验由 {@code InspectionPlanValidator} 完成(放在 Service 内,
 * 保证手工调用 Service 的代码路径也走同一份校验)。</p>
 *
 * <p><b>反序列化安全</b>:使用 record,字段集合显式;不暴露 enabled / version /
 * nextRunAt / lastRunAt / createdBy / updatedBy 等服务端管理字段,防止请求体伪造。</p>
 */
public record InspectionPlanRequest(

        @NotBlank(message = "[name] 不能为空")
        String name,

        String description,

        @NotNull(message = "[templateType] 不能为空")
        InspectionTemplateType templateType,

        Map<String, Object> templateParams,

        Map<String, Object> thresholds,

        @NotNull(message = "[scheduleType] 不能为空")
        InspectionScheduleType scheduleType,

        @NotNull(message = "[localTime] 不能为空")
        LocalTime localTime,

        @NotBlank(message = "[timezone] 不能为空")
        String timezone,

        DayOfWeek dayOfWeek,

        Integer dayOfMonth,

        @NotNull(message = "[notificationPolicy] 不能为空")
        InspectionNotificationPolicy notificationPolicy
) {
}
