<script setup lang="ts">
/**
 * CapabilitiesBento - 6 cells 非对称核心能力网格。
 *
 * 设计规范 (P1-03):
 *   - 桌面 6 列网格:3+3 / 2+2+2 / 6 (3 行,不对称)
 *   - 6 cells 全部有视觉变化 (skill §4.7 强制):
 *       1. 自然语言意图理解 - primary gradient 底
 *       2. MCP 工具矩阵 - surface-code mono 风
 *       3. RiskCheck - risk tag inline
 *       4. SafeExecutor - 默认 surface
 *       5. AuditLog - 默认 surface
 *       6. 报告 + 巡检 + 通知 - wide cell 横跨
 *   - 移动端 (< 768px) 严格单列
 *   - 不放 emoji,全部 Element Plus icon
 */
import { Monitor, Tools, WarningFilled, Lock, Document, DataAnalysis } from '@element-plus/icons-vue'
import LandingContainer from '../components/LandingContainer.vue'
import LandingEyebrow from '../components/LandingEyebrow.vue'
import RiskLevelTag from '@/components/RiskLevelTag/index.vue'

interface BentoCell {
  span: 'a' | 'b' | 'c' | 'd' | 'e' | 'f'
  icon: typeof Monitor
  title: string
  body: string
  tone: 'primary' | 'code' | 'risk' | 'surface' | 'wide'
}

const cells: BentoCell[] = [
  {
    span: 'a',
    icon: Monitor,
    title: '自然语言意图理解',
    body: 'LLM 解析操作员指令,前置 Prompt Injection 检测,识别绕过安全规则的尝试。',
    tone: 'primary',
  },
  {
    span: 'b',
    icon: Tools,
    title: 'MCP 工具矩阵',
    body: '10+ OpsTool 注册,按 L0-L4 风险等级注册,每个工具有独立超时与截断策略。',
    tone: 'code',
  },
  {
    span: 'c',
    icon: WarningFilled,
    title: 'RiskCheck 预执行',
    body: '所有操作经风险规则引擎预检。',
    tone: 'risk',
  },
  {
    span: 'd',
    icon: Lock,
    title: 'SafeExecutor 受限执行',
    body: '受限账户运行,写操作走白名单,删除前置 preview 流程。',
    tone: 'surface',
  },
  {
    span: 'e',
    icon: Document,
    title: 'AuditLog 全量留痕',
    body: '共享 auditId 串联意图到执行,摘要写入不泄露敏感文件内容。',
    tone: 'surface',
  },
  {
    span: 'f',
    icon: DataAnalysis,
    title: '报告生成 + 定时巡检 + 通知',
    body: '一键导出诊断报告,定时巡检任务调度,审计事件通过加密通道外发飞书卡片。',
    tone: 'wide',
  },
]
</script>

<template>
  <section id="capabilities" class="bento" data-testid="landing-bento">
    <LandingContainer>
      <div class="bento__header">
        <LandingEyebrow text="Capabilities" />
        <h2 class="bento__title">护栏内置在 Agent 调用链路上</h2>
        <p class="bento__sub">
          6 项核心能力构成完整的"理解 → 规划 → 预检 → 执行 → 留痕 → 报告"闭环。
        </p>
      </div>

      <div class="bento__grid">
        <article
          v-for="cell in cells"
          :key="cell.span"
          :class="['bento__cell', `bento__cell--${cell.span}`, `bento__cell--${cell.tone}`]"
          :data-testid="`bento-cell-${cell.span}`"
        >
          <header class="bento__cell-head">
            <el-icon class="bento__icon" :size="22" aria-hidden="true">
              <component :is="cell.icon" />
            </el-icon>
            <h3 class="bento__cell-title">{{ cell.title }}</h3>
          </header>

          <p class="bento__cell-body">{{ cell.body }}</p>

          <!-- cell c: risk tag 实际渲染示例 -->
          <div v-if="cell.tone === 'risk'" class="bento__cell-extra">
            <RiskLevelTag level="L4" decision="BLOCK" />
            <span class="bento__cell-hint">rm -rf / 已阻断</span>
          </div>

          <!-- cell b: mono 工具矩阵示例 -->
          <div v-if="cell.tone === 'code'" class="bento__cell-extra bento__cell-extra--mono">
            <span><span class="bento__cell-key">L0</span> system_info_tool · cpu_status_tool</span>
            <span><span class="bento__cell-key">L1</span> large_file_scan_tool</span>
            <span><span class="bento__cell-key">L2</span> service_restart_action</span>
            <span><span class="bento__cell-key">L4</span> rm -rf / · chmod -R 777 /</span>
          </div>
        </article>
      </div>
    </LandingContainer>
  </section>
