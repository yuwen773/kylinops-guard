<script setup lang="ts">
// Dashboard — Phase 2 Task 10.
//
// Responsibilities (locked by 任务卡 §4 + 演示视频脚本 §3.1):
//   * Top section: three summary cards — health score, coverage (successful /
//     total), and collection time + audit id. All values come from the
//     backend /api/dashboard/overview response; the frontend never invents
//     numbers.
//   * Middle section: a grid of StatusMetricCard tiles, one per backend
//     metric. Failed metrics render independently as "数据不可用" /
//     "部分降级" — the page is never blanked by a per-tool failure.
//   * Threshold cosmetics: the page derives a per-metric "warning" /
//     "critical" tone tag from numeric payload fields (CPU usagePercent,
//     memory usedPercent, disk partition usedPercent). This is purely a
//     display hint and NEVER changes the backend score (which is rendered
//     verbatim from `score`).
//   * Manual refresh button: disabled while in-flight, label switches to
//     "采集中…", and a failed refresh KEEPS the previous successful
//     snapshot on screen with a "stale" banner — the operator can still
//     act on the last known state instead of staring at a blank page.
//   * No hard-coded OS numbers. Every rendered value is sourced from the
//     backend payload or labelled "—" / "数据不可用".
//
// Safety contract:
//   * Read-only view. The only outbound call is GET /api/dashboard/overview.
//   * No risk evaluation. The backend has already produced the final
//     score, coverage, and per-tool statuses; we display them verbatim.
//   * No shell, no command forwarding, no auto-confirm.

import { computed, onMounted, ref } from 'vue';
import { getDashboardOverview } from '@/api/dashboard';
import { ApiError } from '@/api/client';
import StatusMetricCard, { type StatusMetricStatus } from '@/components/StatusMetricCard/index.vue';
import AppSectionHeader from '@/components/common/AppSectionHeader.vue';
import AppLoadingState from '@/components/common/AppLoadingState.vue';
import AppErrorState from '@/components/common/AppErrorState.vue';
import AppStateBanner from '@/components/common/AppStateBanner.vue';
import type { DashboardMetric, DashboardOverview } from '@/types/dashboard';

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------

/** Last successful snapshot. Never blanked by a transient refresh failure. */
const overview = ref<DashboardOverview | null>(null);
/** True only after the first fetch has resolved (success or failure). */
const hasLoaded = ref(false);
const inFlight = ref(false);
/** Non-null when the most recent attempt failed AND a previous snapshot is on screen. */
const staleError = ref<string | null>(null);
/** Non-null only on the very first load — no previous data to fall back to. */
const initialError = ref<string | null>(null);

// ---------------------------------------------------------------------------
// Fetch
// ---------------------------------------------------------------------------

const fetchOverview = async (isInitial: boolean) => {
  inFlight.value = true;
  try {
    const result = await getDashboardOverview();
    overview.value = result;
    hasLoaded.value = true;
    staleError.value = null;
    initialError.value = null;
  } catch (err) {
    const e = err as ApiError;
    const message = e?.message ?? '概览加载失败';
    if (isInitial || !overview.value) {
      initialError.value = message;
    } else {
      // We have a previous snapshot — keep it on screen and surface the
      // failure as a stale-data banner instead of clearing the page.
      staleError.value = message;
    }
  } finally {
    inFlight.value = false;
  }
};

onMounted(() => {
  void fetchOverview(true);
});

const onRefreshClick = () => {
  if (inFlight.value) return;
  void fetchOverview(false);
};

// ---------------------------------------------------------------------------
// Derived display values
// ---------------------------------------------------------------------------

const scoreText = computed<string>(() => {
  const s = overview.value?.score;
  if (s === null || s === undefined) return '—';
  return String(s);
});

const scoreTone = computed<'success' | 'warning' | 'danger' | 'info'>(() => {
  const s = overview.value?.score;
  if (s === null || s === undefined) return 'info';
  if (s >= 80) return 'success';
  if (s >= 60) return 'warning';
  return 'danger';
});

