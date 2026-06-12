package com.kylinops.common.enums;

import java.util.EnumSet;
import java.util.Set;

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
    BLOCKED;

    /**
     * 终态集合 — 调用进入这些状态后不再变更。
     * <p>
     * 用于工具调用成功率（successRate）的分母：
     * terminal = SUCCESS + FAILED + TIMEOUT + BLOCKED。
     * PENDING/RUNNING 不计入分母（尚未完成）。
     * </p>
     */
    public static final Set<ToolCallStatus> TERMINAL_STATUSES =
            EnumSet.of(SUCCESS, FAILED, TIMEOUT, BLOCKED);

    /**
     * 是否为终态。
     *
     * @return true when the call has reached a terminal state (SUCCESS /
     *         FAILED / TIMEOUT / BLOCKED)
     */
    public boolean isTerminal() {
        return TERMINAL_STATUSES.contains(this);
    }
}