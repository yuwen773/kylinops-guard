// Inspection plan API — thin wrappers over the runtime-config CRUD
// endpoints exposed by Task 7 of P1-02, plus the immediate-execute and
// execution-history endpoints.
//
//   com.kylinops.inspection.api.InspectionController
//
// Contract (locked):
//   * GET    /api/inspections/templates                        → InspectionTemplateView[]
//   * GET    /api/inspections/plans                            → InspectionPlanSummary[]
//   * POST   /api/inspections/plans                            → InspectionPlanDetail
//   * GET    /api/inspections/plans/{planId}                   → InspectionPlanDetail
//   * PUT    /api/inspections/plans/{planId}                   → InspectionPlanDetail
//   * POST   /api/inspections/plans/{planId}/enable            → InspectionPlanDetail
//   * POST   /api/inspections/plans/{planId}/disable           → InspectionPlanDetail
//   * DELETE /api/inspections/plans/{planId}                   → void
//   * POST   /api/inspections/plans/{planId}/run               → RunResponse
//   * GET    /api/inspections/executions?planId=&status=&page=&size= → InspectionExecutionSummary[]
//   * GET    /api/inspections/executions/{executionId}         → InspectionExecutionDetail
//
// 红线:
//   * runPlan 绝不传 body — operator 永远从 session 取,前端不传
//   * updatePlan 必须带 version 字段(乐观锁)
//   * deletePlan / enable / disable / run 都不带 body 和 query string

import { del, get, post, put } from './client';
import type {
  InspectionExecutionDetail,
  InspectionExecutionQuery,
  InspectionExecutionSummary,
  InspectionPlanDetail,
  InspectionPlanRequest,
  InspectionPlanSummary,
  InspectionPlanUpdateRequest,
  InspectionTemplateView,
  RunResponse,
} from '@/types/inspection';

/**
 * GET /api/inspections/templates — returns the full template catalogue
 * (HEALTH / DISK / SERVICE). The frontend drives the create / edit
 * dialog by iterating over each entry's `fields` array; no per-template
 * hard-coded form branches.
 */
export function getTemplates(): Promise<InspectionTemplateView[]> {
  return get<InspectionTemplateView[]>('/api/inspections/templates');
}

/**
 * GET /api/inspections/plans — list view (first 100, server-clamped).
 * No filter params: the page does not paginate plans.
 */
export function listPlans(): Promise<InspectionPlanSummary[]> {
  return get<InspectionPlanSummary[]>('/api/inspections/plans');
}

/**
 * GET /api/inspections/plans/{planId} — full record including
 * templateParamsJson / thresholdsJson / scheduleConfigJson + audit
 * timestamps.
 */
export function getPlan(planId: string): Promise<InspectionPlanDetail> {
  return get<InspectionPlanDetail>(`/api/inspections/plans/${planId}`);
}

/**
 * POST /api/inspections/plans — create a new plan. Body shape is
 * {@link InspectionPlanRequest}; the server stamps enabled=false,
 * version=0, and an empty nextRunAt on first persist.
 */
export function createPlan(payload: InspectionPlanRequest): Promise<InspectionPlanDetail> {
  return post<InspectionPlanDetail>('/api/inspections/plans', payload);
}

/**
 * PUT /api/inspections/plans/{planId} — partial update. The
 * `version` field on the body is the optimistic-lock check; mismatch
 * returns 409 and the caller should re-fetch via getPlan().
 */
export function updatePlan(
  planId: string,
  payload: InspectionPlanUpdateRequest,
): Promise<InspectionPlanDetail> {
  return put<InspectionPlanDetail>(`/api/inspections/plans/${planId}`, payload);
}

/**
 * POST /api/inspections/plans/{planId}/enable — flip enabled=true.
 * No body. The server returns the fresh detail view.
 */
export function enablePlan(planId: string): Promise<InspectionPlanDetail> {
  return post<InspectionPlanDetail>(`/api/inspections/plans/${planId}/enable`);
}

/**
 * POST /api/inspections/plans/{planId}/disable — flip enabled=false.
 * No body. The server returns the fresh detail view.
 */
export function disablePlan(planId: string): Promise<InspectionPlanDetail> {
  return post<InspectionPlanDetail>(`/api/inspections/plans/${planId}/disable`);
}

/**
 * DELETE /api/inspections/plans/{planId} — soft-delete the plan row.
 * No body, no query string. Historical executions, reports, and audit
 * rows are NOT deleted by this call.
 */
export function deletePlan(planId: string): Promise<void> {
  return del<void>(`/api/inspections/plans/${planId}`);
}

/**
 * POST /api/inspections/plans/{planId}/run — trigger an immediate
 * execution. operator is taken from the session by the backend; the
 * frontend MUST NOT send any body. Returns the new executionId plus
 * a status snapshot; the UI then polls getExecution() until the
 * status leaves RUNNING.
 */
export function runPlan(planId: string): Promise<RunResponse> {
  return post<RunResponse>(`/api/inspections/plans/${planId}/run`);
}

/**
 * GET /api/inspections/executions — paginated execution list. All four
 * query params are optional; when omitted the server defaults to
 * page=0, size=20 with no planId / status filter.
 */
export function listExecutions(
  query: InspectionExecutionQuery = {},
): Promise<InspectionExecutionSummary[]> {
  const params: Record<string, string | number> = {};
  if (query.planId !== undefined) params.planId = query.planId;
  if (query.status !== undefined) params.status = query.status;
  if (query.page !== undefined) params.page = query.page;
  if (query.size !== undefined) params.size = query.size;
  return get<InspectionExecutionSummary[]>('/api/inspections/executions', {
    params,
  });
}

/**
 * GET /api/inspections/executions/{executionId} — full execution
 * detail including planSnapshotJson, auditId, reportId, errorMessage.
 * Used by the post-run polling loop to surface the final state.
 */
export function getExecution(executionId: string): Promise<InspectionExecutionDetail> {
  return get<InspectionExecutionDetail>(
    `/api/inspections/executions/${executionId}`,
  );
}
