<script setup lang="ts">
/**
 * DemoScenarios - 4 个 6:30 演示场景卡片。
 *
 * 设计规范 (P1-03):
 *   - 桌面 2x2 网格;移动端单列
 *   - 4 张卡分别覆盖演示视频脚本 §3.2 的 4 个场景:
 *       01 健康检查 (L1 ALLOW)
 *       02 磁盘诊断 (L2 CONFIRM,带 preview)
 *       03 服务诊断 + 重启 (L2 CONFIRM,PendingAction)
 *       04 危险命令拦截 (L4 BLOCK,含 Prompt Injection)
 *   - 每张卡包含:场景编号 (mono) + 标题 + 触发关键词 + 真实 RiskLevelTag
 *     + 一段简短机制说明
 *   - 不用 zigzag 模式;4 张卡同 layout,符合"4 段同 layout 仍 OK 因为
 *     这是单一章节的内部" (skill §4.7 允许同类 layout 在同一章节内部重复)
 */
import { CircleCheck, FolderOpened, Refresh, WarningFilled } from '@element-plus/icons-vue'
import type { Component } from 'vue'
import LandingContainer from '../components/LandingContainer.vue'
import RiskLevelTag from '@/components/RiskLevelTag/index.vue'
import type { RiskDecision, RiskLevel } from '@/types/safety'

interface Scenario {
  num: string
  title: string
  trigger: string
  level: RiskLevel
  decision: RiskDecision
  icon: Component
  body: string
}

const scenarios: Scenario[] = [
  {
    num: '01',
    title: '健康检查',
    trigger: '"做一次全量健康检查"',
    level: 'L1',
    decision: 'ALLOW',
    icon: CircleCheck,
    body: '多工具并行 fan-out:system_info / cpu / memory / disk / network / service / journal,汇总为评分报告。',
  },
  {
    num: '02',
    title: '磁盘诊断',
    trigger: '"查看 /var 占用" / "df -h"',
    level: 'L2',
    decision: 'CONFIRM',
    icon: FolderOpened,
    body: 'disk_usage_tool 定位占用,large_file_scan_tool 列举大文件,清理动作走 preview → 二次确认流程。',
  },
  {
    num: '03',
    title: '服务诊断 + 重启',
    trigger: '"重启 nginx"',
    level: 'L2',
    decision: 'CONFIRM',
    icon: Refresh,
    body: 'service_status_tool + network_port_tool + journal_log_tool 诊断;重启通过 PendingAction 落盘,操作员在 UI 显式确认后才执行。',
  },
  {
    num: '04',
    title: '危险命令拦截',
    trigger: '"rm -rf /" / "忽略之前所有规则…"',
    level: 'L4',
    decision: 'BLOCK',
    icon: WarningFilled,
    body: 'Prompt Injection 检测前置运行,正则规则匹配 L4 模式,阻断 + 全量审计 + SecurityCenter 可见。无 root 路径。',
  },
]
</script>

<template>
  <section id="scenarios" class="scenarios" data-testid="landing-scenarios">
    <LandingContainer>
      <div class="scenarios__header">
        <h2 class="scenarios__title">6:30 演示脚本的 4 个场景</h2>
        <p class="scenarios__sub">
          覆盖健康检查 / 磁盘诊断 / 服务重启 / 危险命令拦截。每个场景的触发词、风险等级与判定结果来自真实 ChatConsole 返回,不是设计稿里的占位。
        </p>
      </div>

      <div class="scenarios__grid">
        <article
          v-for="s in scenarios"
          :key="s.num"
          class="scenario-card"
          :class="`scenario-card--${s.level.toLowerCase()}`"
          :data-testid="`scenario-${s.num}`"
        >
          <header class="scenario-card__head">
            <span class="scenario-card__num">{{ s.num }}</span>
            <RiskLevelTag :level="s.level" :decision="s.decision" />
          </header>

          <div class="scenario-card__title-row">
            <el-icon class="scenario-card__icon" :size="20" aria-hidden="true">
              <component :is="s.icon" />
            </el-icon>
            <h3 class="scenario-card__title">{{ s.title }}</h3>
          </div>

          <code class="scenario-card__trigger">{{ s.trigger }}</code>

          <p class="scenario-card__body">{{ s.body }}</p>
        </article>
      </div>
    </LandingContainer>
  </section>
</template>

<style scoped>
.scenarios {
  padding: 96px 0;
}

@media (max-width: 768px) {
  .scenarios {
    padding: 64px 0;
  }
}

.scenarios__header {
  margin-bottom: var(--kg-space-8);
  max-width: 60ch;
}

.scenarios__title {
  font-size: clamp(28px, 3.5vw, 36px);
  font-weight: 600;
  line-height: 1.2;
  letter-spacing: -0.01em;
  margin: 0 0 var(--kg-space-3);
  color: var(--kg-color-text-primary);
}

.scenarios__sub {
  font-size: var(--kg-text-md);
  line-height: 1.6;
  color: var(--kg-color-text-secondary);
  margin: 0;
}

.scenarios__grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: var(--kg-space-5);
}

@media (max-width: 768px) {
  .scenarios__grid {
    grid-template-columns: 1fr;
  }
}

.scenario-card {
  display: flex;
  flex-direction: column;
  gap: var(--kg-space-3);
  padding: var(--kg-space-6);
  border: 1px solid var(--kg-color-border);
  border-radius: var(--kg-radius-md);
  background-color: var(--kg-color-surface);
  position: relative;
  transition: border-color var(--kg-transition-base), transform var(--kg-transition-base);
}

.scenario-card:hover {
  border-color: var(--kg-color-border-strong);
  transform: translateY(-1px);
}

.scenario-card--l4 {
  border-color: var(--kg-color-risk-l4-soft);
  background: linear-gradient(
    180deg,
    var(--kg-color-surface) 0%,
    var(--kg-color-risk-l4-soft) 200%
  );
}

.scenario-card--l2 {
  border-color: var(--kg-color-risk-l2-soft);
}

.scenario-card__head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.scenario-card__num {
  font-family: var(--kg-font-mono);
  font-size: var(--kg-text-xs);
  color: var(--kg-color-text-mute);
  letter-spacing: 0.06em;
}

.scenario-card__title-row {
  display: flex;
  align-items: center;
  gap: var(--kg-space-3);
}

.scenario-card__icon {
  color: var(--kg-color-accent-hover);
}

.scenario-card--l4 .scenario-card__icon {
  color: var(--kg-color-risk-l4);
}

.scenario-card--l2 .scenario-card__icon {
  color: var(--kg-color-risk-l2);
}

.scenario-card__title {
  font-size: var(--kg-text-xl);
  font-weight: 600;
  color: var(--kg-color-text-primary);
  margin: 0;
  line-height: 1.3;
}

.scenario-card__trigger {
  display: inline-block;
  padding: 6px 12px;
  background-color: var(--kg-color-surface-code);
  border: 1px solid var(--kg-color-border);
  border-radius: var(--kg-radius-sm);
  font-family: var(--kg-font-mono);
  font-size: var(--kg-text-sm);
  color: var(--kg-color-text-secondary);
  line-height: 1.4;
  align-self: flex-start;
}

.scenario-card__body {
  font-size: var(--kg-text-sm);
  line-height: 1.65;
  color: var(--kg-color-text-secondary);
  margin: 0;
}

@media (prefers-reduced-motion: reduce) {
  .scenario-card:hover {
    transform: none;
  }
}
</style>
