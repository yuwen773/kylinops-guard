// RCA (Root Cause Analysis) types — mirror com.kylinops.rca.RootCauseChain
//
// SAFETY CONTRACT:
//   * The frontend NEVER recomputes or fabricates RCA fields. Every rendered
//     value comes verbatim from the backend. When a field is missing, the
//     component renders an empty state, not a fabricated one.

export interface Evidence {
  evidenceId: string;
  source: string;
  sourceToolCallId?: string;
  observation: string;
  numericValue?: number;
  unit?: string;
}

export interface Hypothesis {
  cause: string;
  probability: number;
  confirmed: boolean;
  reasoning: string;
}

export interface ExcludedCause {
  cause: string;
  reason: string;
  evidenceIds: string[];
}

export interface RootCauseChain {
  symptom: string;
  evidence: Evidence[];
  hypotheses: Hypothesis[];
  excludedCauses: ExcludedCause[];
  conclusion: string;
  confidence: number;
  suggestions: string[];
  riskTips: string[];
}