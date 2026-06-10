package com.kylinops.common.enums;

/**
 * 审计日志状态
 * <p>
 * 记录每个请求从发起到完成的审计状态变迁。
 * </p>
 */
public enum AuditStatus {

    /** 请求已接收 — 审计记录已创建 */
    RECEIVED,

    /** 已通过安全校验 — 风险检查完成 */
    RISK_CHECKED,

    /** 等待用户确认 — L2 操作已生成 PendingAction */
    CONFIRM_PENDING,

    /** 用户已确认 — L2 操作获批准 */
    CONFIRMED,

    /** 用户已取消 — L2 操作被拒绝 */
    CANCELLED,

    /** 执行成功 — 操作完成 */
    SUCCESS,

    /** 被安全规则阻断 — L3/L4 操作被拦截 */
    BLOCKED,

    /** 执行失败 — 操作异常终止 */
    FAILED
}
