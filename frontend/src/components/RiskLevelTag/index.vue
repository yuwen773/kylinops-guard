<script setup lang="ts">
// RiskLevelTag — displays the backend's risk-level verdict verbatim.
//
// SAFETY CONTRACT: This component MUST NOT derive a "softer" level from the
// props. Whatever the backend says (L0..L4) is rendered as-is, with the
// optional decision appended (e.g. "L4 BLOCK"). The UI exists to *display*
// the safety guardrail, not to soften it.
//
// We intentionally avoid any prop-mutation logic: if a caller passes a
// non-canonical level the component falls back to the raw value rather
// than guessing a safer mapping.
import { computed } from 'vue';
import {
  RISK_DECISION_LABELS,
  RISK_LEVEL_LABELS,
  type RiskDecision,
  type RiskLevel,
} from '@/types/safety';

interface Props {
  /** Backend-returned risk level code. */
  level: RiskLevel;
  /** Optional backend-returned decision; appended for at-a-glance reading. */
  decision?: RiskDecision;
}

const props = defineProps<Props>();

// The textual "tone" of the tag follows the backend's policy:
//   L0/L1 -> success (green)
//   L2    -> warning (orange)  — needs user confirmation, but allowed
//   L3/L4 -> danger  (red)     — blocked outright
const tone = computed<'success' | 'warning' | 'danger'>(() => {
  if (props.level === 'L0' || props.level === 'L1') return 'success';
  if (props.level === 'L2') return 'warning';
  return 'danger';
});

const levelLabel = computed(() => `${props.level} ${RISK_LEVEL_LABELS[props.level]}`);

const decisionSuffix = computed(() =>
  props.decision ? ` · ${props.decision} ${RISK_DECISION_LABELS[props.decision]}` : '',
);
</script>

<template>
  <el-tag
    :type="tone"
    :data-testid="`risk-level-${level}`"
    effect="dark"
    round
  >
    {{ levelLabel }}{{ decisionSuffix }}
  </el-tag>
</template>
