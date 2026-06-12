<script setup lang="ts">
// ReportPreview — renders a structured report payload from the backend.
//
// SAFETY CONTRACT — XSS:
//   The bodyMarkdown field MAY contain user-influenced content (the report
//   references tool outputs and audit summaries). We MUST NOT use v-html or
//   markdown-it with html:true. Instead, the body is split into paragraphs
//   on blank lines and rendered with v-text, which the browser treats as
//   plain text. Any <script> / <img onerror> / etc. is shown as literal
//   characters, not executed.
//
// This is the only correct posture for a security-controlled product:
// the report is data, not code.
import { computed } from 'vue';

export interface ReportPreviewProps {
  reportId: string;
  title: string;
  /** Backend-formatted markdown body. Treated as plain text — see safety contract. */
  bodyMarkdown: string;
  /** Optional audit-log correlation id. */
  auditId?: string;
  /** ISO-8601 or epoch-millis timestamp string from the backend. */
  createdAt: string;
}

const props = defineProps<ReportPreviewProps>();

/**
 * Split the body on blank lines. Each chunk is rendered with v-text, so
 * no HTML is ever parsed. Markdown syntax (e.g. "#", "*") is shown as-is.
 */
const paragraphs = computed<string[]>(() => {
  const body = props.bodyMarkdown ?? '';
  return body
    .split(/\r?\n\s*\r?\n/) // blank-line separated
    .map((p) => p.trim())
    .filter((p) => p.length > 0);
});

const hasContent = computed(() => paragraphs.value.length > 0);

const formatTime = (ts: string): string => {
  if (!ts) return '';
  return ts.length > 19 ? ts.slice(0, 19).replace('T', ' ') : ts;
};
</script>

<template>
  <el-card
    class="report-preview"
    shadow="never"
    :data-testid="`report-preview-${reportId}`"
  >
    <template #header>
      <div class="report-preview-header">
        <span class="report-preview-title">{{ title }}</span>
        <span class="report-preview-meta">
          <span :data-testid="`report-preview-id-${reportId}`">ID: {{ reportId }}</span>
          <span v-if="auditId" :data-testid="`report-preview-audit-${reportId}`">
            审计: {{ auditId }}
          </span>
          <span>{{ formatTime(createdAt) }}</span>
        </span>
      </div>
    </template>

    <p
      v-if="!hasContent"
      class="report-preview-empty"
      :data-testid="`report-preview-empty-${reportId}`"
    >
      报告内容为空。
    </p>

    <div
      v-else
      class="report-preview-body"
      :data-testid="`report-preview-body-${reportId}`"
    >
      <!--
        SAFETY: v-text treats content as plain text. We never use v-html on
        the body — see the safety contract in <script setup>.
      -->
      <p
        v-for="(p, idx) in paragraphs"
        :key="`${reportId}-p-${idx}`"
        class="report-preview-paragraph"
      >
        {{ p }}
      </p>
    </div>
  </el-card>
</template>

<style scoped>
.report-preview-header {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.report-preview-title {
  font-weight: 600;
  color: #1f2d3d;
  font-size: 1rem;
}

.report-preview-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 0.75rem;
  font-size: 0.75rem;
  color: #909399;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}

.report-preview-body {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.report-preview-paragraph {
  margin: 0;
  color: #303133;
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 0.9rem;
  line-height: 1.6;
}

.report-preview-empty {
  margin: 0;
  color: #909399;
  font-style: italic;
}
</style>
