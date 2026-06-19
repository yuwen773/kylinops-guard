package com.kylinops.inspection.model;

/**
 * 巡检单次执行的状态机终态(以及 RUNNING 中间态)。
 * <ul>
 *   <li>RUNNING:正在执行</li>
 *   <li>SUCCESS:全部步骤通过</li>
 *   <li>PARTIAL_SUCCESS:非关键步骤失败</li>
 *   <li>FAILED:关键步骤失败或预检拒绝</li>
 *   <li>SKIPPED:与上一次执行重叠,被抢占跳过</li>
 * </ul>
 */
public enum InspectionExecutionStatus {
    RUNNING,
    SUCCESS,
    PARTIAL_SUCCESS,
    FAILED,
    SKIPPED
}