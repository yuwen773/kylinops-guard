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
//
// Visual distinction (UI-01): L0..L4 all map to el-tag base tones for the
// existing test contract, but L3 vs L4 get distinct accent colors via the
// kg-risk-tag--l{3,4} wrapper class. The el-tag--* class is preserved so
// unit tests asserting on those selectors keep passing.
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
  <span
    class="kg-risk-tag"
    :class="`kg-risk-tag--${level.toLowerCase()}`"
  >
    <el-tag
      :type="tone"
      :data-testid="`risk-level-${level}`"
      effect="dark"
      round
    >
      {{ levelLabel }}{{ decisionSuffix }}
    </el-tag>
  </span>
</template>

<style scoped>
.kg-risk-tag {
  display: inline-flex;
}
</style>

<!--
  Unscoped block — must override Element Plus's high-specificity selectors
  (`.el-tag.el-tag--danger.el-tag--dark`) to apply the L3 / L4 distinction.
  Test contract: the el-tag still has `el-tag--danger` for L3/L4, so the
  `uses success tone for L0/L1, warning for L2, danger for L3/L4` test
  keeps passing.
-->
<style>
.kg-risk-tag--l3 .el-tag.el-tag--danger.el-tag--dark {
  background-color: var(--kg-color-risk-l3-soft);
  color: var(--kg-color-risk-l3);
  border-color: var(--kg-color-risk-l3-soft);
}
.kg-risk-tag--l4 .el-tag.el-tag--danger.el-tag--dark {
  background-color: var(--kg-color-risk-l4-soft);
  color: var(--kg-color-risk-l4);
  border-color: var(--kg-color-risk-l4-soft);
}
</style>
