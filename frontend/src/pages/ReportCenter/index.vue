<script setup lang="ts">
// ReportCenter — Phase 2 Task 13.
//
// Responsibilities (locked by 任务卡 §4 + 演示视频脚本 §3.5):
//   * List reports with pagination (mirrors GET /api/reports).
//   * Detail view: opens automatically when route.query.reportId is set,
//     so /reports?reportId=... (the link ChatConsole pushes after a
//     successful generateReport) lands directly on the detail.
//   * The detail body is Markdown. We render it with markdown-it in a
//     STRICTLY sanitized configuration (html:false, linkify:true,
//     breaks:true, typographer:true). We never pass Markdown through
//     v-html without that renderer gating it first, and we never enable
//     raw HTML passthrough — see the safety contract below.
//   * "查看源审计" link in the detail header points at /audit?auditId=...
//     so an operator can correlate the report with the persisted audit
//     record.
//   * Missing fields surface as "数据不可用" — the frontend NEVER
//     fabricates values that the backend did not return.
//   * Markdown render is wrapped in a try/catch — if the renderer ever
//     throws (malformed input), we fall back to showing the raw text
//     instead of blanking the page.
//
// SAFETY CONTRACT (XSS):
//   * markdown-it is configured with html:false. The renderer drops any
//     raw HTML in the body. <script>, <img onerror=...>, javascript: URLs,
//     and similar payloads are emitted as literal text or dropped entirely.
//   * The body is never placed under v-html without first going through
//     markdown-it.render(...), and markdown-it is locked at construction
//     (one instance per page lifetime) — never reconfigured at runtime.
//   * This posture is also consistent with the backend's
//     AuditSanitizer, which produces the body — but the frontend does
//     NOT trust the backend to have sanitized everything; it sanitizes
//     again at render time.

import { computed, onMounted, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import MarkdownIt from 'markdown-it';
import RiskLevelTag from '@/components/RiskLevelTag/index.vue';
import ReasoningChain from '@/components/ReasoningChain/index.vue';
import { rcaTitleFor } from '@/utils/intentType';
import { getReports, getReportDetail, type ReportListQuery } from '@/api/reports';
import { ApiError } from '@/api/client';
import {
  REPORT_TYPE_LABELS,
  type ReportDetail,
  type ReportPage,
  type ReportSummary,
  type ReportType,
} from '@/types/report';

// ---------------------------------------------------------------------------
// Renderer — locked at construction; html:false is non-negotiable.
// ---------------------------------------------------------------------------

const md = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true,
  typographer: true,
});

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------

const route = useRoute();

const DEFAULT_SIZE = 20;

const page = ref(0);
const size = ref(DEFAULT_SIZE);
const totalElements = ref(0);
const summaries = ref<ReportSummary[]>([]);
const loading = ref(false);
const listError = ref<string | null>(null);

const drawerVisible = ref(false);
const detailLoading = ref(false);
const detailError = ref<string | null>(null);
const detail = ref<ReportDetail | null>(null);
const bodyRenderError = ref(false);
const renderedBody = ref('');

const showEmpty = computed(
  () => !loading.value && !listError.value && summaries.value.length === 0,
);

// ---------------------------------------------------------------------------
// List fetching — only reads; never mutates the backend state.
// ---------------------------------------------------------------------------

const buildListQuery = (): ReportListQuery => {
  const q: ReportListQuery = { page: page.value, size: size.value };
  return q;
};

const fetchList = async () => {
  loading.value = true;
  listError.value = null;
  try {
    const result: ReportPage = await getReports(buildListQuery());
    summaries.value = result.content ?? [];
    totalElements.value = result.totalElements ?? 0;
  } catch (err) {
    const e = err as ApiError;
    listError.value = e?.message ?? '查询报告列表失败';
    summaries.value = [];
    totalElements.value = 0;
  } finally {
    loading.value = false;
  }
};

const onPageChange = (next: number) => {
  page.value = next;
  void fetchList();
};

const onSizeChange = (next: number) => {
  size.value = next;
  page.value = 0;
  void fetchList();
};

