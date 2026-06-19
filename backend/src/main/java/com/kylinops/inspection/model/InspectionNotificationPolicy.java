package com.kylinops.inspection.model;

/**
 * 巡检执行结果的通知策略。
 * <ul>
 *   <li>ALWAYS:每次执行完成都通知</li>
 *   <li>ON_ABNORMAL:仅在 abnormal=true 时通知</li>
 *   <li>NEVER:不通知(只写审计/报告)</li>
 * </ul>
 */
public enum InspectionNotificationPolicy {
    ALWAYS,
    ON_ABNORMAL,
    NEVER
}