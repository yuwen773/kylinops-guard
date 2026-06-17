package com.kylinops.notification;

/**
 * 通知发送状态 — 一条 {@link NotificationRecord} 的生命周期状态。
 *
 * <p>见设计文档 §4.4。本期（P1-01）固定 retry=0，不引入 {@code RETRYING}
 * 状态；P1-02 启用重试时再扩展。</p>
 */
public enum NotificationStatus {

    /** 已入队，等待发送（dispatcher 记录创建时初始状态） */
    PENDING,

    /** 发送成功 */
    SENT,

    /** 发送失败 */
    FAILED,

    /** dry-run=true；按配置通道生成记录，但不真实发送 */
    SKIPPED
}
