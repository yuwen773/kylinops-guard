<script setup lang="ts">
// AppLoadingState — shared loading surface for in-flight fetches.
//
// Two layouts:
//   - block  — full-width, padded (default; replaces `.section-loading`)
//   - inline — compact, single-line (toolbar hint / inline status)
import { computed } from 'vue';

export type AppLoadingLayout = 'block' | 'inline';

interface Props {
  title?: string;
  description?: string;
  layout?: AppLoadingLayout;
}

const props = withDefaults(defineProps<Props>(), {
  title: '加载中…',
  description: undefined,
  layout: 'block',
});

const ariaLabel = computed(() => props.title || '加载中');
</script>

<template>
  <div
    class="kg-loading-state"
    :class="[`kg-loading-state--${layout}`]"
    role="status"
    :aria-label="ariaLabel"
    :data-testid="`app-loading-state-${layout}`"
  >
    <span class="kg-loading-state__spinner" aria-hidden="true" />
    <div class="kg-loading-state__text">
      <span class="kg-loading-state__title">{{ title }}</span>
      <span
        v-if="description"
        class="kg-loading-state__description"
      >{{ description }}</span>
    </div>
  </div>
</template>

<style scoped>
.kg-loading-state {
  display: flex;
  align-items: center;
  gap: var(--kg-space-3);
  color: var(--kg-color-text-mute);
  font-size: var(--kg-text-sm);
}

.kg-loading-state--block {
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: var(--kg-space-7) var(--kg-space-5);
  background-color: var(--kg-color-surface);
  border: 1px dashed var(--kg-color-border);
  border-radius: var(--kg-radius-md);
  gap: var(--kg-space-3);
}

.kg-loading-state__spinner {
  display: inline-block;
  width: 18px;
  height: 18px;
  border: 2px solid var(--kg-color-border-strong);
  border-top-color: var(--kg-color-primary);
  border-radius: 50%;
  animation: kg-spin 0.8s linear infinite;
}

.kg-loading-state--block .kg-loading-state__spinner {
  width: 28px;
  height: 28px;
  border-width: 3px;
}

.kg-loading-state__text {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--kg-space-1);
}

.kg-loading-state--inline .kg-loading-state__text {
  align-items: flex-start;
}

.kg-loading-state__title {
  color: var(--kg-color-text-secondary);
  font-weight: 500;
}

.kg-loading-state__description {
  font-size: var(--kg-text-xs);
  color: var(--kg-color-text-mute);
}

@keyframes kg-spin {
  to { transform: rotate(360deg); }
}

@media (prefers-reduced-motion: reduce) {
  .kg-loading-state__spinner { animation: none; }
}
</style>
