<script setup lang="ts">
/**
 * LandingEyebrow - 节标题上方的小标 (mono uppercase wide tracking)。
 *
 * 集中实现,便于:
 *   1. 全局统一上限 (skill 强制 ≤ 3 个/section-cap,这里集中可见)
 *   2. 后续如调整字距 / 颜色,改一处即可
 *
 * 设计规范:
 *   - font-family: var(--kg-font-mono) (Cascadia / JetBrains Mono 备选)
 *   - font-size: 11px
 *   - letter-spacing: 0.22em
 *   - text-transform: uppercase
 *   - color: var(--kg-color-accent-hover) (cyan)
 *
 * 使用方式:
 *   <LandingEyebrow text="Kylin Ops Guard · v0.1" />
 *   或默认 slot 自带内容:
 *   <LandingEyebrow>KYLIN OPS GUARD · V0.1</LandingEyebrow>
 */
defineProps<{ text?: string }>()
</script>

<template>
  <span class="landing-eyebrow">
    <slot>{{ text }}</slot>
  </span>
</template>

<style scoped>
.landing-eyebrow {
  display: inline-block;
  font-family: var(--kg-font-mono);
  font-size: 11px;
  letter-spacing: 0.22em;
  text-transform: uppercase;
  color: var(--kg-color-accent-hover);
  line-height: 1;
}
</style>