const coverageText = computed<string>(() => {
  const o = overview.value;
  if (!o) return '—';
  return `${o.successfulMetricCount} / ${o.totalMetricCount}`;
});

const coveragePercent = computed<number | null>(() => {
  const o = overview.value;
  if (!o || o.totalMetricCount === 0) return null;
  return Math.round((o.successfulMetricCount / o.totalMetricCount) * 100);
});

const collectedAtText = computed<string>(() => {
  const iso = overview.value?.collectedAt;
  if (!iso) return '—';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  // toLocaleString honors the browser locale; we pin zh-CN so the demo
  // shows a consistent Chinese timestamp regardless of dev host locale.
  return d.toLocaleString('zh-CN', { hour12: false });
});

const auditIdText = computed<string>(() => overview.value?.auditId ?? '—');

const degradedVisible = computed<boolean>(
  () => overview.value?.degraded === true,
);

const refreshButtonText = computed<string>(() => (inFlight.value ? '采集中…' : '刷新'));

// ---------------------------------------------------------------------------
// Per-metric display — title, unit, value, threshold hint, tone.
// All cosmetic, derived from the backend payload. No hard-coded values.
// ---------------------------------------------------------------------------

interface MetricView {
  key: string;
  title: string;
  unit?: string;
  value: number | string | null;
  threshold?: string;
  status: StatusMetricStatus;
  toolName: string;
  raw: DashboardMetric;
  errorReason?: string;
}

const toNumber = (v: unknown): number | null => {
  if (v === null || v === undefined) return null;
  if (typeof v === 'number' && Number.isFinite(v)) return v;
  if (typeof v === 'string') {
    const n = Number(v);
    return Number.isFinite(n) ? n : null;
  }
  return null;
};

/**
 * Build the StatusMetricCard props for a single backend metric. The tone
 * is derived from the payload's numeric fields with a small threshold
 * table; this is purely cosmetic and does not change the backend score.
 */
