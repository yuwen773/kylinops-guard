<script setup lang="ts">
// ToolCenter — Phase 2 Task 11.
//
// Responsibilities (locked by 任务卡 §4 + 演示视频脚本 §3.5):
//   * Fetch the registered-tool catalog from GET /api/tools and render the
//     full inventory (≥ 8 OS tools expected). Each entry is displayed with:
//       - tool name + description
//       - risk level tag (L0..L4, rendered verbatim)
//       - permission type tag (READ/WRITE/EXECUTE/ADMIN)
//       - status tag (ENABLED / DISABLED)
//       - call statistics (callCount, successRate, lastCalledAt) from a
//         single server-side grouped aggregate
//   * successRate === null renders as "—" (never 0%, never 100%).
//   * lastCalledAt === null renders as "从未调用".
//   * Each row expands to show inputSchema / outputSchema (text
//     interpolation only — NEVER v-html, since the schema string is not
//     sanitized).
//
// Safety contract:
//   * Read-only view. The only outbound call is GET /api/tools.
//   * No risk evaluation. The backend has already produced the metadata
//     + statistics; we display them verbatim.
//   * No shell, no command forwarding, no auto-confirm.
import { computed, onMounted, ref } from 'vue';
import { getTools } from '@/api/tools';
import { ApiError } from '@/api/client';
import type { ToolDefinition } from '@/types/tool';
import AppSectionHeader from '@/components/common/AppSectionHeader.vue';
import AppLoadingState from '@/components/common/AppLoadingState.vue';
import AppErrorState from '@/components/common/AppErrorState.vue';
import AppEmptyState from '@/components/common/AppEmptyState.vue';

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------

const tools = ref<ToolDefinition[]>([]);
const loading = ref(false);
const error = ref<string | null>(null);
const hasLoaded = ref(false);

// ---------------------------------------------------------------------------
// Fetch
// ---------------------------------------------------------------------------

const fetchTools = async () => {
  loading.value = true;
  error.value = null;
  try {
    const result = await getTools();
    tools.value = Array.isArray(result) ? result : [];
    hasLoaded.value = true;
  } catch (err) {
    const e = err as ApiError;
    error.value = e?.message ?? '工具目录加载失败';
    tools.value = [];
  } finally {
    loading.value = false;
  }
};

onMounted(() => {
  void fetchTools();
});

const onRefreshClick = () => {
  if (loading.value) return;
  void fetchTools();
};

// ---------------------------------------------------------------------------
// Presentation helpers — Chinese labels for risk / permission / status.
// No numeric thresholds; these are pure verbatim renderings.
// ---------------------------------------------------------------------------

const RISK_LEVEL_LABEL: Readonly<Record<string, string>> = {
  L0: 'L0 信息查询',
  L1: 'L1 轻度风险',
  L2: 'L2 需确认',
  L3: 'L3 高风险',
  L4: 'L4 严重风险',
};

const PERMISSION_LABEL: Readonly<Record<string, string>> = {
  READ: 'READ 只读',
  WRITE: 'WRITE 写入',
  EXECUTE: 'EXECUTE 执行',
  ADMIN: 'ADMIN 管理',
};

const STATUS_LABEL: Readonly<Record<string, string>> = {
  ENABLED: '已启用',
  DISABLED: '已停用',
};

const riskLevelText = (level: string): string =>
  RISK_LEVEL_LABEL[level] ?? level;

const permissionText = (p: string): string => PERMISSION_LABEL[p] ?? p;

const statusText = (s: string): string => STATUS_LABEL[s] ?? s;

/**
 * Tone for the status tag. ENABLED = success (green), DISABLED = info (grey).
 * The backend already produced the truth; we only color-code the UI.
 */
const statusTone = (s: string): 'success' | 'info' =>
  s === 'ENABLED' ? 'success' : 'info';

// ---------------------------------------------------------------------------
// Statistics display — successRate / lastCalledAt nullability handled here.
// ---------------------------------------------------------------------------

const successRateText = (rate: number | null): string => {
  if (rate === null || rate === undefined) return '—';
  // Avoid floating-point noise — keep two decimals.
  const pct = (rate * 100).toFixed(2);
  return `${pct}%`;
};

