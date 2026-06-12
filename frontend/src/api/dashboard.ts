// Dashboard API — thin wrapper over the unified /api/dashboard endpoint.
//
// This module owns the wire contract with the backend dashboard subsystem
// (com.kylinops.dashboard.DashboardController). It does NOT do any safety
// evaluation; the backend has already produced the persisted verdict and
// the score. The frontend only fetches + displays.
//
// Errors propagate as ApiError (transport OR business). The page surfaces
// them verbatim and degrades the failing section locally only — previously
// loaded data is preserved.

import { get } from './client';
import type { DashboardOverview } from '@/types/dashboard';

/**
 * GET /api/dashboard/overview — pull a fresh system overview snapshot.
 *
 * Wire contract (mirrors com.kylinops.dashboard.DashboardController#getOverview):
 *   - No query parameters, no body.
 *   - Response: ApiResponse<DashboardOverview> → unwrapped to DashboardOverview.
 *   - The backend always returns HTTP 200 (single-tool failures are
 *     captured as DashboardMetric.status="failed" inside the payload,
 *     not as an HTTP error). True transport failures still raise ApiError.
 */
export function getDashboardOverview(): Promise<DashboardOverview> {
  return get<DashboardOverview>('/api/dashboard/overview');
}
