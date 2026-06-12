// Reports API — thin wrapper over the unified /api/reports/* endpoints.
//
// This module owns the wire contract with the backend report subsystem. It
// does NOT do any safety evaluation; the backend has already produced the
// final report body. The frontend only fetches + displays.
//
// SAFETY CONTRACT:
//   * The request to POST /api/reports/generate must include at least one
//     of { auditId, sessionId } — we do not forward user-provided shell
//     content or commands here.
//   * Errors propagate as ApiError (transport OR business). The page
//     surfaces them verbatim and does NOT retry / mutate.

import { get, post } from './client';
import type {
  ReportDetail,
  ReportGenerateRequest,
  ReportPage,
  ReportSummary,
} from '@/types/report';

export interface ReportListQuery {
  page?: number;
  size?: number;
}

/**
 * Query the paginated report list.
 *
 * Wire contract (mirrors com.kylinops.report.ReportController#list):
 *   - page (default 0)
 *   - size (default 20)
 */
export function getReports(query: ReportListQuery = {}): Promise<ReportPage> {
  const params: Record<string, number> = {};
  if (query.page !== undefined) params.page = query.page;
  if (query.size !== undefined) params.size = query.size;
  return get<ReportPage>('/api/reports', { params });
}

/**
 * Fetch a single report detail by id.
 *
 * Used both by the ReportCenter detail drawer and by ChatConsole's
 * "open this report" navigation when the user has just generated one.
 */
export function getReportDetail(reportId: string): Promise<ReportDetail> {
  return get<ReportDetail>(`/api/reports/${encodeURIComponent(reportId)}`);
}

/**
 * Generate a new report from an existing audit (or session).
 *
 * The request body shape is exactly the backend's ReportGenerateRequest
 * DTO. At least one of `auditId` / `sessionId` must be set; otherwise the
 * backend returns 400 and we surface that as an ApiError.
 */
export function generateReport(
  payload: ReportGenerateRequest,
): Promise<ReportDetail> {
  const body: ReportGenerateRequest = {};
  if (payload.auditId) body.auditId = payload.auditId;
  if (payload.sessionId) body.sessionId = payload.sessionId;
  if (payload.reportType) body.reportType = payload.reportType;
  return post<ReportDetail>('/api/reports/generate', body);
}

/** Re-export the summary DTO so callers don't have to import from types. */
export type { ReportDetail, ReportPage, ReportSummary };