// Security Center DTOs — mirrors the backend
//   com.kylinops.security.RiskLevelView
//   com.kylinops.security.SecurityRuleView
//   com.kylinops.security.SecurityEventView
//   org.springframework.data.domain.Page<T>  (wire shape: { content, totalElements,
//   totalPages, number, size })
//
// The frontend is READ-ONLY for security data. It MUST NOT lower risk levels,
// auto-confirm L2, mutate the rule list, or re-evaluate decisions. The backend
// has already produced the final verdict; we display it verbatim.

import type { RiskDecision, RiskLevel } from './safety';

/** Mirrors com.kylinops.security.RiskLevelView. */
export interface RiskLevelView {
  level: RiskLevel;
  decision: RiskDecision;
  /** Chinese description of the level. */
  description?: string;
  /** Typical command / scenario examples (Chinese). */
  examples?: string[];
}

/** Mirrors com.kylinops.security.SecurityRuleView. */
export interface SecurityRuleView {
  ruleId: string;
  /** Display name (usually identical to ruleId). */
  name?: string;
  /** Chinese description. */
  description?: string;
  /** Original regex string (read-only technical evidence). */
  regex?: string;
  /** Target type list (e.g. command, tool). */
  targetTypes?: string[];
  riskLevel?: RiskLevel;
  riskDecision?: RiskDecision;
  /** Match reason (Chinese). */
  reason?: string;
  /** Safe suggestion (Chinese). */
  safeSuggestion?: string;
  enabled?: boolean;
  priority?: number;
}

/** Mirrors com.kylinops.security.SecurityEventView. */
export interface SecurityEventView {
  auditId: string;
  riskLevel: RiskLevel;
  /** Always BLOCK on this endpoint. */
  decision: RiskDecision;
  /** Matched rule id list. */
  matchedRules?: string[];
  /** Block reason (Chinese). */
  reason?: string;
  /** ISO-8601 timestamp. */
  createdAt?: string;
  /** Optional tool name. */
  toolName?: string;
}

/** Mirrors org.springframework.data.domain.Page wire shape. */
export interface SecurityEventPage {
  content: SecurityEventView[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}
