// Security Center API — thin wrappers over the three read-only endpoints
// introduced by Task 7.
//
// This module owns the wire contract with the backend security catalog
// (com.kylinops.security.SecurityCatalogController). It NEVER posts a
// mutation; the Security Center is a read-only page by design — no rule
// edits, no toggles, no confirm/cancel buttons.
//
// Errors propagate as ApiError (transport OR business). The page surfaces
// them verbatim and degrades the failing section locally only.

import { get } from './client';
import type {
  RiskLevelView,
  SecurityEventPage,
  SecurityRuleView,
} from '@/types/security';

/**
 * GET /api/security/risk-levels — L0..L4 catalog with Chinese descriptions
 * and typical examples. Used to render the five level cards at the top of
 * the Security Center.
 */
export function getRiskLevels(): Promise<RiskLevelView[]> {
  return get<RiskLevelView[]>('/api/security/risk-levels');
}

/**
 * GET /api/security/rules — immutable snapshot of the loaded risk rules.
 * The page groups them by level and renders the regex as read-only
 * technical evidence. There is intentionally no PUT/POST helper here.
 */
export function getSecurityRules(): Promise<SecurityRuleView[]> {
  return get<SecurityRuleView[]>('/api/security/rules');
}

export interface SecurityEventQuery {
  page?: number;
  size?: number;
}

/**
 * GET /api/security/events?page=&size= — paged BLOCK audit events
 * (createdAt DESC). The wire shape mirrors Spring's Page<T>.
 */
export function getSecurityEvents(
  query: SecurityEventQuery = {},
): Promise<SecurityEventPage> {
  const params: Record<string, number> = {};
  if (query.page !== undefined) params.page = query.page;
  if (query.size !== undefined) params.size = query.size;
  return get<SecurityEventPage>('/api/security/events', { params });
}
