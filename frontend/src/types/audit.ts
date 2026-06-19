// Audit log DTOs — mirrors the backend
//   com.kylinops.audit.AuditLogSummary
//   com.kylinops.audit.AuditLogDetail (and nested ToolCallInfo / RiskCheckInfo /
//   PendingActionInfo)
//   org.springframework.data.domain.Page<T>  (wire shape: { content, totalElements,
//   totalPages, number, size })
//
// The frontend NEVER recomputes any safety field. Backend values are rendered
// verbatim. The only local logic allowed is:
//   * mapping the server-side status / decision codes onto Chinese labels
//   * defensive JSON.parse for fields that look like JSON (matchedRules,
//     actionPlan, executionResult, etc.); on parse failure we fall back to
//     the raw sanitized string instead of crashing.

import type { RiskDecision, RiskLevel } from './safety';

/** Mirrors com.kylinops.audit.AuditLogSummary. */
export interface AuditLogSummary {
  auditId: string;
  sessionId?: string;
  /** Sanitized + truncated user input. */
  userInput?: string;
  intentType?: string;
  riskLevel?: RiskLevel;
  riskDecision?: RiskDecision;
  /** Mirrors com.kylinops.common.enums.AuditStatus. */
  status?: string;
  confirmationRequired?: boolean;
  confirmationStatus?: string;
  message?: string;
  /**
   * Number of ToolCallRecord rows associated with this audit.
   * Populated server-side via a single grouped aggregate
   * (`countByAuditIdInGrouped`) — never derived in the frontend.
   */
  toolCallCount?: number;
  createdAt?: string;
}

/** Mirrors org.springframework.data.domain.Page wire shape. */
export interface AuditLogPage {
  content: AuditLogSummary[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

/** Mirrors com.kylinops.audit.AuditLogDetail.ToolCallInfo. */
export interface AuditToolCallInfo {
  toolCallId?: string;
  toolName?: string;
  status?: string;
  input?: string;
  output?: string;
  errorMessage?: string;
  durationMs?: number;
}

/** Mirrors com.kylinops.audit.AuditLogDetail.RiskCheckInfo. */
export interface AuditRiskCheckInfo {
  riskCheckId?: string;
  targetType?: string;
  riskLevel?: RiskLevel | string;
  riskDecision?: RiskDecision | string;
  matchedRules?: string;
  reason?: string;
  checkedAt?: string;
}

/** Mirrors com.kylinops.audit.AuditLogDetail.PendingActionInfo. */
export interface AuditPendingActionInfo {
  actionId?: string;
  actionType?: string;
  toolName?: string;
  status?: string;
  executionResult?: string;
}

/** Mirrors com.kylinops.notification.NotificationRecordSummary. */
export interface NotificationRecordSummary {
  recordId: string;
  eventId: string;
  auditId: string;
  channelId: string;
  channelType: 'WEBHOOK' | 'FEISHU';
  status: 'PENDING' | 'SENT' | 'FAILED' | 'SKIPPED';
  responseCode: number | null;
  errorMessage: string | null;
  retryCount: number;
  sentAt: string | null;
  createdAt: string;
}

/** Mirrors com.kylinops.audit.AuditLogDetail. */
export interface AuditLogDetail {
  auditId: string;
  sessionId?: string;
  userInput?: string;
  intentType?: string;
  riskLevel?: RiskLevel;
  riskDecision?: RiskDecision;
  /** Mirrors com.kylinops.common.enums.AuditStatus. */
  status?: string;
  message?: string;
  matchedRules?: string;
  actionPlan?: string;
  confirmationRequired?: boolean;
  confirmationStatus?: string;
  executionResult?: string;
  finalAnswer?: string;
  warning?: string;
  createdAt?: string;
  updatedAt?: string;
  toolCalls?: AuditToolCallInfo[];
  riskChecks?: AuditRiskCheckInfo[];
  pendingAction?: AuditPendingActionInfo;
  notificationRecords?: NotificationRecordSummary[] | null;
}