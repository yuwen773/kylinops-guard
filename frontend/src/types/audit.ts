// Audit log detail DTOs — mirrors com.kylinops.audit.AuditLogDetail and its
// nested classes (ToolCallInfo, RiskCheckInfo, PendingActionInfo).
//
// The frontend NEVER recomputes these fields. They are rendered verbatim —
// the only local logic allowed is mapping the server-side status / decision
// codes onto Chinese labels.

import type { RiskDecision, RiskLevel } from './safety';

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
}
