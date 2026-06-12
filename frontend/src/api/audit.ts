// Audit log API — thin wrapper over the unified /api/audit/* endpoints.
//
// This module owns the wire contract with the backend audit subsystem. It
// does NOT do any safety evaluation; the backend has already produced the
// final persisted state. The frontend only fetches + displays.
//
// Errors propagate as ApiError (transport OR business). The page surfaces
// them verbatim and does NOT retry / mutate.

import { get } from './client';
import type { AuditLogDetail, AuditLogPage } from '@/types/audit';

/**
 * Query the audit log list with pagination and composite filters.
 *
 * Wire contract (mirrors com.kylinops.audit.AuditLogController#listLogs):
 *   - riskLevel (L0..L4, optional)
 *   - status    (AuditStatus, optional)
 *   - keyword   (substring of userInput, optional)
 *   - startTime / endTime (ISO-8601, optional)
 *   - page      (default 0)
 *   - size      (default 20)
 *
 * The backend returns a Page<AuditLogSummary>; the toolCallCount field is
 * populated server-side via a single grouped aggregate — there is no per-row
 * call from this module.
 */
export interface AuditLogQuery {
  page?: number;
  size?: number;
  riskLevel?: string;
  status?: string;
  keyword?: string;
  startTime?: string;
  endTime?: string;
}

export function getAuditLogs(query: AuditLogQuery = {}): Promise<AuditLogPage> {
  const params: Record<string, string | number> = {};
  if (query.page !== undefined) params.page = query.page;
  if (query.size !== undefined) params.size = query.size;
  if (query.riskLevel) params.riskLevel = query.riskLevel;
  if (query.status) params.status = query.status;
  if (query.keyword) params.keyword = query.keyword;
  if (query.startTime) params.startTime = query.startTime;
  if (query.endTime) params.endTime = query.endTime;

  return get<AuditLogPage>('/api/audit/logs', { params });
}

/**
 * Fetch a single audit log detail by id.
 *
 * Used after an L2 confirmation to refresh the persisted final state
 * (confirmationStatus, executionResult, updatedAt) so the UI can show the
 * backend's view of truth — never derive status locally.
 */
export function getAuditDetail(auditId: string): Promise<AuditLogDetail> {
  // Path parameter is the auditId; the backend path is /api/audit/logs/{id}.
  // No query string, no body. The URL is the only variable.
  return get<AuditLogDetail>(`/api/audit/logs/${encodeURIComponent(auditId)}`);
}