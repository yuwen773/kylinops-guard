<script setup lang="ts">
// StatusMetricCard — compact metric tile for the Dashboard / Tool Center.
// Used to show "CPU 12%", "Disk 86%" etc. with a status-driven colour tone.
//
// Degradation policy:
//   * status === 'unavailable'   => show "—" with a muted note "数据不可用".
//   * status === 'degraded'      => show "部分降级" badge (backend returned a
//                                   structured failure but partially valid data).
//   * value is null/undefined    => show "—" (same as unavailable visually).
//
// The component never fabricates a value when the backend omitted one.
//
// Enhancement (v2): adds an inline progress bar when the value is numeric
// between 0-100, giving the metric card a visual dimension.
import { computed } from 'vue';

export type StatusMetricStatus =
  | 'ok'
  | 'warning'
  | 'critical'
  | 'unavailable'
  | 'degraded';

interface Props {
  title: string;
  /** Numeric or string value. Null/undefined => "—". */
  value?: number | string | null;
  /** Optional unit suffix, e.g. "%", "GB", "ms". */
  unit?: string;
  /** Optional threshold text, e.g. "阈值 80%". */
  threshold?: string;
  /** Status drives the colour tone and the empty-state text. */
  status: StatusMetricStatus;
}

const props = defineProps<Props>();

const isEmpty = computed(
  () => props.value === null || props.value === undefined || props.value === '',
);

/** True when the value is a plain number (not a text label). */
const isNumericValue = computed(() => {
  if (isEmpty.value) return false;
  const num = Number(props.value);
  return Number.isFinite(num);
});

const displayValue = computed(() => (isEmpty.value ? '—' : String(props.value)));

const statusText = computed<string>(() => {
  switch (props.status) {
    case 'ok':
      return '正常';
    case 'warning':
      return '告警';
    case 'critical':
      return '严重';
    case 'unavailable':
      return '数据不可用';
    case 'degraded':
      return '部分降级';
  }
});

const tone = computed<'success' | 'warning' | 'danger' | 'info'>(() => {
  switch (props.status) {
    case 'ok':
      return 'success';
    case 'warning':
      return 'warning';
    case 'critical':
      return 'danger';
    case 'unavailable':
      return 'info';
    case 'degraded':
      return 'warning';
  }
});

/** Whether to show an inline progress bar. True when value is numeric 0-100. */
const showProgressBar = computed(() => {
  if (isEmpty.value) return false;
  const num = Number(props.value);
  return Number.isFinite(num) && num >= 0 && num <= 100 && props.unit === '%';
});

const progressValue = computed(() => {
  if (!showProgressBar.value) return 0;
  return Number(props.value);
});

const progressClass = computed(() => {
  switch (props.status) {
    case 'critical': return 'kg-progress-fill--critical';
    case 'warning':
    case 'degraded': return 'kg-progress-fill--warning';
    default: return 'kg-progress-fill--ok';
  }
});
</script>

<template>
  <el-card
    class="status-metric-card"
    shadow="never"
    :data-testid="`status-metric-${title}`"
  >
    <div class="status-metric-header">
      <span class="status-metric-title">{{ title }}</span>
      <el-tag
        size="small"
        :type="tone"
        :data-testid="`status-metric-tone-${status}`"
      >
        {{ statusText }}
      </el-tag>
    </div>
    <div
      class="status-metric-value"
      :data-testid="`status-metric-value-${title}`"
    >
      <span class="status-metric-number" :class="{ 'status-metric-number--text': !isNumericValue }">{{ displayValue }}</span>
      <span v-if="!isEmpty && unit" class="status-metric-unit">{{ unit }}</span>
    </div>
    <div
      v-if="showProgressBar"
      class="kg-progress-bar status-metric-progress"
    >
      <div
        class="kg-progress-fill"
        :class="progressClass"
        :style="{ width: `${progressValue}%` }"
      />
    </div>
    <div
      v-if="threshold"
      class="status-metric-threshold"
    >
      {{ threshold }}
    </div>
  </el-card>
</template>

<style scoped>
.status-metric-card {
  min-width: 180px;
  position: relative;
  overflow: hidden;
  transition: box-shadow var(--kg-transition-base), transform var(--kg-transition-fast);
  cursor: pointer;
}

.status-metric-card:hover {
  box-shadow: var(--kg-glow-primary);
  transform: translateY(-1px);
}

.status-metric-card:active {
  transform: translateY(0);
}

.status-metric-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--kg-space-2);
  margin-bottom: var(--kg-space-2);
}

.status-metric-title {
  font-size: var(--kg-text-sm);
  color: var(--kg-color-text-secondary);
  font-weight: 500;
}

.status-metric-value {
  display: flex;
  align-items: baseline;
  gap: var(--kg-space-1);
}

.status-metric-number {
  font-size: var(--kg-text-2xl);
  font-weight: 600;
  color: var(--kg-color-text-primary);
  line-height: var(--kg-line-tight);
  font-family: var(--kg-font-mono);
}

.status-metric-number--text {
  font-size: var(--kg-text-base);
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 100%;
}

.status-metric-unit {
  font-size: var(--kg-text-sm);
  color: var(--kg-color-text-mute);
}

.status-metric-progress {
  margin-top: var(--kg-space-3);
}

.status-metric-threshold {
  margin-top: var(--kg-space-1);
  font-size: var(--kg-text-xs);
  color: var(--kg-color-text-mute);
}
</style>
