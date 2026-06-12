/**
 * Shared safety / evidence types used by the frontend.
 *
 * These mirror backend enums (com.kylinops.common.enums) and are the SINGLE
 * source of truth for risk / decision / tool-call status values in the UI.
 * Components MUST receive the backend-returned value as-is and render it
 * verbatim — no derivation, no "softening" of L3/L4 to L2 to make the demo
 * look friendlier. The risk decision is the backend's, not the frontend's.
 *
 * If the backend adds a new enum value, extend the unions here AND update
 * the matching backend enum in the same change.
 */

/** Mirrors com.kylinops.common.enums.RiskLevel. */
export type RiskLevel = 'L0' | 'L1' | 'L2' | 'L3' | 'L4';

/** Mirrors com.kylinops.common.enums.RiskDecision. */
export type RiskDecision = 'ALLOW' | 'CONFIRM' | 'BLOCK';

/** Mirrors com.kylinops.common.enums.ToolCallStatus. */
export type ToolCallStatus =
  | 'PENDING'
  | 'RUNNING'
  | 'SUCCESS'
  | 'FAILED'
  | 'TIMEOUT'
  | 'BLOCKED';

/** Subset of ToolCallStatus that the ToolCallCard UI is allowed to display. */
export type ToolCallDisplayStatus = 'success' | 'failed' | 'timeout' | 'blocked';

/**
 * Human-readable Chinese label for a risk level. The level code (L0..L4) is
 * also shown verbatim in the tag — we never replace the code with this label.
 */
export const RISK_LEVEL_LABELS: Readonly<Record<RiskLevel, string>> = {
  L0: '信息查询',
  L1: '轻度风险',
  L2: '需确认',
  L3: '高风险',
  L4: '严重风险',
};

/** Human-readable Chinese label for a risk decision. */
export const RISK_DECISION_LABELS: Readonly<Record<RiskDecision, string>> = {
  ALLOW: '允许',
  CONFIRM: '待确认',
  BLOCK: '阻断',
};

/** Human-readable Chinese label for a tool-call display status. */
export const TOOL_CALL_LABELS: Readonly<Record<ToolCallDisplayStatus, string>> = {
  success: '执行成功',
  failed: '执行失败',
  timeout: '执行超时',
  blocked: '已阻断',
};
