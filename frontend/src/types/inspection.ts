// Inspection plan DTOs — mirrors the backend
//   com.kylinops.inspection.api.InspectionTemplateView
//   com.kylinops.inspection.api.InspectionPlanSummary
//   com.kylinops.inspection.api.InspectionPlanDetail
//   com.kylinops.inspection.api.InspectionPlanRequest
//   com.kylinops.inspection.api.InspectionPlanUpdateRequest
//   com.kylinops.inspection.api.InspectionExecutionSummary
//   com.kylinops.inspection.api.InspectionExecutionDetail
//   com.kylinops.inspection.api.RunResponse
//
// All DTOs are Java records serialised by Jackson with @JsonInclude(NON_NULL):
//   * Enums default to their constant name on the wire (no @JsonValue).
//   * LocalDateTime serialises as ISO-8601 string without timezone (e.g.
//     "2026-06-12T10:00:00").
//   * LocalTime in InspectionPlanRequest is parsed as "HH:mm:ss"; in the
//     UpdateRequest the server accepts the same string shape (String localTime).
//   * Optional fields are simply absent on the wire when null.

/** Mirrors com.kylinops.inspection.model.InspectionTemplateType. */
export type InspectionTemplateType = 'HEALTH' | 'DISK' | 'SERVICE';

/** Mirrors com.kylinops.inspection.model.InspectionScheduleType. */
export type InspectionScheduleType = 'DAILY' | 'WEEKLY' | 'MONTHLY';

/** Mirrors com.kylinops.inspection.model.InspectionExecutionStatus. */
export type InspectionExecutionStatus =
  | 'RUNNING'
  | 'SUCCESS'
  | 'PARTIAL_SUCCESS'
  | 'FAILED'
  | 'SKIPPED';

/** Mirrors com.kylinops.inspection.model.InspectionNotificationPolicy. */
export type InspectionNotificationPolicy =
  | 'ALWAYS'
  | 'ON_ABNORMAL'
  | 'NEVER';

/** Mirrors com.kylinops.inspection.model.InspectionTriggerType. */
export type InspectionTriggerType = 'SCHEDULED' | 'MANUAL';

/**
 * Per-field form definition. Mirrors InspectionTemplateView.TemplateField.
 * The frontend renders the create / edit dialog by iterating over the
 * `fields` array on each template — no hard-coded per-template form
 * branches, so the contract stays the single source of truth.
 */
export interface InspectionTemplateField {
  /** Field name on the wire (e.g. "serviceName", "cpuWarningPercent"). */
  name: string;
  /** Chinese label rendered next to the input. */
  label: string;
  /** UI input kind: "string" | "number" | "select" | "list". */
  type: string;
  required: boolean;
  /** Default value as a string; null when no default. */
  defaultValue?: string | null;
  /**
   * Free-form constraints map. Known keys:
   *   * number type: { min: number, max: number }
   *   * string type: { description: string }
   * Unknown keys are passed through; the UI ignores them.
   */
  constraints: Record<string, unknown>;
}

/**
 * Mirrors com.kylinops.inspection.api.InspectionTemplateView.
 * Returned by GET /api/inspections/templates.
 */
export interface InspectionTemplateView {
  templateType: InspectionTemplateType;
  /** Chinese display name (e.g. "服务健康度巡检"). */
  displayName: string;
  fields: InspectionTemplateField[];
  /**
   * Per-tool risk level. Optional because the backend may omit the map
   * when the template has no key tools.
   */
  riskLevels?: Record<string, string>;
  keyToolNames?: string[];
}

/**
 * Mirrors com.kylinops.inspection.api.InspectionPlanSummary.
 * Returned by GET /api/inspections/plans.
 * Note: this is the list view; templateParams / thresholds / scheduleConfig
 * are not included — see InspectionPlanDetail for the full record.
 */
export interface InspectionPlanSummary {
  planId: string;
  name: string;
  description?: string | null;
  templateType: InspectionTemplateType;
  scheduleType: InspectionScheduleType;
  timezone: string;
  notificationPolicy: InspectionNotificationPolicy;
  enabled: boolean;
  /** ISO-8601 string (LocalDateTime). Optional — may be absent before first schedule. */
  nextRunAt?: string | null;
  /** ISO-8601 string. Optional — null before first run. */
  lastRunAt?: string | null;
  /** Optimistic-lock version. PUT must echo this. */
  version: number;
}

/**
 * Mirrors com.kylinops.inspection.api.InspectionPlanDetail.
 * Returned by:
 *   * GET /api/inspections/plans/{planId}
 *   * POST /api/inspections/plans (echoes the freshly created plan)
 *   * PUT /api/inspections/plans/{planId}
 *   * POST /api/inspections/plans/{planId}/{enable,disable}
 *
 * Large fields are stored as JSON strings (not parsed) so the UI can
 * present them verbatim and re-serialise on save.
 */
