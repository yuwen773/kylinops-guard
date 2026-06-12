package com.kylinops.report;

/**
 * 报告类型枚举。
 * <p>
 * 对应演示视频脚本中的五大场景分类。所有报告类型仅作为分类标签使用，
 * 实际内容仍然只由 {@code AuditLogDetail} 确定性组装。
 * </p>
 *
 * <ul>
 *   <li>{@link #HEALTH} — 系统健康检查报告</li>
 *   <li>{@link #DISK} — 磁盘诊断报告</li>
 *   <li>{@link #SERVICE} — 服务诊断报告</li>
 *   <li>{@link #SECURITY} — 安全事件报告</li>
 *   <li>{@link #AUDIT} — 通用审计报告</li>
 * </ul>
 */
public enum ReportType {

    HEALTH,
    DISK,
    SERVICE,
    SECURITY,
    AUDIT
}