// ---------------------------------------------------------------------------
// Detail drawer — opens on row click or route.query.reportId.
// ---------------------------------------------------------------------------

const openDetail = async (reportId: string) => {
  drawerVisible.value = true;
  detailLoading.value = true;
  detailError.value = null;
  detail.value = null;
  bodyRenderError.value = false;
  renderedBody.value = '';
  try {
    const next = await getReportDetail(reportId);
    detail.value = next;
    // Render the Markdown body once, here, so the template's v-html
    // binding is a pure read of `renderedBody`. We never re-render on
    // every render tick — that would defeat the markdown-it safety
    // contract by making the side-effect chain harder to reason about.
    if (next?.bodyMarkdown) {
      try {
        renderedBody.value = md.render(next.bodyMarkdown);
      } catch {
        bodyRenderError.value = true;
        renderedBody.value = '';
      }
    }
  } catch (err) {
    const e = err as ApiError;
    detailError.value = e?.message ?? '查询报告详情失败';
  } finally {
    detailLoading.value = false;
  }
};

const closeDrawer = () => {
  drawerVisible.value = false;
  detail.value = null;
  detailError.value = null;
  bodyRenderError.value = false;
  renderedBody.value = '';
};

const onRowClick = (row: ReportSummary) => {
  if (!row?.reportId) return;
  void openDetail(row.reportId);
};

const onReportIdQuery = async (reportId: string | undefined) => {
  if (reportId) {
    await openDetail(reportId);
  }
};

watch(
  () => route.query.reportId,
  (next) => {
    if (typeof next === 'string' && next) {
      void onReportIdQuery(next);
    }
  },
);

onMounted(async () => {
  await fetchList();
  const initial = route.query.reportId;
  if (typeof initial === 'string' && initial) {
    void onReportIdQuery(initial);
  }
});

// ---------------------------------------------------------------------------
// Presentation helpers — Chinese labels, defensive Markdown rendering.
// ---------------------------------------------------------------------------

const reportTypeLabel = (t: ReportType | string | undefined): string => {
  if (!t) return '—';
  if (t in REPORT_TYPE_LABELS) {
    return REPORT_TYPE_LABELS[t as ReportType];
  }
  return String(t);
};

const formatTime = (ts: string | undefined): string => {
  if (!ts) return '';
  return ts.length > 19 ? ts.slice(0, 19).replace('T', ' ') : ts;
};

const sourceAuditHref = (auditId: string | undefined): string | undefined => {
  if (!auditId) return undefined;
  return `/audit?auditId=${encodeURIComponent(auditId)}`;
};

/**
 * Markdown body rendering happens inside openDetail (one shot per fetch).
 * The template binds v-html to `renderedBody`, which is a plain ref of
 * the sanitized HTML emitted by markdown-it. We do NOT recompute the
 * rendered HTML inside a computed — that would force a second render
 * pass every time the drawer's reactivity ticks, and would also make
 * the catch branch re-fire on every cycle.
 */
</script>