export interface InspectionPlanDetail extends InspectionPlanSummary {
  templateParamsJson: string;
  thresholdsJson: string;
  scheduleConfigJson: string;
  /** ISO-8601 string. */
  createdAt: string;
  /** ISO-8601 string. */
  updatedAt: string;
}

/**
 * Mirrors com.kylinops.inspection.api.InspectionPlanRequest.
 * Body of POST /api/inspections/plans.
 * `localTime` is serialised as "HH:mm:ss" (matches Java LocalTime).
 */
export interface InspectionPlanRequest {
  name: string;
  description?: string;
  templateType: InspectionTemplateType;
  /** Free-form per-template parameters (e.g. { serviceName: "nginx" }). */
  templateParams: Record<string, unknown>;
  /** Free-form threshold map (e.g. { cpuWarningPercent: 80 }). */
  thresholds: Record<string, unknown>;
  scheduleType: InspectionScheduleType;
  /** "HH:mm:ss" — required. */
  localTime: string;
  timezone: string;
  /** Required when scheduleType is WEEKLY. */
  dayOfWeek?: 'MONDAY' | 'TUESDAY' | 'WEDNESDAY' | 'THURSDAY' | 'FRIDAY' | 'SATURDAY' | 'SUNDAY';
  /** Required when scheduleType is MONTHLY. 1..31. */
  dayOfMonth?: number;
  notificationPolicy: InspectionNotificationPolicy;
}

/**
 * Mirrors com.kylinops.inspection.api.InspectionPlanUpdateRequest.
 * Body of PUT /api/inspections/plans/{planId}. All fields except `version`
 * are optional (partial update).
 *
 * 红线:version 必传;null 字段不覆盖现有值(由后端 Service 判定)。
 */
export interface InspectionPlanUpdateRequest {
  /** Required. Optimistic-lock version echoed from GET. */
  version: number;
  description?: string;
  templateType?: InspectionTemplateType;
  templateParams?: Record<string, unknown>;
  thresholds?: Record<string, unknown>;
  scheduleType?: InspectionScheduleType;
  /** "HH:mm:ss" string. */
  localTime?: string;
  timezone?: string;
  dayOfWeek?: 'MONDAY' | 'TUESDAY' | 'WEDNESDAY' | 'THURSDAY' | 'FRIDAY' | 'SATURDAY' | 'SUNDAY';
  dayOfMonth?: number;
  notificationPolicy?: InspectionNotificationPolicy;
}

/**
 * Mirrors com.kylinops.inspection.api.RunResponse.
 * Returned by POST /api/inspections/plans/{planId}/run.
 * The status is the snapshot at the moment the controller replied — it
 * may already be SUCCESS / FAILED for fast templates, or RUNNING for
 * slower ones. Frontend MUST poll until the execution leaves RUNNING.
 */
export interface RunResponse {
  executionId: string;
  status: InspectionExecutionStatus;
}

/**
 * Mirrors com.kylinops.inspection.api.InspectionExecutionSummary.
 * Returned by GET /api/inspections/executions.
 */
export interface InspectionExecutionSummary {
  planId: string;
  executionId: string;
  status: InspectionExecutionStatus;
  triggerType: InspectionTriggerType;
  operator: string;
  /** ISO-8601 string. */
  startedAt: string;
  /** ISO-8601 string. Optional — null while still running. */
  finishedAt?: string | null;
  /** Independent flag — a SUCCESS execution can still be abnormal. */
  abnormal: boolean;
  /** Up to 1024 chars (server-truncated). Optional. */
  summary?: string | null;
}

/**
 * Mirrors com.kylinops.inspection.api.InspectionExecutionDetail.
 * Returned by GET /api/inspections/executions/{executionId}.
 */
export interface InspectionExecutionDetail extends InspectionExecutionSummary {
  /** JSON snapshot of the plan at the time of execution. */
  planSnapshotJson: string;
  /** Cross-link to audit log detail; null when no audit was written. */
  auditId?: string | null;
  /** Cross-link to report; null when no report was generated. */
  reportId?: string | null;
  /** Failure reason (FAILED only); null for SUCCESS / RUNNING. */
  errorMessage?: string | null;
}

/**
 * Query params for GET /api/inspections/executions.
 * All fields are optional — omitted params lead to default server-side
 * filtering. `planId` and `status` are explicit filters; `page` and
 * `size` are paginated.
 */
export interface InspectionExecutionQuery {
  planId?: string;
  status?: InspectionExecutionStatus;
  page?: number;
  size?: number;
}
