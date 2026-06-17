<script setup lang="ts">
// AppSectionHeader — shared page / section header.
//
// Used at the top of a page (h2-style title + subtitle) and at the start
// of an in-page section (h3-style). The right-hand slot is reserved for
// action buttons (refresh, filter, etc.).
import { computed } from 'vue';

export type AppSectionHeaderLevel = 'page' | 'section';

interface Props {
  title: string;
  subtitle?: string;
  level?: AppSectionHeaderLevel;
}

const props = withDefaults(defineProps<Props>(), {
  subtitle: undefined,
  level: 'page',
});

const titleClass = computed(() =>
  props.level === 'page' ? 'kg-section-header__title--page' : 'kg-section-header__title--section',
);

// Semantic heading level: page => h2, section => h3 (h1 is the document title).
const titleTag = computed(() => (props.level === 'page' ? 'h2' : 'h3'));
</script>

<template>
  <header
    class="kg-section-header"
    :class="`kg-section-header--${level}`"
    :data-testid="`app-section-header-${level}`"
  >
    <div class="kg-section-header__row">
      <div class="kg-section-header__heading">
        <component
          :is="titleTag"
          :class="titleClass"
          class="kg-section-header__title"
        >{{ title }}</component>
        <p
          v-if="subtitle"
          class="kg-section-header__subtitle"
        >{{ subtitle }}</p>
      </div>
      <div
        v-if="$slots.actions"
        class="kg-section-header__actions"
      >
        <slot name="actions" />
      </div>
    </div>
  </header>
</template>

<style scoped>
.kg-section-header {
  display: flex;
  flex-direction: column;
  gap: var(--kg-space-1);
  margin-bottom: var(--kg-space-3);
}

.kg-section-header--section {
  margin-bottom: var(--kg-space-3);
}

.kg-section-header__row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--kg-space-4);
  flex-wrap: wrap;
}

.kg-section-header__heading {
  display: flex;
  flex-direction: column;
  gap: var(--kg-space-1);
  min-width: 0;
}

.kg-section-header__title {
  margin: 0;
  color: var(--kg-color-text-primary);
  font-weight: 600;
  line-height: var(--kg-line-tight);
}

.kg-section-header__title--page {
  font-size: var(--kg-text-xl);
  letter-spacing: -0.005em;
}

.kg-section-header__title--section {
  font-size: var(--kg-text-md);
}

.kg-section-header__subtitle {
  margin: 0;
  font-size: var(--kg-text-sm);
  color: var(--kg-color-text-mute);
  line-height: var(--kg-line-base);
  max-width: 64ch;
}

.kg-section-header__actions {
  display: flex;
  align-items: center;
  gap: var(--kg-space-2);
  flex-shrink: 0;
}
</style>
