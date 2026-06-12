// Dashboard DTOs — mirrors the backend
//   com.kylinops.dashboard.DashboardOverview
//   com.kylinops.dashboard.DashboardMetric
//
// The frontend NEVER recomputes the health score or any safety verdict.
// Backend values are rendered verbatim. Local logic is limited to:
//   * mapping tool names onto display titles / units
//   * deriving per-metric display tone (warning / critical) from numeric
//     thresholds — purely cosmetic, never changes the backend score
//   * safely parsing the optional `data` payload (a backend Map serialized
//     as a JSON object) without crashing the page
//
// Hard rules:
//   * No hard-coded OS numbers. Every displayed value is read from the
//     backend payload or labelled "数据不可用" / "—".
//   * Failed metrics render independently — one tool failure does not
//     blank the whole page.

/** Mirrors com.kylinops.dashboard.DashboardOverview. */
export interface DashboardOverview {
  /** 0-100 health score; null when every metric failed (never a fake value). */
  score: number | null;

  /** Number of metrics that completed with status === 'success'. */
  successfulMetricCount: number;

  /** Total number of metrics attempted in the last collection cycle. */
  totalMetricCount: number;

  /** True when at least one metric failed/timeout/blocked. */
  degraded: boolean;

  /** Audit id that scopes this collection cycle. */
  auditId: string;

  /** ISO-8601 collection finish time from the backend. */
  collectedAt: string;

  /** Per-tool metric list. Order is backend-determined. */
  metrics: DashboardMetric[];
}

/** Mirrors com.kylinops.dashboard.DashboardMetric. */
export interface DashboardMetric {
  toolName: string;
  /** "success" | "failed" | "timeout" | "blocked". */
  status: string;
  /** Backend Map serialized as a JSON object; null/undefined on failure. */
  data?: Record<string, unknown> | null;
  /** Failure / timeout / blocked reason; absent on success. */
  errorMessage?: string;
  /** Per-tool call duration in milliseconds. */
  durationMs: number;
}
