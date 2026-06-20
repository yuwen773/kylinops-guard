<script setup lang="ts">
/**
 * ClosedLoopFlow - 10 步骤闭环横向流。
 *
 * 设计规范 (P1-03):
 *   - 桌面端:横向 10 节点 + 节点间 1px 渐变线
 *   - 移动端:切换为 2 列网格或竖向列表
 *   - 每节点:小圆点 + mono step number + 4 字中文 + 1 行说明
 *   - reduced-motion:去掉渐变线动画
 *
 * 内容来源:PRD v0.1 §3.1 闭环描述,精确匹配 ChatConsole 实际数据流
 * (自然语言 → 意图识别 → MCP 规划 → Tool 调用 → OS 感知 → 智能分析 →
 *  RiskCheck → SafeExecutor → AuditLog → 报告)
 */
import LandingContainer from '../components/LandingContainer.vue'

interface LoopStep {
  num: string
  label: string
  detail: string
}

const steps: LoopStep[] = [
  { num: '01', label: '自然语言', detail: '操作员输入指令' },
  { num: '02', label: '意图识别', detail: 'LLM 解析 + 注入检测' },
  { num: '03', label: 'MCP 规划', detail: 'ToolPlanner 选工具' },
  { num: '04', label: 'Tool 调用', detail: '参数白名单 + 超时' },
  { num: '05', label: 'OS 感知', detail: '执行并采集输出' },
  { num: '06', label: '智能分析', detail: '结构化诊断结果' },
  { num: '07', label: 'RiskCheck', detail: '预执行规则匹配' },
  { num: '08', label: 'SafeExecutor', detail: '受限账户 + 白名单' },
  { num: '09', label: 'AuditLog', detail: '共享 auditId 留痕' },
  { num: '10', label: '报告', detail: '结构化导出 + 通知' },
]
</script>

<template>
  <section id="flow" class="flow" aria-label="闭环流程">
    <LandingContainer>
      <div class="flow__header">
        <h2 class="flow__title">从一句话到一份可审计报告</h2>
        <p class="flow__sub">10 步闭环贯穿自然语言到全量审计,中间没有任何"魔法盒"。</p>
      </div>

      <ol class="flow__list" data-testid="closed-loop">
        <li v-for="(s, i) in steps" :key="s.num" class="flow__step">
          <span class="flow__dot" aria-hidden="true" />
          <span v-if="i < steps.length - 1" class="flow__connector" aria-hidden="true" />
          <span class="flow__num">{{ s.num }}</span>
          <span class="flow__label">{{ s.label }}</span>
          <span class="flow__detail">{{ s.detail }}</span>
        </li>
      </ol>
    </LandingContainer>
  </section>
</template>

<style scoped>
.flow {
  padding: 96px 0;
  background-color: var(--kg-color-surface);
  border-top: 1px solid var(--kg-color-border);
  border-bottom: 1px solid var(--kg-color-border);
}

@media (max-width: 768px) {
  .flow {
    padding: 64px 0;
  }
}

.flow__header {
  margin-bottom: var(--kg-space-8);
  max-width: 60ch;
}

.flow__title {
  font-size: clamp(28px, 3.5vw, 36px);
  font-weight: 600;
  line-height: 1.2;
  letter-spacing: -0.01em;
  margin: 0 0 var(--kg-space-3);
  color: var(--kg-color-text-primary);
}

.flow__sub {
  font-size: var(--kg-text-md);
  line-height: 1.6;
  color: var(--kg-color-text-secondary);
  margin: 0;
}

.flow__list {
  display: grid;
  grid-template-columns: repeat(10, 1fr);
  gap: var(--kg-space-3);
  margin: 0;
  padding: 0;
  list-style: none;
}

@media (max-width: 1024px) {
  .flow__list {
    grid-template-columns: repeat(5, 1fr);
    row-gap: var(--kg-space-6);
  }
}

@media (max-width: 768px) {
  .flow__list {
    grid-template-columns: repeat(2, 1fr);
    row-gap: var(--kg-space-5);
  }
}

.flow__step {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--kg-space-2);
  padding-top: var(--kg-space-5);
}

.flow__dot {
  position: absolute;
  top: 0;
  left: 0;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background-color: var(--kg-color-accent);
  box-shadow: 0 0 0 4px var(--kg-color-accent-soft);
}

.flow__connector {
  position: absolute;
  top: 4px;
  left: 14px;
  width: calc(100% - 14px);
  height: 1px;
  background: linear-gradient(
    90deg,
    var(--kg-color-accent) 0%,
    var(--kg-color-accent-soft) 60%,
    transparent 100%
  );
}

@media (max-width: 1024px) {
  /* 平板 5 列布局下,connector 会越过格子边界,改为隐藏 */
  .flow__connector {
    display: none;
  }
}

.flow__num {
  font-family: var(--kg-font-mono);
  font-size: var(--kg-text-xs);
  color: var(--kg-color-accent-hover);
  letter-spacing: 0.06em;
  line-height: 1;
}

.flow__label {
  font-size: var(--kg-text-base);
  font-weight: 600;
  color: var(--kg-color-text-primary);
  line-height: 1.3;
}

.flow__detail {
  font-size: var(--kg-text-xs);
  color: var(--kg-color-text-mute);
  line-height: 1.5;
}

@media (prefers-reduced-motion: reduce) {
  .flow__dot {
    box-shadow: 0 0 0 2px var(--kg-color-accent-soft);
  }
}
</style>
