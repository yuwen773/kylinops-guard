<script setup lang="ts">
/**
 * TrustStrip - 4 个核心数字指标。
 *
 * 设计规范 (P1-03):
 *   - 横排 4 列,移动端 2x2
 *   - 大数字 mono + 小字 unit/label
 *   - 无 eyebrow,无标题,作为 Hero 与 Bento 之间的过渡段
 *   - 不放客户 logo (项目暂无公开客户信息)
 *   - 数字精度真实化:不写假精度 (92% 之类);要么 100% (全量审计事实),
 *     要么整数 (10+ 工具,L0-L4 等级,4 个演示场景)
 */
import LandingContainer from '../components/LandingContainer.vue'

interface Metric {
  value: string
  unit?: string
  label: string
}

const metrics: Metric[] = [
  { value: '10', unit: '+', label: 'MCP 工具注册' },
  { value: 'L0-L4', label: '风险等级预检' },
  { value: '100', unit: '%', label: '操作全量审计' },
  { value: '4', label: '6:30 演示场景' },
]
</script>

<template>
  <section class="trust-strip" aria-label="核心能力指标">
    <LandingContainer>
      <ul class="trust-strip__list">
        <li v-for="(m, i) in metrics" :key="i" class="trust-strip__item">
          <span class="trust-strip__value">
            <span class="trust-strip__num">{{ m.value }}</span>
            <span v-if="m.unit" class="trust-strip__unit">{{ m.unit }}</span>
          </span>
          <span class="trust-strip__label">{{ m.label }}</span>
        </li>
      </ul>
    </LandingContainer>
  </section>
</template>

<style scoped>
.trust-strip {
  padding: 48px 0;
  border-top: 1px solid var(--kg-color-border);
  border-bottom: 1px solid var(--kg-color-border);
  background-color: var(--kg-color-surface);
}

.trust-strip__list {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--kg-space-7);
  margin: 0;
  padding: 0;
  list-style: none;
}

@media (max-width: 768px) {
  .trust-strip__list {
    grid-template-columns: repeat(2, 1fr);
    gap: var(--kg-space-6);
  }
}

.trust-strip__item {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--kg-space-2);
}

.trust-strip__value {
  display: inline-flex;
  align-items: baseline;
  gap: 2px;
  font-family: var(--kg-font-mono);
  font-weight: 600;
  color: var(--kg-color-text-primary);
}

.trust-strip__num {
  font-size: clamp(28px, 4vw, 40px);
  line-height: 1;
  letter-spacing: -0.01em;
}

.trust-strip__unit {
  font-size: var(--kg-text-lg);
  color: var(--kg-color-accent-hover);
}

.trust-strip__label {
  font-size: var(--kg-text-sm);
  color: var(--kg-color-text-secondary);
  line-height: 1.4;
}
</style>