</template>

<style scoped>
.bento {
  padding: 96px 0;
}

@media (max-width: 768px) {
  .bento {
    padding: 64px 0;
  }
}

.bento__header {
  margin-bottom: var(--kg-space-8);
  max-width: 60ch;
}

.bento__title {
  font-size: clamp(28px, 3.5vw, 36px);
  font-weight: 600;
  line-height: 1.2;
  letter-spacing: -0.01em;
  margin: var(--kg-space-3) 0 var(--kg-space-3);
  color: var(--kg-color-text-primary);
}

.bento__sub {
  font-size: var(--kg-text-md);
  line-height: 1.6;
  color: var(--kg-color-text-secondary);
  margin: 0;
}

.bento__grid {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: var(--kg-space-4);
}

.bento__cell {
  padding: var(--kg-space-5);
  border: 1px solid var(--kg-color-border);
  border-radius: var(--kg-radius-md);
  background-color: var(--kg-color-surface);
  display: flex;
  flex-direction: column;
  gap: var(--kg-space-3);
  min-height: 180px;
  transition: border-color var(--kg-transition-base), transform var(--kg-transition-base);
}

.bento__cell:hover {
  border-color: var(--kg-color-border-strong);
  transform: translateY(-1px);
}

/* 桌面不对称网格 */
.bento__cell--a {
  grid-column: span 3;
  background: linear-gradient(135deg, var(--kg-color-primary-soft) 0%, transparent 70%);
}

.bento__cell--b {
  grid-column: span 3;
  background-color: var(--kg-color-surface-code);
}

.bento__cell--c,
.bento__cell--d,
.bento__cell--e {
  grid-column: span 2;
}

.bento__cell--f {
  grid-column: span 6;
  background: linear-gradient(
    180deg,
    var(--kg-color-surface) 0%,
    var(--kg-color-surface-soft) 100%
  );
}

@media (max-width: 1024px) {
  .bento__cell--a,
  .bento__cell--b {
    grid-column: span 6;
  }
  .bento__cell--c,
  .bento__cell--d,
  .bento__cell--e {
    grid-column: span 3;
  }
}

@media (max-width: 768px) {
  .bento__grid {
    grid-template-columns: 1fr;
  }
  .bento__cell--a,
  .bento__cell--b,
  .bento__cell--c,
  .bento__cell--d,
  .bento__cell--e,
  .bento__cell--f {
    grid-column: 1 / -1;
  }
}

.bento__cell-head {
  display: flex;
  align-items: center;
  gap: var(--kg-space-3);
}

.bento__icon {
  color: var(--kg-color-accent-hover);
  flex-shrink: 0;
}

.bento__cell--a .bento__icon {
  color: var(--kg-color-primary-hover);
}

.bento__cell-title {
  font-size: var(--kg-text-lg);
  font-weight: 600;
  color: var(--kg-color-text-primary);
  margin: 0;
  line-height: 1.3;
}

.bento__cell-body {
  font-size: var(--kg-text-sm);
  line-height: 1.6;
  color: var(--kg-color-text-secondary);
  margin: 0;
  flex: 1;
}

.bento__cell-extra {
  display: flex;
  align-items: center;
  gap: var(--kg-space-3);
  padding-top: var(--kg-space-2);
  border-top: 1px solid var(--kg-color-border-mute);
}

.bento__cell-hint {
  font-family: var(--kg-font-mono);
  font-size: var(--kg-text-xs);
  color: var(--kg-color-text-mute);
}

.bento__cell-extra--mono {
  flex-direction: column;
  align-items: stretch;
  gap: var(--kg-space-1);
  font-family: var(--kg-font-mono);
  font-size: var(--kg-text-xs);
  color: var(--kg-color-text-secondary);
}

.bento__cell-key {
  display: inline-block;
  width: 24px;
  color: var(--kg-color-accent-hover);
  font-weight: 600;
}

@media (prefers-reduced-motion: reduce) {
  .bento__cell:hover {
    transform: none;
  }
}
</style>