const buildMetricView = (m: DashboardMetric): MetricView => {
  const data = (m.data ?? {}) as Record<string, unknown>;

  // Failed metrics: render the card with no value and the failure reason.
  if (m.status !== 'success') {
    return {
      key: m.toolName,
      title: METRIC_TITLES[m.toolName] ?? m.toolName,
      value: null,
      status: 'unavailable',
      toolName: m.toolName,
      raw: m,
      errorReason: m.errorMessage,
    };
  }

  switch (m.toolName) {
    case 'cpu_status_tool': {
      const usage = toNumber(data.usagePercent);
      const load1 = toNumber(data.loadAvg1);
      if (usage === null) {
        return {
          key: m.toolName,
          title: METRIC_TITLES[m.toolName] ?? m.toolName,
          value: null,
          status: 'unavailable',
          toolName: m.toolName,
          raw: m,
        };
      }
      return {
        key: m.toolName,
        title: METRIC_TITLES[m.toolName] ?? m.toolName,
        value: usage,
        unit: '%',
        threshold: load1 !== null ? `负载 ${load1.toFixed(2)}` : undefined,
        status: classifyPercent(usage, 60, 80),
        toolName: m.toolName,
        raw: m,
      };
    }
    case 'memory_status_tool': {
      const pct = toNumber(data.usedPercent);
      if (pct === null) {
        return {
          key: m.toolName,
          title: METRIC_TITLES[m.toolName] ?? m.toolName,
          value: null,
          status: 'unavailable',
          toolName: m.toolName,
          raw: m,
        };
      }
      const total = toNumber(data.totalMB);
      return {
        key: m.toolName,
        title: METRIC_TITLES[m.toolName] ?? m.toolName,
        value: pct,
        unit: '%',
        threshold: total !== null ? `总量 ${total} MB` : undefined,
        status: classifyPercent(pct, 75, 90),
        toolName: m.toolName,
        raw: m,
      };
    }
    case 'disk_usage_tool': {
      const partitions = Array.isArray(data.partitions)
        ? (data.partitions as Array<Record<string, unknown>>)
        : [];
      if (partitions.length === 0) {
        return {
          key: m.toolName,
          title: METRIC_TITLES[m.toolName] ?? m.toolName,
          value: null,
          status: 'unavailable',
          toolName: m.toolName,
          raw: m,
        };
      }
      const maxPct = partitions.reduce<number>((acc, p) => {
        const v = toNumber(p.usedPercent);
        return v !== null && v > acc ? v : acc;
      }, 0);
      const top = partitions
        .map((p) => ({
          mount: typeof p.mount === 'string' ? p.mount : '',
          pct: toNumber(p.usedPercent),
        }))
        .filter((p) => p.pct !== null)
        .sort((a, b) => (b.pct as number) - (a.pct as number))[0];
      return {
        key: m.toolName,
        title: METRIC_TITLES[m.toolName] ?? m.toolName,
        value: maxPct,
        unit: '%',
        threshold: top?.mount ? `最高 ${top.mount}` : undefined,
        status: classifyPercent(maxPct, 80, 90),
        toolName: m.toolName,
        raw: m,
      };
    }
    case 'service_status_tool': {
      const active = typeof data.activeState === 'string' ? data.activeState : '';
      const sub = typeof data.subState === 'string' ? data.subState : '';
      const svc = typeof data.serviceName === 'string' ? data.serviceName : '';
      const label = active ? `${svc || '服务'} · ${active}` : svc || '服务';
      const isHealthy = active === 'active' && sub !== 'failed';
      return {
        key: m.toolName,
        title: METRIC_TITLES[m.toolName] ?? m.toolName,
        value: label,
        threshold: sub ? `子状态 ${sub}` : undefined,
        status: isHealthy ? 'ok' : 'warning',
        toolName: m.toolName,
        raw: m,
      };
    }
    case 'network_port_tool': {
      const ports = Array.isArray(data.ports) ? (data.ports as unknown[]) : [];
      return {
        key: m.toolName,
        title: METRIC_TITLES[m.toolName] ?? m.toolName,
        value: ports.length,
        unit: '个',
        threshold: '监听端口',
        status: 'ok',
        toolName: m.toolName,
        raw: m,
      };
    }
    case 'process_list_tool': {
      const procs = Array.isArray(data.processes) ? (data.processes as unknown[]) : [];
      return {
        key: m.toolName,
        title: METRIC_TITLES[m.toolName] ?? m.toolName,
        value: procs.length,
        unit: '个',
        threshold: '进程总数',
        status: 'ok',
        toolName: m.toolName,
        raw: m,
      };
    }
    case 'system_info_tool': {
      const host = typeof data.hostname === 'string' ? data.hostname : '';
      const os = typeof data.osName === 'string' ? data.osName : '';
      const arch = typeof data.arch === 'string' ? data.arch : '';
      const label = host || os || arch || '—';
      return {
        key: m.toolName,
        title: METRIC_TITLES[m.toolName] ?? m.toolName,
        value: label,
        threshold: arch ? `架构 ${arch}` : undefined,
        status: 'ok',
        toolName: m.toolName,
        raw: m,
      };
    }
    case 'large_file_scan_tool': {
      const files = Array.isArray(data.files) ? (data.files as unknown[]) : [];
      return {
        key: m.toolName,
        title: METRIC_TITLES[m.toolName] ?? m.toolName,
        value: files.length,
        unit: '项',
        threshold: '可清理大文件',
        status: files.length > 0 ? 'degraded' : 'ok',
        toolName: m.toolName,
        raw: m,
      };
    }
    case 'process_detail_tool': {
      return {
        key: m.toolName,
        title: METRIC_TITLES[m.toolName] ?? m.toolName,
        value: '可用',
        threshold: '按需查询',
        status: 'ok',
        toolName: m.toolName,
        raw: m,
      };
    }
    case 'journal_log_tool': {
      const lines = Array.isArray(data.entries) ? (data.entries as unknown[]) : [];
      return {
        key: m.toolName,
        title: METRIC_TITLES[m.toolName] ?? m.toolName,
        value: lines.length,
        unit: '条',
        threshold: '最近日志',
        status: 'ok',
        toolName: m.toolName,
        raw: m,
      };
    }
    default: {
      return {
        key: m.toolName,
        title: m.toolName,
        value: null,
        status: 'unavailable',
        toolName: m.toolName,
        raw: m,
      };
    }
  }
};

