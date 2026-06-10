package com.kylinops.common.enums;

/**
 * Tool 调用状态
 * <p>
 * 跟踪每个 OpsTool 从注册到执行完成的完整生命周期。
 * </p>
 */
public enum ToolCallStatus {

    /** 待执行 — 已编排但尚未调用 */
    PENDING,

    /** 正在执行 — ToolExecutor 正在运行 */
    RUNNING,

    /** 执行成功 */
    SUCCESS,

    /** 执行失败（返回结构化错误） */
    FAILED,

    /** 执行超时 */
    TIMEOUT,

    /** 被安全规则阻断 */
    BLOCKED
}
