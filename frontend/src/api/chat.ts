// Chat API — thin wrapper over the unified /api/chat/send endpoint.
//
// This module owns the wire contract with the backend. It does NOT do any
// safety evaluation; the backend already returns the final riskLevel /
// riskDecision / auditId for the request. Anything that touches the
// backend's safety verdict (display, gating) lives in the page component.
//
// IMPORTANT:
//   * Request body MUST be exactly { content, sessionId? }. Sending a
//     `message` field would silently deserialise to null on the backend
//     and the request would 400 with "消息内容不能为空".
//   * Errors propagate as ApiError (transport OR business). The page
//     surfaces them verbatim and does NOT retry / mutate.

import { post } from './client';
import type { AgentResult, ChatRequest } from '@/types/agent';

/**
 * Send one chat turn to the Agent.
 *
 * @param payload.content    The natural-language user input (required).
 * @param payload.sessionId  Optional. Pass the sessionId returned by the
 *                           previous response so the backend can append to
 *                           the same conversation.
 */
export function sendChat(payload: ChatRequest): Promise<AgentResult> {
  // The body shape is locked: { content, sessionId? }. No `message` field,
  // no extra envelopes, no metadata. Any drift here is a wire-contract bug.
  const body: ChatRequest = {
    content: payload.content,
  };
  if (payload.sessionId) {
    body.sessionId = payload.sessionId;
  }
  return post<AgentResult>('/api/chat/send', body);
}
