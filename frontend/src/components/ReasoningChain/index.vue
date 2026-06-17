<script setup lang="ts">
// ReasoningChain — 可视化展示 RootCauseChain。
// 字段渲染严格用 v-text，不使用 v-html（XSS 安全契约）。
import type { RootCauseChain } from '@/types/rca';

withDefaults(defineProps<{
  chain: RootCauseChain;
  title: string;
  dataTestid?: string;
}>(), {
  dataTestid: 'reasoning-chain',
});

const confidencePercent = (c: number) => Math.round(c * 100);
</script>

<template>
  <el-card class="reasoning-chain" shadow="never" :data-testid="dataTestid">
    <template #header>
      <div class="rc-header">
        <span class="rc-title">{{ title }}</span>
        <el-tag size="small" :type="chain.confidence >= 0.7 ? 'success' : 'warning'">
          置信度 {{ confidencePercent(chain.confidence) }}%
        </el-tag>
      </div>
    </template>

    <section class="rc-section">
      <div class="rc-section-label">现象</div>
      <p class="rc-symptom" data-testid="rc-symptom">{{ chain.symptom }}</p>
    </section>

    <section v-if="chain.evidence.length" class="rc-section">
      <div class="rc-section-label">证据（{{ chain.evidence.length }}）</div>
      <ul class="rc-evidence">
        <li
          v-for="ev in chain.evidence"
          :key="ev.evidenceId"
          :data-testid="`rc-evidence-${ev.evidenceId}`"
        >
          <span class="rc-source">{{ ev.source }}：</span>
          <span class="rc-obs">{{ ev.observation }}</span>
        </li>
      </ul>
    </section>

    <section v-if="chain.hypotheses.length" class="rc-section">
      <div class="rc-section-label">候选根因</div>
      <ul class="rc-hypotheses">
        <li
          v-for="(h, idx) in chain.hypotheses"
          :key="`h-${idx}`"
          :class="h.confirmed ? 'rc-confirmed' : ''"
        >
          <el-tag v-if="h.confirmed" size="small" type="success">已确认</el-tag>
          <el-tag v-else size="small" type="info">候选</el-tag>
          <span>{{ h.cause }}（{{ confidencePercent(h.probability) }}%）</span>
          <small class="rc-reasoning">{{ h.reasoning }}</small>
        </li>
      </ul>
    </section>

    <section v-if="chain.excludedCauses.length" class="rc-section">
      <div class="rc-section-label">已排除</div>
      <ul class="rc-excluded">
        <li v-for="(e, idx) in chain.excludedCauses" :key="`e-${idx}`">
          {{ e.cause }} — {{ e.reason }}
        </li>
      </ul>
    </section>

    <section class="rc-section">
      <div class="rc-section-label">结论</div>
      <p class="rc-conclusion" data-testid="rc-conclusion">{{ chain.conclusion }}</p>
    </section>

    <section v-if="chain.suggestions.length" class="rc-section">
      <div class="rc-section-label">建议</div>
      <ol class="rc-suggestions">
        <li v-for="(s, idx) in chain.suggestions" :key="`s-${idx}`">{{ s }}</li>
      </ol>
    </section>

    <section v-if="chain.riskTips.length" class="rc-section rc-risks">
      <div class="rc-section-label">⚠️ 风险提示</div>
      <ul>
        <li v-for="(t, idx) in chain.riskTips" :key="`r-${idx}`">{{ t }}</li>
      </ul>
    </section>
  </el-card>
</template>

<style scoped>
.reasoning-chain {
  margin-top: 0.5rem;
}
.rc-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.rc-title {
  font-weight: 600;
  color: #1f2d3d;
}
.rc-section {
  margin-top: 0.75rem;
}
.rc-section-label {
  font-size: 0.75rem;
  color: #909399;
  margin-bottom: 0.25rem;
  font-weight: 600;
  text-transform: uppercase;
}
.rc-symptom,
.rc-conclusion {
  margin: 0;
  color: #303133;
  line-height: 1.6;
}
.rc-evidence,
.rc-hypotheses,
.rc-excluded,
.rc-suggestions {
  margin: 0;
  padding-left: 1.25rem;
  color: #303133;
}
.rc-evidence li,
.rc-hypotheses li,
.rc-excluded li,
.rc-suggestions li {
  margin-bottom: 0.25rem;
}
.rc-source {
  font-family: ui-monospace, monospace;
  font-size: 0.85rem;
  color: #5d6d7e;
}
.rc-confirmed {
  font-weight: 600;
}
.rc-reasoning {
  color: #909399;
  margin-left: 0.5rem;
  font-size: 0.85rem;
}
.rc-risks ul {
  color: #e6a23c;
}
</style>