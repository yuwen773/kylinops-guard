// Tool Center API — thin wrapper over GET /api/tools.
//
// This module owns the wire contract with the backend tool registry. It
// does NOT recompute safety verdicts or call statistics — the backend has
// already produced the merged DTO. The frontend only fetches + displays.
//
// Errors propagate as ApiError (transport OR business).

import { get } from './client';
import type { ToolDefinition } from '@/types/tool';

/**
 * GET /api/tools — list every registered tool with metadata + call stats.
 *
 * Wire contract (mirrors com.kylinops.tool.ToolController#listTools):
 *   - No query parameters, no body.
 *   - Response: ApiResponse<ToolDefinition[]> → unwrapped to ToolDefinition[].
 *   - Each entry includes callCount (long), successRate (Double|null),
 *     lastCalledAt (Instant|null) from a single server-side grouped
 *     aggregate — the frontend NEVER issues per-tool stats queries.
 */
export function getTools(): Promise<ToolDefinition[]> {
  return get<ToolDefinition[]>('/api/tools');
}

/**
 * GET /api/tools/{toolName} — single tool detail.
 *
 * @param toolName the unique tool name
 */
export function getTool(toolName: string): Promise<ToolDefinition> {
  return get<ToolDefinition>(`/api/tools/${encodeURIComponent(toolName)}`);
}