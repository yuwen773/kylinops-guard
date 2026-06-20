<script setup lang="ts">
/**
 * LandingContainer - 统一的 landing page 宽度容器。
 *
 * 设计规范 (P1-03 design spec):
 *   - max-width 1200px,避免大屏阅读线过长
 *   - 桌面端 px-24 (var(--kg-space-6))
 *   - 移动端 (< 768px) px-20 (--kg-space-5)
 *   - 横向居中
 *
 * 所有 landing section 内部必须使用本组件包裹内容,而不是各自写一遍
 * max-width + padding,以保证横向节奏一致。
 */
</script>

<template>
  <div class="landing-container">
    <slot />
  </div>
</template>

<style scoped>
.landing-container {
  width: 100%;
  max-width: 1200px;
  margin-inline: auto;
  padding-inline: var(--kg-space-6);
}

@media (max-width: 768px) {
  .landing-container {
    padding-inline: var(--kg-space-5);
  }
}
</style>
