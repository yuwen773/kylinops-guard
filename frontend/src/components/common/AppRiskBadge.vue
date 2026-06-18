<script setup lang="ts">
// AppRiskBadge — shared risk-level badge for the KylinOps Guard UI.
//
// Supports the full 6-tier surface (L0..L4 + Inject). 'Inject' is not part
// of the backend's RiskLevel enum (it's a separate prompt-injection signal),
// so we widen the level type locally and provide a fixed label/description.
//
// Three variants:
//   - soft    : tinted background, colored text (default; reads at a glance)
//   - solid   : saturated background, dark text (high contrast)
//   - outline : transparent background, colored border + text (table cells)
//
// Two sizes:
//   - sm : compact (table / inline list)
//   - md : default (cards / detail)
//
// Tooltip: defaults to a level-specific description so screen-readers and
// hover tooltips both surface the meaning.
import { computed } from 'vue';
import { RISK_LEVEL_LABELS, type RiskLevel } from '@/types/safety';

export type AppRiskLevel = RiskLevel | 'Inject';
export type AppRiskSize = 'sm' | 'md';
export type AppRiskVariant = 'soft' | 'solid' | 'outline';

interface Props {
  level: AppRiskLevel;
  /** Show only the code (e.g. "L4"), hiding the Chinese label. */
  compact?: boolean;
  /** Show both level code and Chinese label. Default true. */
  showLabel?: boolean;
  /** Optional tooltip text. Falls back to a level-specific description. */
  tooltip?: string;
  size?: AppRiskSize;
  variant?: AppRiskVariant;
}

const props = withDefaults(defineProps<Props>(), {
  compact: false,
  showLabel: true,
  tooltip: undefined,
  size: 'md',
  variant: 'soft',
});

// Prompt-injection is a UI-01 extension: not in the backend enum, so we
// keep the description short and verifiable.
const INJECT_LABEL = '提示词注入';
const INJECT_DESC = '检测到提示词注入企图，已阻断工具调用并记录事件';

const levelLabels: Readonly<Record<AppRiskLevel, string>> = {
  L0: RISK_LEVEL_LABELS.L0,
  L1: RISK_LEVEL_LABELS.L1,
  L2: RISK_LEVEL_LABELS.L2,
  L3: RISK_LEVEL_LABELS.L3,
  L4: RISK_LEVEL_LABELS.L4,
  Inject: INJECT_LABEL,
};

const levelDescriptions: Readonly<Record<AppRiskLevel, string>> = {
  L0: `${RISK_LEVEL_LABELS.L0}，直接执行并审计`,
  L1: `${RISK_LEVEL_LABELS.L1}，直接执行并审计`,
  L2: `${RISK_LEVEL_LABELS.L2}，用户确认后执行`,
  L3: `${RISK_LEVEL_LABELS.L3}，默认限制或强确认`,
  L4: `${RISK_LEVEL_LABELS.L4}，直接拦截并审计`,
  Inject: INJECT_DESC,
};

const levelClass = computed(() => `kg-risk-badge--${props.level.toLowerCase()}`);
const label = computed(() => levelLabels[props.level] ?? props.level);
const resolvedTooltip = computed(
  () => props.tooltip ?? levelDescriptions[props.level] ?? '',
);
</script>

<template>
  <span
    class="kg-risk-badge"
    :class="[
      levelClass,
      `kg-risk-badge--${size}`,
      `kg-risk-badge--${variant}`,
    ]"
    :title="resolvedTooltip || undefined"
    :data-testid="`app-risk-badge-${level}`"
  >
    <span class="kg-risk-badge__code">{{ level }}</span>
    <span
      v-if="showLabel && !compact"
      class="kg-risk-badge__label"
    >{{ label }}</span>
  </span>
</template>

<style scoped>
.kg-risk-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border-radius: var(--kg-radius-sm);
  font-weight: 600;
  letter-spacing: 0.02em;
  white-space: nowrap;
  border: 1px solid transparent;
  transition: background-color var(--kg-transition-fast),
    border-color var(--kg-transition-fast);
  cursor: default;
}

