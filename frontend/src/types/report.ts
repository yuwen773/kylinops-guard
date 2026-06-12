// Report DTOs — mirrors the backend
//   com.kylinops.report.ReportSummary
//   com.kylinops.report.ReportDetail
//   com.kylinops.report.ReportType
//   com.kylinops.report.ReportGenerateRequest
//
// SAFETY CONTRACT:
//   * The frontend NEVER recomputes or fabricates report fields. Every
//     rendered value comes verbatim from the backend. When the backend
//     marks a field as missing (the report body says 数据不可用), we do not
//     invent a replacement.
//   * The Markdown body may contain user-influenced content (audit
//     summaries, tool outputs). It is rendered with markdown-it in a
//     strictly sanitized configuration (html:false). It is NEVER passed
//     through v-html without that renderer gating it first.

import type { RiskDecision, RiskLevel } from './safety';

/** Mirrors com.kylinops.report.ReportType (string form). */
export type ReportType = 'HEALTH' | 'DISK' | 'SERVICE' | 'SECURITY' | 'AUDIT';

/** Human-readable Chinese labels for the report types — display only. */
export const REPORT_TYPE_LABELS: Readonly<Record<ReportType, string>> = {
  HEALTH: '系统健康',
  DISK: '磁盘诊断',
  SERVICE: '服务诊断',
  SECURITY: '安全事件',
  AUDIT: '通用审计',
};

/** Mirrors com.kylinops.report.ReportSummary (list row). */
export interface ReportSummary {
  reportId: string;
  title: string;
  reportType: ReportType;
  riskLevel?: RiskLevel;
  sessionId?: string;
  /** Source audit id — used to deep-link back to /audit?auditId=... */
  auditId?: string;
  /** ISO-8601 timestamp from the backend. */
  createdAt?: string;
}

/** Mirrors com.kylinops.report.ReportDetail (full report incl. body). */
export interface ReportDetail {
  reportId: string;
  title: string;
  reportType: ReportType;
  riskLevel?: RiskLevel;
  sessionId?: string;
  /** Source audit id — used to deep-link back to /audit?auditId=... */
  auditId?: string;
  /**
   * Markdown body. Rendered with markdown-it (html:false) — see the
   * safety contract at the top of this file.
   */
  bodyMarkdown?: string;
  createdAt?: string;
}

/** Mirrors com.kylinops.report.ReportGenerateRequest. */
export interface ReportGenerateRequest {
  /** Source audit id. At least one of auditId / sessionId is required. */
  auditId?: string;
  /** Session id. */
  sessionId?: string;
  /** Report type — optional; backend may infer from the source. */
  reportType?: ReportType;
}

/** Mirrors org.springframework.data.domain.Page wire shape. */
export interface ReportPage {
  content: ReportSummary[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}