// Audit log API — thin wrapper over the unified /api/audit/* endpoints.
//
// This module owns the wire contract with the backend audit subsystem. It
// does NOT do any safety evaluation; the backend has already produced the
// final persisted state. The frontend only fetches + displays.
//
// Errors propagate as ApiError (transport OR business). The page surfaces
// them verbatim and does NOT retry / mutate.

import { get } from './client';
import type { AuditLogDetail } from '@/types/audit';

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