.kg-risk-badge--sm {
  padding: 1px 6px;
  font-size: 11px;
  line-height: 1.4;
}

.kg-risk-badge--md {
  padding: 2px 8px;
  font-size: 12px;
  line-height: 1.5;
}

.kg-risk-badge__code {
  font-family: var(--kg-font-mono);
  font-weight: 700;
  letter-spacing: 0.04em;
}

.kg-risk-badge__label {
  font-weight: 500;
}

/* Soft variant — light fill, colored text */
.kg-risk-badge--l0.kg-risk-badge--soft {
  background: var(--kg-color-risk-l0-soft);
  color: var(--kg-color-risk-l0);
  border-color: var(--kg-color-risk-l0-soft);
}
.kg-risk-badge--l1.kg-risk-badge--soft {
  background: var(--kg-color-risk-l1-soft);
  color: var(--kg-color-risk-l1);
  border-color: var(--kg-color-risk-l1-soft);
}
.kg-risk-badge--l2.kg-risk-badge--soft {
  background: var(--kg-color-risk-l2-soft);
  color: var(--kg-color-risk-l2);
  border-color: var(--kg-color-risk-l2-soft);
}
.kg-risk-badge--l3.kg-risk-badge--soft {
  background: var(--kg-color-risk-l3-soft);
  color: var(--kg-color-risk-l3);
  border-color: var(--kg-color-risk-l3-soft);
}
.kg-risk-badge--l4.kg-risk-badge--soft {
  background: var(--kg-color-risk-l4-soft);
  color: var(--kg-color-risk-l4);
  border-color: var(--kg-color-risk-l4-soft);
}
.kg-risk-badge--inject.kg-risk-badge--soft {
  background: var(--kg-color-risk-inject-soft);
  color: var(--kg-color-risk-inject);
  border-color: var(--kg-color-risk-inject-soft);
}

/* Solid variant — saturated fill, dark text. L1 stays green-on-dark. */
.kg-risk-badge--solid {
  color: var(--kg-color-text-on-risk);
}
.kg-risk-badge--l0.kg-risk-badge--solid { background: var(--kg-color-risk-l0); }
.kg-risk-badge--l1.kg-risk-badge--solid { background: var(--kg-color-risk-l1); }
.kg-risk-badge--l2.kg-risk-badge--solid { background: var(--kg-color-risk-l2); }
.kg-risk-badge--l3.kg-risk-badge--solid { background: var(--kg-color-risk-l3); }
.kg-risk-badge--l4.kg-risk-badge--solid { background: var(--kg-color-risk-l4); }
.kg-risk-badge--inject.kg-risk-badge--solid {
  background: var(--kg-color-risk-inject);
  color: #fff;
}

/* Outline variant — transparent fill, colored border + text */
.kg-risk-badge--outline { background: transparent; }
.kg-risk-badge--l0.kg-risk-badge--outline {
  border-color: var(--kg-color-risk-l0); color: var(--kg-color-risk-l0);
}
.kg-risk-badge--l1.kg-risk-badge--outline {
  border-color: var(--kg-color-risk-l1); color: var(--kg-color-risk-l1);
}
.kg-risk-badge--l2.kg-risk-badge--outline {
  border-color: var(--kg-color-risk-l2); color: var(--kg-color-risk-l2);
}
.kg-risk-badge--l3.kg-risk-badge--outline {
  border-color: var(--kg-color-risk-l3); color: var(--kg-color-risk-l3);
}
.kg-risk-badge--l4.kg-risk-badge--outline {
  border-color: var(--kg-color-risk-l4); color: var(--kg-color-risk-l4);
}
.kg-risk-badge--inject.kg-risk-badge--outline {
  border-color: var(--kg-color-risk-inject); color: var(--kg-color-risk-inject);
}
</style>
