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
      <span class="status-metric-number">{{ displayValue }}</span>
      <span v-if="!isEmpty && unit" class="status-metric-unit">{{ unit }}</span>
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
}

.status-metric-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
  margin-bottom: 0.5rem;
}

.status-metric-title {
  font-size: 0.85rem;
  color: #606266;
  font-weight: 500;
}

.status-metric-value {
  display: flex;
  align-items: baseline;
  gap: 0.25rem;
}

.status-metric-number {
  font-size: 1.75rem;
  font-weight: 600;
  color: #1f2d3d;
  line-height: 1.2;
}

.status-metric-unit {
  font-size: 0.85rem;
  color: #909399;
}

.status-metric-threshold {
  margin-top: 0.25rem;
  font-size: 0.75rem;
  color: #909399;
}
</style>
