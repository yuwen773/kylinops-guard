<script setup lang="ts">
// AppStateBanner — slim, page-level status banner.
//
// Used for "stale data" / "service degraded" / "partial result" hints that
// sit at the top of a page but should NOT push the existing content off
// screen. The visual differs from AppErrorState in that it is inline and
// uses the design system accent for the chosen tone.
import { computed } from 'vue';

export type AppStateBannerTone = 'info' | 'success' | 'warning' | 'danger';

interface Props {
  tone?: AppStateBannerTone;
  title: string;
  description?: string;
  closable?: boolean;
}

const props = withDefaults(defineProps<Props>(), {
  tone: 'info',
  description: undefined,
  closable: false,
});

const emit = defineEmits<{ (e: 'close'): void }>();

const toneClass = computed(() => `kg-state-banner--${props.tone}`);

const onClose = () => emit('close');
</script>

<template>
  <div
    class="kg-state-banner"
    :class="toneClass"
    :data-testid="`app-state-banner-${tone}`"
    role="status"
  >
    <div class="kg-state-banner__body">
      <span class="kg-state-banner__title">{{ title }}</span>
      <span
        v-if="description"
        class="kg-state-banner__description"
      >{{ description }}</span>
    </div>
    <button
      v-if="closable"
      type="button"
      class="kg-state-banner__close"
      aria-label="关闭提示"
      data-testid="app-state-banner-close"
      @click="onClose"
    >×</button>
  </div>
</template>

<style scoped>
.kg-state-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--kg-space-3);
  padding: var(--kg-space-3) var(--kg-space-4);
  border: 1px solid transparent;
  border-left-width: 3px;
  border-radius: var(--kg-radius-sm);
  font-size: var(--kg-text-sm);
  color: var(--kg-color-text-primary);
  background-color: var(--kg-color-surface-soft);
}

.kg-state-banner--info {
  border-color: var(--kg-color-info);
  background-color: var(--kg-color-info-soft);
  color: var(--kg-color-info);
}

.kg-state-banner--success {
  border-color: var(--kg-color-success);
  background-color: var(--kg-color-success-soft);
  color: var(--kg-color-success);
}

.kg-state-banner--warning {
  border-color: var(--kg-color-warning);
  background-color: var(--kg-color-warning-soft);
  color: var(--kg-color-warning);
}

.kg-state-banner--danger {
  border-color: var(--kg-color-danger);
  background-color: var(--kg-color-danger-soft);
  color: var(--kg-color-danger);
}

.kg-state-banner__body {
  display: flex;
  flex-direction: column;
  gap: 2px;
  flex: 1 1 auto;
}

.kg-state-banner__title {
  font-weight: 600;
  color: inherit;
}

.kg-state-banner__description {
  color: var(--kg-color-text-secondary);
  font-size: var(--kg-text-xs);
}

.kg-state-banner__close {
  background: transparent;
  border: none;
  color: inherit;
  font-size: 18px;
  line-height: 1;
  cursor: pointer;
  padding: 0 var(--kg-space-2);
  border-radius: var(--kg-radius-sm);
  transition: background-color var(--kg-transition-fast);
}

.kg-state-banner__close:hover {
  background-color: rgba(255, 255, 255, 0.06);
}
</style>
