<script setup lang="ts">
// AppEmptyState — unified empty-state surface for the KylinOps Guard UI.
//
// Replaces the ad-hoc `<p class="...-empty">暂无xxx</p>` pattern used in
// Dashboard / SecurityCenter / ToolCenter. Variants tune the icon glyph
// and the default copy; callers can override everything via props/slots.
//
// Variants:
//   - default     — generic "no data"
//   - no-audit    — "no audit records"
//   - no-tool     — "no registered tools"
//   - no-event    — "no security events"
//   - no-report   — "no reports"
//   - unavailable — "service unavailable / failed"
import { computed } from 'vue';

export type AppEmptyVariant =
  | 'default'
  | 'no-audit'
  | 'no-tool'
  | 'no-event'
  | 'no-report'
  | 'unavailable';

interface Props {
  variant?: AppEmptyVariant;
  title?: string;
  description?: string;
}

const DEFAULTS: Readonly<Record<AppEmptyVariant, { title: string; description: string }>> = {
  default: {
    title: '暂无数据',
    description: '当前条件下没有可显示的内容。',
  },
  'no-audit': {
    title: '暂无审计记录',
    description: '该请求尚未产生审计落库记录。',
  },
  'no-tool': {
    title: '暂无注册工具',
    description: '后端尚未注册任何 OpsTool，请联系管理员。',
  },
  'no-event': {
    title: '暂无拦截事件',
    description: '当前时间段内未发现安全阻断事件。',
  },
  'no-report': {
    title: '暂无报告',
    description: '尚未生成任何运维报告。',
  },
  unavailable: {
    title: '数据不可用',
    description: '后端服务当前不可用，刷新或稍后重试。',
  },
};

const ICON_FOR_VARIANT: Readonly<Record<AppEmptyVariant, string>> = {
  default: '∅',
  'no-audit': '—',
  'no-tool': '⌘',
  'no-event': '✓',
  'no-report': '◯',
  unavailable: '⚠',
};

const props = withDefaults(defineProps<Props>(), {
  variant: 'default',
  title: undefined,
  description: undefined,
});

const resolvedTitle = computed(
  () => props.title ?? DEFAULTS[props.variant].title,
);
const resolvedDescription = computed(
  () => props.description ?? DEFAULTS[props.variant].description,
);
</script>

<template>
  <div
    class="kg-empty-state"
    :class="`kg-empty-state--${variant}`"
    :data-testid="`app-empty-state-${variant}`"
    role="status"
  >
    <div class="kg-empty-state__icon" aria-hidden="true">
      <slot name="icon">{{ ICON_FOR_VARIANT[variant] }}</slot>
    </div>
    <h4 class="kg-empty-state__title">{{ resolvedTitle }}</h4>
    <p class="kg-empty-state__description">{{ resolvedDescription }}</p>
    <div v-if="$slots.action" class="kg-empty-state__action">
      <slot name="action" />
    </div>
  </div>
</template>

<style scoped>
.kg-empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--kg-space-2);
  padding: var(--kg-space-7) var(--kg-space-5);
  text-align: center;
  background-color: var(--kg-color-surface);
  border: 1px dashed var(--kg-color-border);
  border-radius: var(--kg-radius-md);
  color: var(--kg-color-text-secondary);
}

.kg-empty-state__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border-radius: var(--kg-radius-pill);
  background-color: var(--kg-color-surface-soft);
  color: var(--kg-color-text-mute);
  font-size: 20px;
  font-weight: 600;
  margin-bottom: var(--kg-space-1);
}

.kg-empty-state__title {
  margin: 0;
  font-size: var(--kg-text-md);
  font-weight: 600;
  color: var(--kg-color-text-primary);
}

.kg-empty-state__description {
  margin: 0;
  max-width: 36em;
  font-size: var(--kg-text-sm);
  color: var(--kg-color-text-mute);
  line-height: var(--kg-line-base);
}

.kg-empty-state__action {
  margin-top: var(--kg-space-3);
  display: flex;
  gap: var(--kg-space-2);
}
</style>
