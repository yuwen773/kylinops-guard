package com.kylinops.notification;

/**
 * 通知严重等级 — 用于通知通道（飞书颜色/手机推送级别/Webhook 优先级）。
 *
 * <p>见设计文档 §4.3：</p>
 * <ul>
 *   <li>{@link #CRITICAL} — 严重：必须立即处理（L4_BLOCK / PROMPT_INJECTION_BLOCK）</li>
 *   <li>{@link #WARNING} — 警告：需要关注（L2_CONFIRM_REQUIRED）</li>
 *   <li>{@link #INFO} — 信息：仅供参考（SERVICE_ABNORMAL / DISK_RISK）</li>
 * </ul>
 */
public enum NotificationSeverity {

    /** 严重：必须立即处理 */
    CRITICAL,

    /** 警告：需要关注 */
    WARNING,

    /** 信息：仅供参考 */
    INFO
}
