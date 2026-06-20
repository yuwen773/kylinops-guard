<script setup lang="ts">
/**
 * ToolMatrix - 10 个 MCP 工具矩阵。
 *
 * 设计规范 (P1-03):
 *   - 单列 mono 列表,左侧 icon + tool name + 描述,右侧 risk tag
 *   - 不用每行 border;顶部 1px 边线 + 12px 行高 (skill §4.9 long list 禁令)
 *   - 移动端保持单列
 *   - 工具清单精确匹配 PRD v0.1 §4.2 (P0 工具,10 个 L0/L1)
 */
import {
  Cpu,
  Document,
  Files,
  Histogram,
  List,
  Monitor,
  Service,
  View,
} from '@element-plus/icons-vue'
import type { Component } from 'vue'
import LandingContainer from '../components/LandingContainer.vue'
import RiskLevelTag from '@/components/RiskLevelTag/index.vue'
import type { RiskLevel } from '@/types/safety'

interface ToolRow {
  icon: Component
  name: string
  desc: string
  level: RiskLevel
}

const tools: ToolRow[] = [
  { icon: Monitor, name: 'system_info_tool', desc: '系统状态汇总(主机名/内核/启动时间)', level: 'L0' },
  { icon: Cpu, name: 'cpu_status_tool', desc: 'CPU 负载与核心使用率', level: 'L0' },
  { icon: Histogram, name: 'memory_status_tool', desc: '内存使用与 SWAP', level: 'L0' },
  { icon: Files, name: 'disk_usage_tool', desc: '磁盘使用率与挂载点', level: 'L0' },
  { icon: Document, name: 'large_file_scan_tool', desc: '大文件扫描(可触发清理 preview)', level: 'L1' },
  { icon: List, name: 'process_list_tool', desc: '进程列表', level: 'L0' },
  { icon: View, name: 'process_detail_tool', desc: '进程详情', level: 'L0' },
  { icon: Service, name: 'network_port_tool', desc: '端口监听', level: 'L0' },
  { icon: Service, name: 'service_status_tool', desc: 'systemd 服务状态', level: 'L0' },
  { icon: Document, name: 'journal_log_tool', desc: 'journal 日志查询', level: 'L0' },
]
</script>

<template>
  <section id="tools" class="tool-matrix" data-testid="landing-tool-matrix">
    <LandingContainer>
      <div class="tool-matrix__header">
        <h2 class="tool-matrix__title">10 个内置 MCP 工具</h2>
        <p class="tool-matrix__sub">
          每个工具有独立 inputSchema/outputSchema、超时与截断策略。注册时声明风险等级,所有调用经 RiskCheck。
        </p>
      </div>

      <ul class="tool-matrix__list">
        <li
          v-for="(t, i) in tools"
          :key="t.name"
          class="tool-matrix__row"
          :class="{ 'is-first': i === 0 }"
        >
          <el-icon class="tool-matrix__icon" :size="18" aria-hidden="true">
            <component :is="t.icon" />
          </el-icon>
          <span class="tool-matrix__name">{{ t.name }}</span>
          <span class="tool-matrix__desc">{{ t.desc }}</span>
          <span class="tool-matrix__tag">
            <RiskLevelTag :level="t.level" />
          </span>
        </li>
      </ul>
    </LandingContainer>
  </section>
</template>

<style scoped>
.tool-matrix {
  padding: 96px 0;
  background-color: var(--kg-color-surface);
  border-top: 1px solid var(--kg-color-border);
  border-bottom: 1px solid var(--kg-color-border);
}

@media (max-width: 768px) {
  .tool-matrix {
    padding: 64px 0;
  }
}

.tool-matrix__header {
  margin-bottom: var(--kg-space-7);
  max-width: 60ch;
}

.tool-matrix__title {
  font-size: clamp(28px, 3.5vw, 36px);
  font-weight: 600;
  line-height: 1.2;
  letter-spacing: -0.01em;
  margin: 0 0 var(--kg-space-3);
  color: var(--kg-color-text-primary);
}

.tool-matrix__sub {
  font-size: var(--kg-text-md);
  line-height: 1.6;
  color: var(--kg-color-text-secondary);
  margin: 0;
}

.tool-matrix__list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
}

.tool-matrix__row {
  display: grid;
  grid-template-columns: 24px 220px 1fr 80px;
  align-items: center;
  gap: var(--kg-space-4);
  padding: 14px 0;
  border-top: 1px solid var(--kg-color-border-mute);
}

.tool-matrix__row.is-first {
  border-top-color: var(--kg-color-border-strong);
}

@media (max-width: 768px) {
  .tool-matrix__row {
    grid-template-columns: 24px 1fr 70px;
    gap: var(--kg-space-3);
  }
  .tool-matrix__desc {
    grid-column: 2 / -1;
    grid-row: 2;
  }
}

.tool-matrix__icon {
  color: var(--kg-color-accent-hover);
}

.tool-matrix__name {
  font-family: var(--kg-font-mono);
  font-size: var(--kg-text-sm);
  color: var(--kg-color-text-primary);
  font-weight: 500;
}

.tool-matrix__desc {
  font-size: var(--kg-text-sm);
  color: var(--kg-color-text-secondary);
  line-height: 1.5;
}

.tool-matrix__tag {
  justify-self: end;
}
</style>
