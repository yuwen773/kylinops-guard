package com.kylinops.executor;

/**
 * PendingAction 生命周期状态
 * <p>
 * {@code WAITING} → {@code CONFIRMED} → {@code EXECUTING} → {@code SUCCESS}
 *                                                  ↘ {@code FAILED}
 * {@code WAITING} → {@code CANCELLED}
 * {@code WAITING} → {@code EXPIRED} （超时自动触发）
 * </p>
 */
public enum PendingActionStatus {
    /** 待确认（初始状态） */
    WAITING,
    /** 用户已确认 */
    CONFIRMED,
    /** 用户已取消 */
    CANCELLED,
    /** 执行中 */
    EXECUTING,
    /** 执行成功 */
    SUCCESS,
    /** 执行失败 */
    FAILED,
    /** 已过期（超时未确认） */
    EXPIRED
}
