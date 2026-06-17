package com.kylinops.notification;

import com.kylinops.common.enums.IntentType;
import com.kylinops.common.enums.RiskDecision;
import com.kylinops.common.enums.RiskLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 通知事件 — Notification Center 的核心 DTO。
 *
 * <p><b>设计原则</b>（见设计文档 §4.1）：</p>
 * <ul>
 *   <li><b>可变 DTO</b>（不是不可变 POJO）：由 builder 创建；{@code NotificationService.emit}
 *       入口补 eventId / occurredAt 字段。</li>
 *   <li>强类型字段（不用 {@code Map<String, Object>}，避免运行时类型错误）</li>
 *   <li>按 eventType 决定哪些字段有效，便于审计回放</li>
 *   <li>setter 仅供 Service 内部使用，AgentOrchestrator 业务代码不调 setter</li>
 * </ul>
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class NotificationEvent {

    /** 事件唯一 ID（UUID，由 NotificationService 生成；与 channelId 联合唯一） */
    private String eventId;

    /** 事件类型 */
    private NotificationEventType eventType;

    /** 严重等级 */
    private NotificationSeverity severity;

    /** 通知标题（人类可读） */
    private String title;

    /** 通知摘要（≤ 200 字） */
    private String summary;

    /** 通知详情（≤ 500 字，超出截断） */
    private String detail;

    /** 会话 ID（来自 AgentRequest） */
    private String sessionId;

    /** 审计 ID（与 AuditLog.auditId 关联） */
    private String auditId;

    /** 风险等级（L4_BLOCK 时填，其他可空） */
    private RiskLevel riskLevel;

    /** 风险决策（BLOCK / CONFIRM / ALLOW） */
    private RiskDecision riskDecision;

    /** 意图类型（运维类事件时填） */
    private IntentType intentType;

    // ───── 强类型 context 字段（按 eventType 取舍） ─────

    /** 命中的风险规则 ID（L4_BLOCK 时填，如 "rm_root_recursive"） */
    private String matchedRuleId;

    /** 服务名（SERVICE_ABNORMAL 时填） */
    private String serviceName;

    /** 磁盘路径（DISK_RISK 时填） */
    private String diskPath;

    /** 磁盘使用率（DISK_RISK 时填，0.0-100.0） */
    private Double diskUsagePercent;

    /** Prompt 注入命中的模式（PROMPT_INJECTION_BLOCK 时填） */
    private String promptInjectionPattern;

    /** RCA 置信度（运维类事件时填，0.0-1.0） */
    private Double rcaConfidence;

    /** 发生时间（Clock 注入） */
    private LocalDateTime occurredAt;
}