const classifyPercent = (
  value: number,
  warnAt: number,
  criticalAt: number,
): StatusMetricStatus => {
  if (value >= criticalAt) return 'critical';
  if (value >= warnAt) return 'warning';
  return 'ok';
};

const METRIC_TITLES: Record<string, string> = {
  cpu_status_tool: 'CPU 使用率',
  memory_status_tool: '内存使用率',
  disk_usage_tool: '磁盘使用率',
  service_status_tool: '服务状态',
  network_port_tool: '网络端口',
  process_list_tool: '进程数',
  process_detail_tool: '进程详情',
  system_info_tool: '系统信息',
  large_file_scan_tool: '大文件扫描',
  journal_log_tool: '系统日志',
};

const metricViews = computed<MetricView[]>(() => {
  const list = overview.value?.metrics ?? [];
  return list.map(buildMetricView);
});
</script>

<template>
  <div class="dashboard-page" data-testid="dashboard-page">
    <AppSectionHeader
      level="page"
      title="系统总览"
      subtitle="基于只读 OpsTool 实时采集，任意单条工具失败不影响整体刷新"
    >
      <template #actions>
        <el-button
          type="primary"
          :loading="inFlight"
          :disabled="inFlight"
          class="dashboard-refresh-button"
          data-testid="dashboard-refresh-button"
          @click="onRefreshClick"
        >
          {{ refreshButtonText }}
        </el-button>
      </template>
    </AppSectionHeader>

    <div
      v-if="!hasLoaded && !initialError"
      data-testid="dashboard-loading"
    >
      <AppLoadingState
        title="正在采集系统概览…"
        description="并行调用 10 个只读工具，单条失败不影响整体"
      />
    </div>

    <div
      v-if="initialError"
      data-testid="dashboard-initial-error"
    >
      <AppErrorState
        variant="transient"
        :title="`概览加载失败：${initialError}`"
        description="请检查后端服务状态后点击刷新重试。"
      >
        <template #action>
          <el-button
            size="small"
            type="primary"
            :disabled="inFlight"
            @click="onRefreshClick"
          >
            立即重试
          </el-button>
        </template>
      </AppErrorState>
    </div>

    <div
      v-if="staleError"
      data-testid="dashboard-stale-banner"
    >
      <AppStateBanner
        tone="warning"
        :title="`刷新失败：${staleError}`"
        description="当前展示的是上一次成功采集的数据。"
      />
    </div>

    <template v-if="overview">
      <section
        class="dashboard-summary"
        data-testid="dashboard-summary"
      >
        <el-card
          class="dashboard-summary-card"
          shadow="never"
          data-testid="dashboard-score-card"
        >
          <template #header>
            <div class="summary-header">
              <span class="summary-title">健康分</span>
              <el-tag
                :type="scoreTone"
                size="small"
                data-testid="dashboard-score-tone"
              >
                {{
                  scoreTone === 'success'
                    ? '良好'
                    : scoreTone === 'warning'
                      ? '注意'
                      : scoreTone === 'danger'
                        ? '异常'
                        : '数据不可用'
                }}
              </el-tag>
            </div>
          </template>
          <div class="summary-score-row">
            <span class="summary-score-number">{{ scoreText }}</span>
            <span class="summary-score-unit">/ 100</span>
          </div>
          <p class="summary-help">得分仅基于成功指标计算</p>
        </el-card>

        <el-card
          class="dashboard-summary-card"
          shadow="never"
          data-testid="dashboard-coverage-card"
        >
          <template #header>
            <div class="summary-header">
              <span class="summary-title">指标覆盖率</span>
              <el-tag
                v-if="degradedVisible"
                type="warning"
                size="small"
                data-testid="dashboard-degraded-tag"
              >
                部分降级
              </el-tag>
              <el-tag
                v-else
                type="success"
                size="small"
              >
                全部成功
              </el-tag>
            </div>
          </template>
          <div class="summary-score-row">
            <span class="summary-score-number">{{ coverageText }}</span>
            <span
              v-if="coveragePercent !== null"
              class="summary-score-unit"
            >
              ({{ coveragePercent }}%)
            </span>
          </div>
          <p class="summary-help">
            成功 {{ overview.successfulMetricCount }} / 共
            {{ overview.totalMetricCount }} 项
          </p>
        </el-card>

        <el-card
          class="dashboard-summary-card"
          shadow="never"
          data-testid="dashboard-collected-card"
        >
          <template #header>
            <div class="summary-header">
              <span class="summary-title">采集时间</span>
              <span
                class="summary-audit-id"
                data-testid="dashboard-audit-id"
              >审计 {{ auditIdText }}</span>
            </div>
          </template>
          <div
            class="summary-collected"
            data-testid="dashboard-collected-at"
          >
            {{ collectedAtText }}
          </div>
          <p class="summary-help">
            关联审计 ID 可在审计中心按 ID 检索
          </p>
        </el-card>
      </section>

      <section
        class="dashboard-metrics"
        data-testid="dashboard-metrics"
      >
        <AppSectionHeader
          level="section"
          title="核心指标"
          subtitle="每张卡片对应一次只读工具调用，失败项独立显示数据不可用"
        />
        <div class="dashboard-metrics-grid">
          <div
            v-for="view in metricViews"
            :key="view.key"
            class="dashboard-metric-cell"
            :data-testid="`dashboard-metric-cell-${view.toolName}`"
          >
            <StatusMetricCard
              :title="view.title"
              :value="view.value"
              :unit="view.unit"
              :threshold="view.threshold"
              :status="view.status"
            />
            <p
              v-if="view.errorReason"
              class="dashboard-metric-reason"
              :data-testid="`dashboard-metric-reason-${view.toolName}`"
            >
              {{ view.errorReason }}
            </p>
          </div>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.dashboard-page {
  display: flex;
  flex-direction: column;
  gap: var(--kg-space-4);
  max-width: 1280px;
  margin: 0 auto;
  width: 100%;
}

