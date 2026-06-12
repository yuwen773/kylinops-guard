// Action confirmation API — thin wrapper over POST /api/actions/confirm.
//
// SAFETY CONTRACT — locked at the wire level:
//   * The request body is EXACTLY `{ actionId, confirm }`. No extras.
//   * We never forward the original toolName, target, params, or command.
//     The backend re-reads them from the persisted PendingAction row
//     (com.kylinops.executor.PendingAction) and uses @JsonIgnoreProperties
//     to reject any unknown field. Mirroring that on the frontend keeps
//     the contract honest: no client-side "soften" can sneak extra keys in.
//   * The frontend never auto-confirms an L2 PendingAction. The user must
//     click 确认执行 or 取消 in the ExecutionConfirmCard.
//
// Errors propagate as ApiError (transport or business). The page surfaces
// them verbatim and does NOT retry.

import { post } from './client';

/**
 * Payload for POST /api/actions/confirm.
 *
 * Note: `actionId` is the only identifier we forward. The original
 * `toolName` / `params` / `command` / `target` come from the server-side
 * PendingAction row; the backend will reject any attempt to overwrite
 * them via the request body.
 */
export interface ConfirmActionPayload {
  /** PendingAction id returned by /api/chat/send. */
  actionId: string;
  /** true = confirm execution, false = cancel. */
  confirm: boolean;
}

/**
 * Response shape mirrors com.kylinops.executor.PendingAction.
 * We only model the fields the UI actually reads; unknown fields are
 * preserved on the wire (axios drops nothing) and ignored here.
 */
export interface ConfirmActionResult {
  actionId?: string;
  auditId?: string;
  sessionId?: string;
  actionType?: string;
  toolName?: string;
  /** Mirrors com.kylinops.executor.PendingActionStatus. */
  status?: string;
  riskLevel?: string;
  /** ISO-8601 string; the backend serialises LocalDateTime as a string. */
  expiresAt?: string;
  executionResult?: string;
}

/**
 * Confirm or cancel an L2 PendingAction.
 *
 * The request body is locked to `{ actionId, confirm }` — no additional
 * fields are forwarded. The backend will reject unknown fields and the
 * tool/command/target are read from the persisted PendingAction, not the
 * request body.
 */
export function confirmAction(payload: ConfirmActionPayload): Promise<ConfirmActionResult> {
  // The body shape is locked: { actionId, confirm }. No `toolName`,
  // `command`, `target`, `params` — the backend re-reads these from the
  // persisted PendingAction row. Any drift here is a wire-contract bug.
  const body: ConfirmActionPayload = {
    actionId: payload.actionId,
    confirm: payload.confirm,
  };
  return post<ConfirmActionResult>('/api/actions/confirm', body);
}
