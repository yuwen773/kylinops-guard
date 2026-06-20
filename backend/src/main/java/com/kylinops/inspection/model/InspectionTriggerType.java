package com.kylinops.inspection.model;

/**
 * 巡检执行触发来源,用于审计 trigger_type 字段。
 * <ul>
 *   <li>SCHEDULED:定时调度触发</li>
 *   <li>MANUAL:管理员手动触发</li>
 * </ul>
 */
public enum InspectionTriggerType {
    SCHEDULED,
    MANUAL
}