.dashboard-summary {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: var(--kg-space-3);
}

.dashboard-summary-card {
  height: 100%;
}

.summary-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--kg-space-2);
}

.summary-title {
  font-weight: 600;
  color: var(--kg-color-text-primary);
}

.summary-audit-id {
  font-family: var(--kg-font-mono);
  font-size: var(--kg-text-xs);
  color: var(--kg-color-text-mute);
  word-break: break-all;
}

.summary-score-row {
  display: flex;
  align-items: baseline;
  gap: var(--kg-space-1);
}

.summary-score-number {
  font-size: var(--kg-text-2xl);
  font-weight: 600;
  color: var(--kg-color-text-primary);
  line-height: var(--kg-line-tight);
}

.summary-score-unit {
  font-size: var(--kg-text-sm);
  color: var(--kg-color-text-mute);
}

.summary-collected {
  font-family: var(--kg-font-mono);
  font-size: var(--kg-text-md);
  color: var(--kg-color-text-primary);
  word-break: break-all;
}

.summary-help {
  margin: var(--kg-space-2) 0 0 0;
  font-size: var(--kg-text-xs);
  color: var(--kg-color-text-mute);
}

.dashboard-metrics {
  display: flex;
  flex-direction: column;
  gap: var(--kg-space-3);
}

.dashboard-metrics-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: var(--kg-space-3);
}

.dashboard-metric-cell {
  display: flex;
  flex-direction: column;
  gap: var(--kg-space-1);
}

.dashboard-metric-reason {
  margin: 0;
  padding: 0 var(--kg-space-1);
  font-size: var(--kg-text-xs);
  color: var(--kg-color-text-mute);
}
</style>
