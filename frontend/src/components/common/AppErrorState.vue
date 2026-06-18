<script setup lang="ts">
// AppErrorState — shared error surface for failed fetches.
//
// Variants:
//   - transient — soft, retry expected (default for fetch errors)
//   - fatal     — solid border, no retry path (service unavailable)
import { computed } from 'vue';

export type AppErrorVariant = 'transient' | 'fatal';

interface Props {
  title?: string;
  description?: string;
  variant?: AppErrorVariant;
}

const DEFAULTS: Readonly<Record<AppErrorVariant, { title: string; description: string }>> = {
  transient: {
    title: '加载失败',
    description: '后端接口调用失败，请稍后重试。',
  },
  fatal: {
    title: '服务不可用',
    description: '后端服务当前不可用，请联系管理员。',
  },
};

const props = withDefaults(defineProps<Props>(), {
  title: undefined,
  description: undefined,
  variant: 'transient',
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
    class="kg-error-state"
    :class="[`kg-error-state--${variant}`]"
    role="alert"
    :data-testid="`app-error-state-${variant}`"
  >
    <div class="kg-error-state__icon" aria-hidden="true">!</div>
    <div class="kg-error-state__body">
      <h4 class="kg-error-state__title">{{ resolvedTitle }}</h4>
      <p class="kg-error-state__description">{{ resolvedDescription }}</p>
    </div>
    <div v-if="$slots.action" class="kg-error-state__action">
      <slot name="action" />
    </div>
  </div>
</template>

<style scoped>
.kg-error-state {
  display: flex;
  align-items: flex-start;
  gap: var(--kg-space-3);
  padding: var(--kg-space-4) var(--kg-space-5);
  background-color: var(--kg-color-danger-soft);
  border: 1px solid var(--kg-color-danger);
  border-radius: var(--kg-radius-md);
  color: var(--kg-color-text-primary);
}

.kg-error-state--fatal {
  background-color: var(--kg-color-surface-soft);
  border-style: solid;
  border-color: var(--kg-color-danger);
}

.kg-error-state__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: var(--kg-radius-pill);
  background-color: var(--kg-color-danger);
  color: #fff;
  font-weight: 700;
  font-size: 14px;
  flex: 0 0 auto;
}

.kg-error-state__body {
  flex: 1 1 auto;
  display: flex;
  flex-direction: column;
  gap: var(--kg-space-1);
}

.kg-error-state__title {
  margin: 0;
  font-size: var(--kg-text-md);
  font-weight: 600;
  color: var(--kg-color-danger);
}

.kg-error-state__description {
  margin: 0;
  font-size: var(--kg-text-sm);
  color: var(--kg-color-text-secondary);
  line-height: var(--kg-line-base);
}

.kg-error-state__action {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: var(--kg-space-2);
}
</style>
