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
        <div class="page-header">
          <div>
            <span class="page-title">工具中心</span>
            <span class="page-subtitle">
              注册的 OpsTool 目录与调用统计；统计由后端单次聚合，禁止前端逐工具查询
            </span>
          </div>
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
        </div>
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

      <p
        v-if="loading && !hasLoaded"
        class="tool-loading"
        data-testid="tool-loading"
      >
        正在加载工具目录…
      </p>

      <el-alert
        v-else-if="error"
        class="tool-error"
        type="error"
        :closable="false"
        show-icon
        data-testid="tool-error"
        :title="`工具目录加载失败：${error}`"
      />

      <p
        v-else-if="emptyVisible"
        class="tool-empty"
        data-testid="tool-empty"
      >
        暂无注册工具
      </p>

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

.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 1rem;
}

.page-title {
  font-weight: 600;
  font-size: 1.05rem;
  color: #1f2d3d;
}

.page-subtitle {
  display: block;
  margin-top: 0.25rem;
  font-size: 0.8rem;
  color: #909399;
}

.tool-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 1rem;
  margin-bottom: 0.75rem;
  padding: 0.5rem 0.75rem;
  background: #f5f7fa;
  border-radius: 4px;
  font-size: 0.85rem;
  color: #303133;
}

.summary-line strong {
  margin-right: 0.25rem;
  color: #1f2d3d;
}

.tool-loading {
  margin: 1rem 0;
  padding: 1.25rem;
  text-align: center;
  color: #909399;
  background: #f5f7fa;
  border-radius: 6px;
}

.tool-error {
  margin: 0.5rem 0;
}

.tool-empty {
  margin: 1rem 0;
  padding: 1.5rem;
  text-align: center;
  color: #909399;
  background: #f5f7fa;
  border-radius: 6px;
}

.tool-table {
  margin-top: 0.25rem;
}

.tool-cell {
  display: flex;
  flex-direction: column;
  gap: 0.15rem;
}

.tool-name {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 0.85rem;
  font-weight: 600;
  color: #1f2d3d;
}

.tool-description {
  font-size: 0.8rem;
  color: #606266;
}

.tool-stat-num {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-weight: 600;
  color: #303133;
}

.tool-last-called {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 0.8rem;
  color: #606266;
  white-space: nowrap;
}

.tool-schema {
  padding: 0.5rem 1rem;
  background: #fafbfc;
}

.tool-schema-descriptions {
  margin-bottom: 0.5rem;
}

.tool-schema-name {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 0.8rem;
  color: #1f2d3d;
}

.tool-schema-block {
  margin: 0;
  padding: 0.5rem 0.75rem;
  background: #1f2d3d;
  color: #e6f1ff;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 0.8rem;
  white-space: pre-wrap;
  word-break: break-all;
  border-radius: 4px;
  max-height: 200px;
  overflow: auto;
}
</style>