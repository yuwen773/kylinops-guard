<script setup lang="ts">
// AuditLog — Phase 2 Task 6 / Task 16.
//
// Responsibilities (locked by 任务卡 §4 + 演示视频脚本 §3.4):
//   * List audit logs with pagination and composite filters (risk / status /
//     keyword / date range). Filters map 1:1 to GET /api/audit/logs query
//     parameters.
//   * Clicking a row opens a detail drawer showing the full request lifecycle
//     (AuditTimeline) plus tool failures, risk checks, pending action,
//     execution result, and final answer.
//   * Mount with route.query.auditId=... opens the drawer automatically so
//     /audit?auditId=... links from ChatConsole land directly on the detail.
//   * JSON-like fields (matchedRules, actionPlan, executionResult) are parsed
//     defensively. On parse failure we render the raw sanitized string instead
//     of crashing.
//   * Loading / error / empty states are explicit. v-html is NEVER used for
//     unsanitized content; everything goes through text interpolation or
//     trusted per-key rendering.
//
// Safety contract:
//   * No risk evaluation happens here. The backend has already produced the
//     final persisted verdict; we display it verbatim.
//   * No shell / command / target is forwarded. The page is read-only.

import { computed, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { getAuditLogs, getAuditDetail, type AuditLogQuery } from '@/api/audit';
import { ApiError } from '@/api/client';
import { generateReport } from '@/api/reports';
import RiskLevelTag from '@/components/RiskLevelTag/index.vue';
import AuditTimeline, {
  type AuditTimelineEntry,
} from '@/components/AuditTimeline/index.vue';
import type {
  AuditLogDetail,
  AuditLogPage,
  AuditLogSummary,
  AuditPendingActionInfo,
  AuditRiskCheckInfo,
  AuditToolCallInfo,
} from '@/types/audit';
import type { RiskDecision, RiskLevel } from '@/types/safety';

const route = useRoute();
const router = useRouter();

const DEFAULT_SIZE = 20;

const page = ref(0);
const size = ref(DEFAULT_SIZE);
const totalElements = ref(0);
const summaries = ref<AuditLogSummary[]>([]);
const loading = ref(false);
const listError = ref<string | null>(null);

const riskFilter = ref<RiskLevel | ''>('');
const statusFilter = ref<string>('');
const keywordFilter = ref<string>('');
const startTimeFilter = ref<string>('');
const endTimeFilter = ref<string>('');

const drawerVisible = ref(false);
const detailLoading = ref(false);
const detailError = ref<string | null>(null);
const detail = ref<AuditLogDetail | null>(null);
const generatingReport = ref(false);
const generateReportError = ref<string | null>(null);

// Generate a report from the current audit detail and jump to /reports.
// The whole flow is one-shot: success → navigate to the new report detail;
// failure → inline error (no retry, no mutation of the audit).
const handleGenerateReport = async () => {
  if (!detail.value?.auditId || generatingReport.value) return;
  generatingReport.value = true;
  generateReportError.value = null;
  try {
    const report = await generateReport({ auditId: detail.value.auditId });
    if (report?.reportId) {
      router.push({ path: '/reports', query: { reportId: report.reportId } });
    } else {
      router.push('/reports');
    }
  } catch (e) {
    generateReportError.value =
      e instanceof ApiError ? e.message : (e as Error)?.message ?? '生成报告失败';
  } finally {
    generatingReport.value = false;
  }
};

const hasAnyFilter = computed(
  () =>
    !!riskFilter.value ||
    !!statusFilter.value ||
    !!keywordFilter.value.trim() ||
    !!startTimeFilter.value ||
    !!endTimeFilter.value,
);

// ---------------------------------------------------------------------------
// List fetching — only reads; never mutates the backend state.
// ---------------------------------------------------------------------------

const buildQuery = (): AuditLogQuery => {
  const q: AuditLogQuery = { page: page.value, size: size.value };
  if (riskFilter.value) q.riskLevel = riskFilter.value;
  if (statusFilter.value) q.status = statusFilter.value;
  const kw = keywordFilter.value.trim();
  if (kw) q.keyword = kw;
  if (startTimeFilter.value) q.startTime = startTimeFilter.value;
  if (endTimeFilter.value) q.endTime = endTimeFilter.value;
  return q;
};

const fetchList = async () => {
  loading.value = true;
  listError.value = null;
  try {
    const result: AuditLogPage = await getAuditLogs(buildQuery());
    summaries.value = result.content ?? [];
    totalElements.value = result.totalElements ?? 0;
  } catch (err) {
    const e = err as ApiError;
    listError.value = e?.message ?? '查询审计日志失败';
    summaries.value = [];
    totalElements.value = 0;
  } finally {
    loading.value = false;
  }
};

// ---------------------------------------------------------------------------
// Filter / pagination handlers — re-fetch with current state.
// Resetting to page 0 on filter change keeps the user anchored to the top
// of the result set rather than stranded on an empty page.
// ---------------------------------------------------------------------------

const onPageChange = (next: number) => {
  page.value = next;
  void fetchList();
};

const onSizeChange = (next: number) => {
  size.value = next;
  page.value = 0;
  void fetchList();
};

const onFilterChange = () => {
  page.value = 0;
  void fetchList();
};

const onResetFilters = () => {
  riskFilter.value = '';
  statusFilter.value = '';
  keywordFilter.value = '';
  startTimeFilter.value = '';
  endTimeFilter.value = '';
  page.value = 0;
  void fetchList();
};

// ---------------------------------------------------------------------------
// Detail drawer — opens on row click or route.query.auditId.
// ---------------------------------------------------------------------------

const openDetail = async (auditId: string) => {
  drawerVisible.value = true;
  detailLoading.value = true;
  detailError.value = null;
  detail.value = null;
  try {
    detail.value = await getAuditDetail(auditId);
  } catch (err) {
    const e = err as ApiError;
    detailError.value = e?.message ?? '查询审计详情失败';
  } finally {
    detailLoading.value = false;
  }
};

const closeDrawer = () => {
  drawerVisible.value = false;
  detail.value = null;
  detailError.value = null;
};

const onRowClick = (auditId: string | undefined) => {
  if (!auditId) return;
  void openDetail(auditId);
};

// React to route.query.auditId — both on mount and on subsequent changes.
const onAuditIdQuery = async (auditId: string | undefined) => {
  if (auditId) {
    await openDetail(auditId);
  }
};

watch(
  () => route.query.auditId,
  (next) => {
    if (typeof next === 'string' && next) {
      void onAuditIdQuery(next);
    }
  },
);

onMounted(async () => {
  await fetchList();
  const initialAuditId = route.query.auditId;
  if (typeof initialAuditId === 'string' && initialAuditId) {
    void onAuditIdQuery(initialAuditId);
  }
});

// ---------------------------------------------------------------------------
// Presentation helpers — Chinese labels, defensive JSON parsing.
// ---------------------------------------------------------------------------

const STATUS_OPTIONS: ReadonlyArray<{ value: string; label: string }> = [
  { value: 'RECEIVED', label: '已接收' },
  { value: 'RISK_CHECKED', label: '已校验' },
  { value: 'CONFIRM_PENDING', label: '待确认' },
  { value: 'CONFIRMED', label: '已确认' },
  { value: 'CANCELLED', label: '已取消' },
  { value: 'SUCCESS', label: '执行成功' },
  { value: 'BLOCKED', label: '已阻断' },
  { value: 'FAILED', label: '执行失败' },
];

const RISK_OPTIONS: ReadonlyArray<{ value: RiskLevel; label: string }> = [
  { value: 'L0', label: 'L0 信息查询' },
  { value: 'L1', label: 'L1 轻度风险' },
  { value: 'L2', label: 'L2 需确认' },
  { value: 'L3', label: 'L3 高风险' },
  { value: 'L4', label: 'L4 严重风险' },
];

const statusLabel = (status: string | undefined): string => {
  if (!status) return '—';
  const found = STATUS_OPTIONS.find((o) => o.value === status);
  return found?.label ?? status;
};

const confirmationLabel = (status: string | undefined): string => {
  if (!status) return '';
  switch (status) {
    case 'WAITING':
      return '等待用户确认';
    case 'CONFIRMED':
      return '已确认';
    case 'CANCELLED':
      return '已取消';
    case 'SUCCESS':
      return '已执行';
    case 'FAILED':
      return '执行失败';
    default:
      return status;
  }
};

const toolStatusLabel = (status: string | undefined): string => {
  if (!status) return '';
  switch (status) {
    case 'PENDING':
      return '待执行';
    case 'RUNNING':
      return '执行中';
    case 'SUCCESS':
      return '执行成功';
    case 'FAILED':
      return '执行失败';
    case 'TIMEOUT':
      return '执行超时';
    case 'BLOCKED':
      return '已阻断';
    default:
      return status;
  }
};

/**
 * Defensive JSON parse. Returns:
 *   - { kind: 'json', value: unknown } if value parses cleanly
 *   - { kind: 'raw', value: string } otherwise
 *
 * NEVER throws; the page always has something safe to render.
 */
type ParsedField =
  | { kind: 'json'; value: unknown }
  | { kind: 'raw'; value: string };

const parseJsonLike = (raw: string | undefined | null): ParsedField => {
  if (raw === null || raw === undefined || raw === '') {
    return { kind: 'raw', value: '' };
  }
  const trimmed = raw.trim();
  // Only attempt JSON.parse if the payload looks like a JSON object/array.
  // This avoids throwing on free-text output like "ok".
  if (
    !(trimmed.startsWith('[') && trimmed.endsWith(']')) &&
    !(trimmed.startsWith('{') && trimmed.endsWith('}'))
  ) {
    return { kind: 'raw', value: raw };
  }
  try {
    return { kind: 'json', value: JSON.parse(trimmed) };
  } catch {
    return { kind: 'raw', value: raw };
  }
};

const formatJsonNode = (node: unknown): string => {
  if (node === null || node === undefined) return '';
  if (typeof node === 'string') return node;
  if (typeof node === 'number' || typeof node === 'boolean') return String(node);
  if (Array.isArray(node)) {
    return node.map((v) => formatJsonNode(v)).join('、');
  }
  if (typeof node === 'object') {
    return Object.entries(node as Record<string, unknown>)
      .map(([k, v]) => `${k}: ${formatJsonNode(v)}`)
      .join('；');
  }
  return String(node);
};

// ---------------------------------------------------------------------------
// Derived view-model for the detail drawer.
// ---------------------------------------------------------------------------

const timelineEntries = computed<AuditTimelineEntry[]>(() => {
  const d = detail.value;
  if (!d) return [];
  const entries: AuditTimelineEntry[] = [];

  const firstRisk = (d.riskChecks ?? [])
    .slice()
    .sort((a, b) =>
      (a.checkedAt ?? '').localeCompare(b.checkedAt ?? ''),
    )[0];
  if (firstRisk || d.riskLevel) {
    entries.push({
      stage: 'risk-check',
      timestamp: firstRisk?.checkedAt ?? d.createdAt ?? '',
      summary: firstRisk?.reason
        ? `风险校验：${firstRisk.reason}`
        : `风险校验：${d.riskLevel ?? ''} ${d.riskDecision ?? ''}`.trim(),
    });
  }

  if ((d.toolCalls ?? []).length > 0) {
    const tools = (d.toolCalls ?? []).map((t) => t.toolName).filter(Boolean);
    entries.push({
      stage: 'tool-call',
      timestamp: d.createdAt ?? '',
      summary: tools.length
        ? `调用 ${tools.length} 个工具：${tools.join('、')}`
        : '调用工具',
    });
  }

  if (d.confirmationRequired || d.pendingAction) {
    entries.push({
      stage: 'confirmation',
      timestamp: d.updatedAt ?? d.createdAt ?? '',
      summary: `用户确认：${confirmationLabel(d.confirmationStatus)}`,
    });
  }

  if (d.pendingAction?.executionResult) {
    entries.push({
      stage: 'execution',
      timestamp: d.updatedAt ?? d.createdAt ?? '',
      summary: 'SafeExecutor 已执行待确认动作',
    });
  }

  if (d.finalAnswer) {
    entries.push({
      stage: 'answer',
      timestamp: d.updatedAt ?? d.createdAt ?? '',
      summary: d.finalAnswer,
    });
  }

  return entries;
});

const failedTools = computed<AuditToolCallInfo[]>(() => {
  return (detail.value?.toolCalls ?? []).filter((t) =>
    ['FAILED', 'TIMEOUT', 'BLOCKED'].includes(t.status ?? ''),
  );
});

const riskChecksView = computed<AuditRiskCheckInfo[]>(
  () => detail.value?.riskChecks ?? [],
);

const pendingActionView = computed<AuditPendingActionInfo | null>(
  () => detail.value?.pendingAction ?? null,
);

const matchedRulesView = computed<ParsedField>(() =>
  parseJsonLike(detail.value?.matchedRules),
);
const executionResultView = computed<ParsedField>(() =>
  parseJsonLike(detail.value?.executionResult),
);
const pendingExecutionView = computed<ParsedField>(() =>
  parseJsonLike(pendingActionView.value?.executionResult),
);

const showEmpty = computed(
  () => !loading.value && !listError.value && summaries.value.length === 0,
);

const decisionForTag = (level: RiskLevel | undefined): RiskDecision | undefined =>
  detail.value?.riskDecision;
</script>

<template>
  <div class="audit-page" data-testid="audit-page">
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="page-header">
          <span class="page-title">审计日志</span>
          <span class="page-subtitle">
            全部请求均在此处留痕，可按风险等级 / 状态 / 关键词 / 时间筛选
          </span>
        </div>
      </template>

      <!-- Filters -->
      <section class="audit-filters" data-testid="audit-filters">
        <div class="audit-filter-item">
          <label class="audit-filter-label">风险等级</label>
          <el-select
            v-model="riskFilter"
            placeholder="全部"
            clearable
            data-testid="audit-filter-risk"
            @change="onFilterChange"
            @clear="onFilterChange"
          >
            <el-option
              v-for="opt in RISK_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
              :data-testid="`audit-filter-risk-option-${opt.value}`"
            />
          </el-select>
        </div>

        <div class="audit-filter-item">
          <label class="audit-filter-label">状态</label>
          <el-select
            v-model="statusFilter"
            placeholder="全部"
            clearable
            data-testid="audit-filter-status"
            @change="onFilterChange"
            @clear="onFilterChange"
          >
            <el-option
              v-for="opt in STATUS_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </div>

        <div class="audit-filter-item audit-filter-item-grow">
          <label class="audit-filter-label">关键词</label>
          <el-input
            v-model="keywordFilter"
            placeholder="匹配用户输入"
            clearable
            data-testid="audit-filter-keyword"
            @keydown.enter.prevent="onFilterChange"
            @clear="onFilterChange"
          />
        </div>

        <div class="audit-filter-item">
          <label class="audit-filter-label">开始时间</label>
          <el-input
            v-model="startTimeFilter"
            placeholder="ISO 时间，例如 2026-06-12T00:00:00"
            clearable
            data-testid="audit-filter-start"
            @change="onFilterChange"
          />
        </div>

        <div class="audit-filter-item">
          <label class="audit-filter-label">结束时间</label>
          <el-input
            v-model="endTimeFilter"
            placeholder="ISO 时间，例如 2026-06-12T23:59:59"
            clearable
            data-testid="audit-filter-end"
            @change="onFilterChange"
          />
        </div>

        <div class="audit-filter-actions">
          <el-button
            type="primary"
            plain
            data-testid="audit-filter-apply"
            @click="onFilterChange"
          >
            查询
          </el-button>
          <el-button
            :disabled="!hasAnyFilter"
            data-testid="audit-filter-reset"
            @click="onResetFilters"
          >
            重置
          </el-button>
        </div>
      </section>

      <!-- List -->
      <section class="audit-list" data-testid="audit-list">
        <p
          v-if="loading"
          class="audit-loading"
          data-testid="audit-loading"
        >
          正在加载审计日志…
        </p>

        <el-alert
          v-else-if="listError"
          class="audit-error"
          type="error"
          :closable="false"
          show-icon
          data-testid="audit-error"
          :title="listError"
        />

        <p
          v-else-if="showEmpty"
          class="audit-empty"
          data-testid="audit-empty"
        >
          暂无审计记录
        </p>

        <el-table
          v-else
          class="audit-table"
          :data="summaries"
          :row-style="{ cursor: 'pointer' }"
          data-testid="audit-table"
          @row-click="(row: AuditLogSummary) => onRowClick(row.auditId)"
        >
          <el-table-column prop="userInput" label="用户输入" min-width="220">
            <template #default="{ row }">
              <span
                class="audit-row-input"
                :data-testid="`audit-row-${row.auditId}`"
              >{{ row.userInput || '—' }}</span>
            </template>
          </el-table-column>

          <el-table-column label="意图" width="160">
            <template #default="{ row }">
              <span class="audit-row-intent">{{ row.intentType || '—' }}</span>
            </template>
          </el-table-column>

          <el-table-column label="风险" width="180">
            <template #default="{ row }">
              <RiskLevelTag
                v-if="row.riskLevel"
                :level="row.riskLevel"
                :decision="row.riskDecision"
              />
              <span v-else>—</span>
            </template>
          </el-table-column>

          <el-table-column label="状态" width="120">
            <template #default="{ row }">
              {{ statusLabel(row.status) }}
            </template>
          </el-table-column>

          <el-table-column label="工具数" width="80">
            <template #default="{ row }">
              <span
                class="audit-row-toolcount"
                :data-testid="`audit-row-toolcount-${row.auditId}`"
              >
                {{ row.toolCallCount ?? 0 }}
              </span>
            </template>
          </el-table-column>

          <el-table-column label="时间" width="180">
            <template #default="{ row }">
              <span class="audit-row-time">{{ (row.createdAt ?? '').replace('T', ' ').slice(0, 19) }}</span>
            </template>
          </el-table-column>
        </el-table>

        <!-- Pagination -->
        <el-pagination
          v-if="!loading && !listError && totalElements > 0"
          class="audit-pagination"
          :current-page="page + 1"
          :page-size="size"
          :page-sizes="[10, 20, 50, 100]"
          :total="totalElements"
          layout="total, sizes, prev, pager, next, jumper"
          data-testid="audit-pagination"
          @current-change="(p: number) => onPageChange(p - 1)"
          @size-change="onSizeChange"
        />
      </section>
    </el-card>

    <!-- Detail drawer -->
    <el-drawer
      v-model="drawerVisible"
      direction="rtl"
      size="640px"
      :with-header="false"
      :destroy-on-close="false"
      data-testid="audit-detail-drawer"
      @close="closeDrawer"
    >
      <section
        v-if="detail"
        class="audit-detail"
        data-testid="audit-detail"
      >
        <header class="audit-detail-header">
          <span class="audit-detail-title">审计详情</span>
          <code class="audit-detail-auditid">{{ detail.auditId }}</code>
          <div class="audit-detail-actions">
            <router-link
              to="/security"
              class="audit-detail-nav-link"
              data-testid="audit-detail-security-link"
            >
              查看安全中心
            </router-link>
            <el-button
              type="primary"
              size="small"
              :loading="generatingReport"
              :disabled="generatingReport"
              data-testid="audit-detail-generate-report"
              @click="handleGenerateReport"
            >
              生成报告
            </el-button>
          </div>
        </header>

        <el-alert
          v-if="generateReportError"
          class="audit-detail-generate-error"
          type="error"
          :closable="false"
          show-icon
          :title="generateReportError"
        />

        <div class="audit-detail-meta">
          <RiskLevelTag
            v-if="detail.riskLevel"
            :level="detail.riskLevel"
            :decision="decisionForTag(detail.riskLevel)"
          />
          <span class="audit-detail-status">
            状态：{{ statusLabel(detail.status) }}
          </span>
          <span
            v-if="detail.confirmationRequired"
            class="audit-detail-confirm"
          >
            确认状态：{{ confirmationLabel(detail.confirmationStatus) }}
          </span>
        </div>

        <p v-if="detail.userInput" class="audit-detail-userinput">
          {{ detail.userInput }}
        </p>

        <AuditTimeline :entries="timelineEntries" />

        <!-- Tool failures -->
        <el-card
          v-if="failedTools.length > 0"
          class="audit-section"
          shadow="never"
          data-testid="audit-tool-failure"
        >
          <template #header>
            <span>工具调用失败</span>
          </template>
          <ul class="audit-failure-list">
            <li
              v-for="tool in failedTools"
              :key="tool.toolCallId ?? tool.toolName"
              class="audit-failure-item"
            >
              <span class="audit-failure-name">{{ tool.toolName }}</span>
              <span class="audit-failure-status">
                {{ toolStatusLabel(tool.status) }}
              </span>
              <span v-if="tool.errorMessage" class="audit-failure-reason">
                {{ tool.errorMessage }}
              </span>
            </li>
          </ul>
        </el-card>

        <!-- Risk checks -->
        <el-card
          v-if="riskChecksView.length > 0"
          class="audit-section"
          shadow="never"
          data-testid="audit-risk-check"
        >
          <template #header>
            <span>风险校验</span>
          </template>
          <ul class="audit-riskcheck-list">
            <li
              v-for="rc in riskChecksView"
              :key="rc.riskCheckId ?? rc.checkedAt"
              class="audit-riskcheck-item"
            >
              <span class="audit-riskcheck-level">
                {{ rc.riskLevel }} · {{ rc.riskDecision }}
              </span>
              <span v-if="rc.reason" class="audit-riskcheck-reason">
                {{ rc.reason }}
              </span>
            </li>
          </ul>
        </el-card>

        <!-- Pending action -->
        <el-card
          v-if="pendingActionView"
          class="audit-section"
          shadow="never"
          data-testid="audit-pending-action"
        >
          <template #header>
            <span>待确认动作</span>
          </template>
          <p class="audit-pending-line">
            <span>动作 ID：</span>
            <code>{{ pendingActionView.actionId }}</code>
          </p>
          <p v-if="pendingActionView.actionType" class="audit-pending-line">
            类型：{{ pendingActionView.actionType }}
          </p>
          <p v-if="pendingActionView.toolName" class="audit-pending-line">
            工具：{{ pendingActionView.toolName }}
          </p>
          <p v-if="pendingActionView.status" class="audit-pending-line">
            状态：{{ toolStatusLabel(pendingActionView.status) }}
          </p>
          <p
            v-if="pendingExecutionView.kind === 'json'"
            class="audit-pending-execution"
            data-testid="audit-pending-execution"
          >
            执行结果：{{ formatJsonNode(pendingExecutionView.value) }}
          </p>
          <p
            v-else-if="pendingExecutionView.value"
            class="audit-pending-execution"
            data-testid="audit-pending-execution"
          >
            执行结果：{{ pendingExecutionView.value }}
          </p>
        </el-card>

        <!-- Execution result -->
        <el-card
          v-if="executionResultView.value"
          class="audit-section"
          shadow="never"
          data-testid="audit-execution-result"
        >
          <template #header>
            <span>执行结果</span>
          </template>
          <p v-if="executionResultView.kind === 'json'">
            {{ formatJsonNode(executionResultView.value) }}
          </p>
          <p v-else>{{ executionResultView.value }}</p>
        </el-card>

        <!-- Matched rules (JSON) -->
        <el-card
          v-if="matchedRulesView.value"
          class="audit-section"
          shadow="never"
          data-testid="audit-matched-rules"
        >
          <template #header>
            <span>匹配规则</span>
          </template>
          <p v-if="matchedRulesView.kind === 'json'">
            {{ formatJsonNode(matchedRulesView.value) }}
          </p>
          <p v-else>{{ matchedRulesView.value }}</p>
        </el-card>

        <!-- Final answer -->
        <el-card
          v-if="detail.finalAnswer"
          class="audit-section"
          shadow="never"
          data-testid="audit-final-answer"
        >
          <template #header>
            <span>最终回复</span>
          </template>
          <p class="audit-final-answer">{{ detail.finalAnswer }}</p>
        </el-card>

        <!-- Warning -->
        <el-alert
          v-if="detail.warning"
          class="audit-warning"
          type="warning"
          :closable="false"
          show-icon
          :title="detail.warning"
        />
      </section>

      <p
        v-else-if="detailLoading"
        class="audit-loading"
        data-testid="audit-detail-loading"
      >
        正在加载审计详情…
      </p>

      <el-alert
        v-else-if="detailError"
        class="audit-error"
        type="error"
        :closable="false"
        show-icon
        data-testid="audit-detail-error"
        :title="detailError"
      />
    </el-drawer>
  </div>
</template>

<style scoped>
.audit-page {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.page-card {
  max-width: 1280px;
  margin: 0 auto;
  width: 100%;
}

.page-header {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.page-title {
  font-weight: 600;
}

.page-subtitle {
  font-size: 0.8rem;
  color: #909399;
}

.audit-filters {
  display: flex;
  flex-wrap: wrap;
  gap: 0.75rem;
  align-items: flex-end;
  margin-bottom: 1rem;
}

.audit-filter-item {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  min-width: 160px;
}

.audit-filter-item-grow {
  flex: 1 1 220px;
}

.audit-filter-label {
  font-size: 0.75rem;
  color: #606266;
}

.audit-filter-actions {
  display: flex;
  gap: 0.5rem;
}

.audit-list {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.audit-loading {
  margin: 1rem 0;
  color: #909399;
  text-align: center;
}

.audit-empty {
  margin: 1rem 0;
  padding: 1.5rem;
  text-align: center;
  color: #909399;
  background: #f5f7fa;
  border-radius: 6px;
}

.audit-error {
  margin: 0.5rem 0;
}

.audit-table {
  cursor: pointer;
}

.audit-row-input {
  color: #303133;
}

.audit-row-intent,
.audit-row-time {
  font-size: 0.85rem;
  color: #606266;
}

.audit-row-toolcount {
  font-weight: 600;
}

.audit-pagination {
  margin-top: 1rem;
  justify-content: flex-end;
}

.audit-pagination-next-btn {
  background: transparent;
  border: 0;
  color: #409eff;
  cursor: pointer;
}

.audit-detail {
  padding: 1rem;
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.audit-detail-header {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.audit-detail-title {
  font-weight: 600;
  font-size: 1.1rem;
}

.audit-detail-auditid {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 0.75rem;
  color: #909399;
  word-break: break-all;
}

.audit-detail-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.75rem;
  align-items: center;
  margin-top: 0.25rem;
}

.audit-detail-nav-link {
  color: var(--el-color-primary);
  text-decoration: none;
  font-size: 0.875rem;
}

.audit-detail-nav-link:hover {
  text-decoration: underline;
}

.audit-detail-generate-error {
  margin-top: 0.5rem;
}

.audit-detail-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  align-items: center;
}

.audit-detail-status,
.audit-detail-confirm {
  font-size: 0.85rem;
  color: #606266;
}

.audit-detail-userinput {
  margin: 0;
  padding: 0.5rem 0.75rem;
  background: #f5f7fa;
  border-radius: 4px;
  color: #303133;
  white-space: pre-wrap;
  word-break: break-word;
}

.audit-section {
  margin-bottom: 0.5rem;
}

.audit-failure-list,
.audit-riskcheck-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.audit-failure-item,
.audit-riskcheck-item {
  padding: 0.5rem 0.75rem;
  background: #fdf6ec;
  border-radius: 4px;
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  align-items: baseline;
}

.audit-failure-name {
  font-weight: 600;
  color: #c45656;
}

.audit-failure-status {
  font-size: 0.85rem;
  color: #b88230;
}

.audit-failure-reason,
.audit-riskcheck-reason {
  color: #303133;
  font-size: 0.85rem;
}

.audit-riskcheck-level {
  font-weight: 600;
  color: #1f2d3d;
}

.audit-pending-line {
  margin: 0.25rem 0;
  color: #303133;
}

.audit-pending-execution {
  margin: 0.25rem 0 0 0;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 0.85rem;
  color: #303133;
  word-break: break-all;
  white-space: pre-wrap;
}

.audit-final-answer {
  margin: 0;
  color: #303133;
  white-space: pre-wrap;
  word-break: break-word;
}

.audit-warning {
  margin-top: 0.5rem;
}
</style>