const successRateTone = (
  rate: number | null,
): 'success' | 'warning' | 'danger' | 'info' => {
  if (rate === null || rate === undefined) return 'info';
  if (rate >= 0.99) return 'success';
  if (rate >= 0.7) return 'warning';
  return 'danger';
};

const lastCalledAtText = (iso: string | null): string => {
  if (!iso) return '从未调用';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleString('zh-CN', { hour12: false });
};

const timeoutText = (ms: number): string => `${ms} ms`;

const refreshButtonText = computed<string>(() =>
  loading.value ? '加载中…' : '刷新',
);

const toolCount = computed<number>(() => tools.value.length);
const emptyVisible = computed<boolean>(
  () => !loading.value && !error.value && hasLoaded.value && tools.value.length === 0,
);

const auditedCount = computed<number>(
  () => tools.value.filter((t) => t.auditRequired).length,
);

// ---------------------------------------------------------------------------
// Risk tone — purely cosmetic, mirrors RiskLevelTag semantics.
// ---------------------------------------------------------------------------

const riskTone = (
  level: string,
): 'success' | 'warning' | 'danger' => {
  if (level === 'L0' || level === 'L1') return 'success';
  if (level === 'L2') return 'warning';
  return 'danger';
};
</script>

<template>
  <div class="tool-center-page" data-testid="tool-center-page">
    <el-card class="page-card" shadow="never">
      <template #header>
        <AppSectionHeader
          level="section"
          title="工具中心"
          subtitle="注册的 OpsTool 目录与调用统计；统计由后端单次聚合，禁止前端逐工具查询"
        >
          <template #actions>
            <el-button
              type="primary"
              :loading="loading"
              :disabled="loading"
              class="page-refresh-button"
              data-testid="tool-refresh-button"
              @click="onRefreshClick"
            >
              {{ refreshButtonText }}
            </el-button>
          </template>
        </AppSectionHeader>
      </template>

      <section class="tool-summary" data-testid="tool-summary">
        <span class="summary-line">
          <strong>已注册：</strong>
          <span data-testid="tool-count">{{ toolCount }}</span>
          个工具
        </span>
        <span class="summary-line">
          <strong>需审计：</strong>
          <span data-testid="tool-audited-count">{{ auditedCount }}</span>
          个
        </span>
      </section>

      <div
        v-if="loading && !hasLoaded"
        data-testid="tool-loading"
      >
        <AppLoadingState title="正在加载工具目录…" />
      </div>

      <div
        v-else-if="error"
        data-testid="tool-error"
      >
        <AppErrorState
          variant="transient"
          :title="`工具目录加载失败：${error}`"
        >
          <template #action>
            <el-button
              size="small"
              type="primary"
              :disabled="loading"
              @click="onRefreshClick"
            >
              立即重试
            </el-button>
          </template>
        </AppErrorState>
      </div>

      <div
        v-else-if="emptyVisible"
        data-testid="tool-empty"
      >
        <AppEmptyState
          variant="no-tool"
          title="暂无注册工具"
          description="后端尚未注册任何 OpsTool，请联系管理员检查启动配置。"
        />
      </div>

      <el-table
        v-else-if="tools.length > 0"
        class="tool-table"
        :data="tools"
        :row-key="(row: ToolDefinition) => row.toolName"
        data-testid="tool-table"
      >
        <el-table-column type="expand">
          <template #default="{ row }">
            <section
              class="tool-schema"
              :data-testid="`tool-schema-${row.toolName}`"
            >
              <el-descriptions
                class="tool-schema-descriptions"
                :column="1"
                border
              >
                <el-descriptions-item label="工具名称">
                  <code class="tool-schema-name">{{ row.toolName }}</code>
                </el-descriptions-item>
                <el-descriptions-item label="超时时间">
                  {{ timeoutText(row.timeoutMs) }}
                </el-descriptions-item>
                <el-descriptions-item label="需要审计">
                  {{ row.auditRequired ? '是' : '否' }}
                </el-descriptions-item>
                <el-descriptions-item label="输入 Schema">
                  <pre
                    class="tool-schema-block"
                    :data-testid="`tool-input-schema-${row.toolName}`"
                  >{{ row.inputSchema }}</pre>
                </el-descriptions-item>
                <el-descriptions-item label="输出 Schema">
                  <pre
                    class="tool-schema-block"
                    :data-testid="`tool-output-schema-${row.toolName}`"
                  >{{ row.outputSchema }}</pre>
                </el-descriptions-item>
              </el-descriptions>
            </section>
          </template>
        </el-table-column>

        <el-table-column
          label="工具"
          min-width="220"
        >
          <template #default="{ row }">
            <div
              class="tool-cell"
              :data-testid="`tool-row-${row.toolName}`"
            >
              <code class="tool-name">{{ row.toolName }}</code>
              <span class="tool-description">{{ row.description }}</span>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="风险" width="140">
          <template #default="{ row }">
            <el-tag
              :type="riskTone(row.riskLevel)"
              effect="dark"
              round
              :data-testid="`tool-risk-level-${row.toolName}`"
            >
              {{ riskLevelText(row.riskLevel) }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="权限" width="140">
          <template #default="{ row }">
            <el-tag
              type="info"
              effect="plain"
              :data-testid="`tool-permission-${row.toolName}`"
            >
              {{ permissionText(row.permissionType) }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag
              :type="statusTone(row.toolStatus)"
              effect="plain"
              :data-testid="`tool-status-${row.toolName}`"
            >
              {{ statusText(row.toolStatus) }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="调用次数" width="100">
          <template #default="{ row }">
            <span
              class="tool-stat-num"
              :data-testid="`tool-call-count-${row.toolName}`"
            >{{ row.callCount ?? 0 }}</span>
          </template>
        </el-table-column>

        <el-table-column label="成功率" width="120">
          <template #default="{ row }">
            <el-tag
              :type="successRateTone(row.successRate)"
              effect="plain"
              :data-testid="`tool-success-rate-${row.toolName}`"
            >
              {{ successRateText(row.successRate) }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="最近调用" width="200">
          <template #default="{ row }">
            <span
              class="tool-last-called"
              :data-testid="`tool-last-called-${row.toolName}`"
            >{{ lastCalledAtText(row.lastCalledAt) }}</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.tool-center-page {
  max-width: 1280px;
  margin: 0 auto;
  width: 100%;
}

.page-card {
  width: 100%;
}

.tool-summary {
  display: flex;
  flex-wrap: wrap;
  gap: var(--kg-space-4);
  margin-bottom: var(--kg-space-3);
  padding: var(--kg-space-2) var(--kg-space-3);
  background: var(--kg-color-surface-soft);
  border: 1px solid var(--kg-color-border-mute);
  border-radius: var(--kg-radius-sm);
  font-size: var(--kg-text-sm);
  color: var(--kg-color-text-secondary);
}

.summary-line strong {
  margin-right: var(--kg-space-1);
  color: var(--kg-color-text-primary);
}

.tool-table {
  margin-top: var(--kg-space-1);
}

.tool-cell {
  display: flex;
  flex-direction: column;
  gap: var(--kg-space-1);
}

.tool-name {
  font-family: var(--kg-font-mono);
  font-size: var(--kg-text-sm);
  font-weight: 600;
  color: var(--kg-color-text-primary);
}

.tool-description {
  font-size: var(--kg-text-sm);
  color: var(--kg-color-text-secondary);
}

.tool-stat-num {
  font-family: var(--kg-font-mono);
  font-weight: 600;
  color: var(--kg-color-text-primary);
}

.tool-last-called {
  font-family: var(--kg-font-mono);
  font-size: var(--kg-text-sm);
  color: var(--kg-color-text-secondary);
  white-space: nowrap;
}

.tool-schema {
  padding: var(--kg-space-2) var(--kg-space-4);
  background: var(--kg-color-surface-soft);
}

.tool-schema-descriptions {
  margin-bottom: var(--kg-space-2);
}

.tool-schema-name {
  font-family: var(--kg-font-mono);
  font-size: var(--kg-text-sm);
  color: var(--kg-color-text-primary);
}

.tool-schema-block {
  margin: 0;
  padding: var(--kg-space-2) var(--kg-space-3);
  background: var(--kg-color-surface-code);
  color: var(--kg-color-text-secondary);
  font-family: var(--kg-font-mono);
  font-size: var(--kg-text-sm);
  white-space: pre-wrap;
  word-break: break-all;
  border: 1px solid var(--kg-color-border-mute);
  border-radius: var(--kg-radius-sm);
  max-height: 200px;
  overflow: auto;
  line-height: var(--kg-line-base);
}
</style>