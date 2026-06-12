// Agent / Chat DTOs — single source of truth for the frontend wire format.
//
// These mirror com.kylinops.agent.AgentResult and its inner classes. Field
// order and types must stay aligned with the Java definition so JSON
// deserialization is lossless. The ChatConsole MUST render the backend
// verdict verbatim — never derive a softer level or decision here.
//
// SAFETY CONTRACT:
//   * The frontend does NOT recompute riskLevel / riskDecision. It only
//     displays what /api/chat/send returned.
//   * needConfirmation + pendingAction exist solely so the UI can disable
//     the input until the user decides. The decision to confirm/cancel is
//     sent to the backend; the frontend never auto-confirms.

import type { RiskDecision, RiskLevel, ToolCallDisplayStatus } from './safety';

/** Mirrors com.kylinops.common.enums.IntentType (string form). */
export type IntentType =
  | 'HEALTH_CHECK'
  | 'DISK_DIAGNOSIS'
  | 'SERVICE_DIAGNOSIS'
  | 'SERVICE_OPERATION'
  | 'PROCESS_INQUIRY'
  | 'NETWORK_INQUIRY'
  | 'LOG_INQUIRY'
  | 'UNKNOWN';

/** Mirrors com.kylinops.agent.AgentResult.ToolCallInfo. */
export interface ToolCallDto {
  toolName: string;
  /** Raw status from the backend: success | failed | timeout | blocked. */
  status: ToolCallDisplayStatus | string;
  summary?: string;
  durationMs?: number;
}

/** Mirrors com.kylinops.agent.AgentResult.PendingAction. */
export interface PendingActionDto {
  actionId: string;
  toolName: string;
  params?: Record<string, unknown>;
  description?: string;
}

/** Mirrors com.kylinops.agent.AgentResult. */
export interface AgentResult {
  sessionId?: string;
  answer?: string;
  intentType?: IntentType | string;
  toolCalls?: ToolCallDto[];
  riskLevel?: RiskLevel;
  riskDecision?: RiskDecision;
  needConfirmation?: boolean;
  pendingAction?: PendingActionDto;
  auditId?: string;
  errorMessage?: string;
}

/** Request body for POST /api/chat/send. */
export interface ChatRequest {
  /** Required. The natural-language user input. MUST NOT contain a `message` field. */
  content: string;
  /** Optional. Reuse the sessionId returned by the previous response. */
  sessionId?: string;
}