<template>
  <div class="report-page" data-testid="report-page">
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="page-header">
          <span class="page-title">报告中心</span>
          <span class="page-subtitle">
            每次安全闭环请求均可生成一份 Markdown 报告，可追溯到来源审计日志
          </span>
        </div>
      </template>

      <section class="report-list" data-testid="report-list">
        <p v-if="loading" class="report-loading" data-testid="report-loading">
          正在加载报告…
        </p>

        <el-alert
          v-else-if="listError"
          class="report-error"
          type="error"
          :closable="false"
          show-icon
          data-testid="report-error"
          :title="listError"
        />

        <p v-else-if="showEmpty" class="report-empty" data-testid="report-empty">
          暂无报告，可前往对话控制台生成。
        </p>

        <el-table
          v-else
          class="report-table"
          :data="summaries"
          :row-style="{ cursor: 'pointer' }"
          data-testid="report-table"
          @row-click="onRowClick"
        >
          <el-table-column label="报告标题" min-width="220">
            <template #default="{ row }">
              <span
                class="report-row-title"
                :data-testid="`report-row-${row.reportId}`"
              >
                {{ row.title || '—' }}
              </span>
            </template>
          </el-table-column>

          <el-table-column label="类型" width="120">
            <template #default="{ row }">
              <span
                class="report-row-type"
                :data-testid="`report-row-type-${row.reportId}`"
              >
                {{ reportTypeLabel(row.reportType) }}
              </span>
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

          <el-table-column label="会话 ID" width="160">
            <template #default="{ row }">
              <span class="report-row-session">{{ row.sessionId || '—' }}</span>
            </template>
          </el-table-column>

          <el-table-column label="审计 ID" width="200">
            <template #default="{ row }">
              <span
                v-if="row.auditId"
                class="report-row-audit"
                :data-testid="`report-row-audit-${row.reportId}`"
              >
                {{ row.auditId }}
              </span>
              <span v-else>—</span>
            </template>
          </el-table-column>

          <el-table-column label="创建时间" width="180">
            <template #default="{ row }">
              <span class="report-row-time">{{ formatTime(row.createdAt) || '—' }}</span>
            </template>
          </el-table-column>
        </el-table>

        <el-pagination
          v-if="!loading && !listError && totalElements > 0"
          class="report-pagination"
          :current-page="page + 1"
          :page-size="size"
          :page-sizes="[10, 20, 50, 100]"
          :total="totalElements"
          layout="total, sizes, prev, pager, next, jumper"
          data-testid="report-pagination"
          @current-change="(p: number) => onPageChange(p - 1)"
          @size-change="onSizeChange"
        />
      </section>
    </el-card>

    <el-drawer
      v-model="drawerVisible"
      direction="rtl"
      size="720px"
      :with-header="false"
      :destroy-on-close="false"
      data-testid="report-detail-drawer"
      @close="closeDrawer"
    >
      <section
        v-if="detail"
        class="report-detail"
        data-testid="report-detail"
      >
        <header class="report-detail-header">
          <span
            class="report-detail-title"
            data-testid="report-detail-title"
          >
            {{ detail.title || '数据不可用' }}
          </span>
          <code class="report-detail-reportid">{{ detail.reportId }}</code>
        </header>

        <div class="report-detail-meta">
          <span class="report-detail-meta-item">
            类型：{{ reportTypeLabel(detail.reportType) }}
          </span>
          <RiskLevelTag
            v-if="detail.riskLevel"
            :level="detail.riskLevel"
            :decision="detail.riskDecision"
          />
          <span class="report-detail-meta-item">
            创建：{{ formatTime(detail.createdAt) || '数据不可用' }}
          </span>
        </div>

        <p class="report-detail-source">
          来源审计：
          <router-link
            v-if="detail.auditId"
            :to="sourceAuditHref(detail.auditId)!"
            class="report-detail-source-link"
            data-testid="report-detail-source-audit-link"
          >
            查看源审计 ({{ detail.auditId }})
          </router-link>
          <span v-else>数据不可用</span>
        </p>

        <article
          v-if="detail.bodyMarkdown && !bodyRenderError"
          class="report-detail-body markdown-body"
          data-testid="report-detail-body"
          v-html="renderedBody"
        />

        <ReasoningChain
          v-if="detail.rootCauseChain"
          :chain="detail.rootCauseChain"
          :title="rcaTitleFor(detail.reportType) ?? '推理链'"
          :data-testid="`report-rca-${detail.reportId}`"
        />

        <pre
          v-else-if="detail.bodyMarkdown && bodyRenderError"
          class="report-detail-body-fallback"
          data-testid="report-detail-body-fallback"
        >{{ detail.bodyMarkdown }}</pre>

        <p
          v-else
          class="report-detail-body-empty"
          data-testid="report-detail-body-empty"
        >
          报告内容数据不可用
        </p>
      </section>

      <p
        v-else-if="detailLoading"
        class="report-loading"
        data-testid="report-detail-loading"
      >
        正在加载报告详情…
      </p>

      <el-alert
        v-else-if="detailError"
        class="report-error"
        type="error"
        :closable="false"
        show-icon
        data-testid="report-detail-error"
        :title="detailError"
      />
    </el-drawer>
  </div>
