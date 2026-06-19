package com.kylinops.inspection;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Objects;

/**
 * 巡检计划调度配置:不可变 record。语义校验(类型必填字段、范围)由
 * {@link InspectionScheduleCalculator#nextRun} 统一执行,以便调用方拿到清晰的 IllegalArgumentException。
 *
 * @param localTime  触发本地时间(必填)
 * @param dayOfWeek  WEEKLY 必填,DAILY/MONTHLY 必为 null
 * @param dayOfMonth MONTHLY 必填,且范围 1..28(避免 Feb 闰年歧义)
 */
public record InspectionScheduleConfig(
        LocalTime localTime,
        DayOfWeek dayOfWeek,
        Integer dayOfMonth) {

    public InspectionScheduleConfig {
        Objects.requireNonNull(localTime, "localTime must not be null");
    }
}