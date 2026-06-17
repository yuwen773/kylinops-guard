package com.kylinops.notification;

/**
 * 通知事件类型 — Notification Center emit 的事件分类。
 *
 * <p>见设计文档 §4.2：每个枚举值对应 AgentOrchestrator 中的一个发射点（C6 实现），
 * 并由 {@link NotificationEventFactory}（C3）将原始上下文转换为带
 * 严重等级/上下文字段的完整事件。</p>
 */
public enum NotificationEventType {

    /** L4 绝对阻断（rm -rf / 等） */
    L4_BLOCK,

    /** Prompt 注入拦截 */
    PROMPT_INJECTION_BLOCK,

    /** L2 待用户确认 */
    L2_CONFIRM_REQUIRED,

    /** 服务异常（SERVICE_DIAGNOSIS + RCA 置信度 ≥ 0.7） */
    SERVICE_ABNORMAL,

    /** 磁盘风险（DISK_DIAGNOSIS + 磁盘 ≥ 85% 或 RCA 置信度 ≥ 0.7） */
    DISK_RISK
}