</template>

<style scoped>
.report-page {
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

.report-list {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.report-loading {
  margin: 1rem 0;
  color: #909399;
  text-align: center;
}

.report-empty {
  margin: 1rem 0;
  padding: 1.5rem;
  text-align: center;
  color: #909399;
  background: #f5f7fa;
  border-radius: 6px;
}

.report-error {
  margin: 0.5rem 0;
}

.report-table {
  cursor: pointer;
}

.report-row-title {
  color: #303133;
  font-weight: 500;
}

.report-row-type,
.report-row-session,
.report-row-audit,
.report-row-time {
  font-size: 0.85rem;
  color: #606266;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  word-break: break-all;
}

.report-row-time {
  font-family: inherit;
}

.report-pagination {
  margin-top: 1rem;
  justify-content: flex-end;
}

.report-detail {
  padding: 1rem;
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.report-detail-header {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.report-detail-title {
  font-weight: 600;
  font-size: 1.1rem;
  color: #1f2d3d;
}

.report-detail-reportid {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 0.75rem;
  color: #909399;
  word-break: break-all;
}

.report-detail-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  align-items: center;
  font-size: 0.85rem;
  color: #606266;
}

.report-detail-meta-item {
  font-size: 0.85rem;
  color: #606266;
}

.report-detail-source {
  margin: 0;
  font-size: 0.85rem;
  color: #303133;
}

.report-detail-source-link {
  color: #409eff;
  text-decoration: none;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}

.report-detail-body {
  padding: 0.75rem 1rem;
  background: #fafafa;
  border-radius: 6px;
  border: 1px solid #ebeef5;
  color: #303133;
  font-size: 0.9rem;
  line-height: 1.65;
  word-break: break-word;
}

.report-detail-body :deep(h1),
.report-detail-body :deep(h2),
.report-detail-body :deep(h3),
.report-detail-body :deep(h4) {
  margin: 0.75rem 0 0.5rem 0;
  color: #1f2d3d;
  font-weight: 600;
  line-height: 1.4;
}

.report-detail-body :deep(h1) {
  font-size: 1.15rem;
}
.report-detail-body :deep(h2) {
  font-size: 1.05rem;
}
.report-detail-body :deep(h3) {
  font-size: 1rem;
}
.report-detail-body :deep(h4) {
  font-size: 0.95rem;
}

.report-detail-body :deep(p) {
  margin: 0.5rem 0;
}

.report-detail-body :deep(ul),
.report-detail-body :deep(ol) {
  margin: 0.5rem 0 0.5rem 1.5rem;
  padding: 0;
}

.report-detail-body :deep(li) {
  margin: 0.25rem 0;
}

.report-detail-body :deep(code) {
  background: #f0f2f5;
  padding: 0.1rem 0.35rem;
  border-radius: 3px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 0.85em;
}

.report-detail-body :deep(pre) {
  background: #1f2d3d;
  color: #f5f7fa;
  padding: 0.75rem;
  border-radius: 4px;
  overflow-x: auto;
  font-size: 0.85rem;
}

.report-detail-body :deep(pre code) {
  background: transparent;
  padding: 0;
  color: inherit;
}

.report-detail-body :deep(blockquote) {
  margin: 0.5rem 0;
  padding: 0.25rem 0.75rem;
  border-left: 3px solid #dcdfe6;
  color: #606266;
  background: #f5f7fa;
}

.report-detail-body :deep(a) {
  color: #409eff;
  text-decoration: none;
  word-break: break-all;
}

.report-detail-body-fallback {
  margin: 0;
  padding: 0.75rem 1rem;
  background: #fdf6ec;
  border-radius: 6px;
  border: 1px solid #faecd8;
  color: #303133;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 0.85rem;
  white-space: pre-wrap;
  word-break: break-word;
}

.report-detail-body-empty {
  margin: 0;
  padding: 1rem;
  text-align: center;
  color: #909399;
  background: #f5f7fa;
  border-radius: 6px;
  font-style: italic;
}
</style>