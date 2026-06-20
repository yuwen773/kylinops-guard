package com.kylinops.inspection.model;

/**
 * 巡检计划的调度周期类型。
 * 显式枚举值,禁止动态扩展以保证 RiskCheck 路径可静态校验。
 */
public enum InspectionScheduleType {
    DAILY,
    WEEKLY,
    MONTHLY
}