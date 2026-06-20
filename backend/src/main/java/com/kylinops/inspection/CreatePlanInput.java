package com.kylinops.inspection;

import com.kylinops.inspection.model.InspectionNotificationPolicy;
import com.kylinops.inspection.model.InspectionScheduleType;
import com.kylinops.inspection.model.InspectionTemplateType;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Map;

/**
 * 创建巡检计划的输入 DTO(P1-02 Task 6)。
 *
 * <p>供 Service 层 {@link InspectionPlanService#createPlan} 接收参数,
 * 由 {@link InspectionPlanValidator} 完整校验后再写入。
 * 字段为 public 以便 controller / test 直接装配;Service 层不修改入参对象。</p>
 */
public class CreatePlanInput {

    /** 计划名,管理员侧去重键。 */
    public String name;

    /** 计划说明(可空)。 */
    public String description;

    /** 模板类型:HEALTH / DISK / SERVICE。 */
    public InspectionTemplateType templateType;

    /** 模板参数。 */
    public Map<String, Object> templateParams;

    /** 阈值。 */
    public Map<String, Object> thresholds;

    /** 调度周期:DAILY / WEEKLY / MONTHLY。 */
    public InspectionScheduleType scheduleType;

    /** 触发本地时间(HH:mm)。 */
    public LocalTime localTime;

    /** IANA 时区 ID。 */
    public String timezone;

    /** WEEKLY 必填,其它类型必为 null。 */
    public DayOfWeek dayOfWeek;

    /** MONTHLY 必填 1..28,其它类型必为 null。 */
    public Integer dayOfMonth;

    /** 通知策略。 */
    public InspectionNotificationPolicy notificationPolicy;
}