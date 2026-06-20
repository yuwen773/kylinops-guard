<script setup lang="ts">
/**
 * ConsolePreview - Hero 右侧迷你控制台预览。
 *
 * 设计原则:
 *   - 渲染真实的 RiskLevelTag 与 ToolCallCard 组件,而不是 div-based
 *     假截图 (design-taste-frontend §4.8 禁令)。
 *   - 数据是静态 fixture (健康检查场景),与 ChatConsole 真实返回同源。
 *   - 不调用任何 API,无副作用,纯展示。
 *
 * 数据来源:
 *   - 3 个 L0 工具调用 + 1 个 L1 风险判定 (与 ChatConsole 健康检查场景一致)
 */
import { computed } from 'vue'
import RiskLevelTag from '@/components/RiskLevelTag/index.vue'
import ToolCallCard from '@/components/ToolCallCard/index.vue'

const toolCalls = [
  {
    toolName: 'system_info_tool',
    status: 'success' as const,
    output: 'hostname=ops-guard-01  uptime=42d  load=0.34',
  },
  {
    toolName: 'cpu_status_tool',
    status: 'success' as const,
    output: 'cores=8  user=12.4%  sys=3.1%  iowait=0.8%',
  },
  {
    toolName: 'disk_usage_tool',
    status: 'success' as const,
    output: '/ 82%  /var 71%  /tmp 12%',
  },
]

const verdict = computed(() => 'L1' as const)
const decision = computed(() => 'ALLOW' as const)
</script>

<template>
  <div class="console-preview" role="img" aria-label="对话控制台预览:健康检查场景">
    <div class="console-preview__chrome">
      <span class="console-preview__dot" aria-hidden="true" />
      <span class="console-preview__dot" aria-hidden="true" />
      <span class="console-preview__dot" aria-hidden="true" />
      <span class="console-preview__path">POST /api/chat/send</span>
      <span class="console-preview__fixture-label">示例数据 / sample fixture</span>
    </div>

    <div class="console-preview__body">
      <div class="console-preview__row">
        <span class="console-preview__user">操作员 ›</span>
        <span class="console-preview__msg">做一次全量健康检查</span>
      </div>
      <div class="console-preview__row">
        <span class="console-preview__agent">Guard ›</span>
        <span class="console-preview__msg">检测到 4 项指标,正在调度工具…</span>
      </div>

      <div class="console-preview__tools">
        <ToolCallCard
          v-for="call in toolCalls"
          :key="call.toolName"
          :tool-name="call.toolName"
          :status="call.status"
          :output="call.output"
        />
      </div>

      <div class="console-preview__verdict">
        <RiskLevelTag :level="verdict" :decision="decision" />
        <span class="console-preview__audit">auditId · 7f3a-2b1c-d04e</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.console-preview {
  background-color: var(--kg-color-surface);
  border: 1px solid var(--kg-color-border-strong);
  border-radius: var(--kg-radius-md);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(2, 6, 16, 0.48);
}

.console-preview__chrome {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 10px 14px;
  border-bottom: 1px solid var(--kg-color-border);
  background-color: var(--kg-color-surface-soft);
}

.console-preview__path {
  flex: 1;
  font-family: var(--kg-font-mono);
  font-size: 11px;
  color: var(--kg-color-text-mute);
}

.console-preview__dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background-color: var(--kg-color-border-strong);
}

.console-preview__fixture-label {
  font-family: var(--kg-font-mono);
  font-size: 10px;
  color: var(--kg-color-text-mute);
  opacity: 0.55;
  letter-spacing: 0.04em;
  user-select: none;
}

.console-preview__body {
  padding: var(--kg-space-5);
  display: flex;
  flex-direction: column;
  gap: var(--kg-space-3);
  font-size: var(--kg-text-sm);
}

.console-preview__row {
  display: flex;
  gap: var(--kg-space-2);
  align-items: baseline;
}

.console-preview__user,
.console-preview__agent {
  font-family: var(--kg-font-mono);
  color: var(--kg-color-accent-hover);
  font-weight: 600;
  flex-shrink: 0;
}

.console-preview__msg {
  color: var(--kg-color-text-secondary);
  line-height: 1.5;
}

.console-preview__tools {
  display: flex;
  flex-direction: column;
  gap: var(--kg-space-2);
  padding-top: var(--kg-space-2);
  border-top: 1px solid var(--kg-color-border);
}

/* ToolCallCard 内部样式为全局 scoped,这里只覆盖外层间距 */
.console-preview__tools :deep(.tool-call-card) {
  margin-bottom: 0;
  background-color: var(--kg-color-surface-code);
}

.console-preview__verdict {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--kg-space-3);
  padding-top: var(--kg-space-3);
  border-top: 1px solid var(--kg-color-border);
}

.console-preview__audit {
  font-family: var(--kg-font-mono);
  font-size: var(--kg-text-xs);
  color: var(--kg-color-text-mute);
}

@media (max-width: 768px) {
  .console-preview__verdict {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
