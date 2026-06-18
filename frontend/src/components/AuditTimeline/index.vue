<script setup lang="ts">
// AuditTimeline — renders the lifecycle of a single request as an ordered
// timeline. Each stage corresponds to a backend audit-log entry sharing the
// same auditId:
//   1. risk-check    (前置安全风险校验)
//   2. tool-call     (OpsTool 调用)
//   3. confirmation  (L2 用户确认)
//   4. execution     (SafeExecutor 实际执行)
//   5. answer        (最终回复落库)
//
// The component does NOT synthesise or interpolate missing stages. If the
// backend did not record a stage, the timeline shows "数据不可用" so the
// auditor can see the gap rather than a fabricated step.
import { computed } from 'vue';

export interface AuditTimelineEntry {
  /** Backend stage name (must match one of STAGE_LABELS keys, or be freeform). */
  stage: string;
  /** ISO-8601 or epoch-millis timestamp from the audit log. */
  timestamp: string;
  /** Short human-readable summary. */
  summary: string;
}

/** Canonical stage order. Anything else is appended at the end. */
const STAGE_ORDER: ReadonlyArray<string> = [
  'risk-check',
  'tool-call',
  'confirmation',
  'execution',
  'answer',
];

/** Chinese labels for the canonical stages. Freeform stages fall back to raw. */
const STAGE_LABELS: Readonly<Record<string, string>> = {
  'risk-check': '风险校验',
  'tool-call': '工具调用',
  'confirmation': '用户确认',
  'execution': '安全执行',
  'answer': '最终回复',
};

interface Props {
  entries: ReadonlyArray<AuditTimelineEntry>;
}

const props = defineProps<Props>();

const sortedEntries = computed(() => {
  const orderIndex = (stage: string): number => {
    const i = STAGE_ORDER.indexOf(stage);
    return i === -1 ? STAGE_ORDER.length : i;
  };
  return [...props.entries].sort((a, b) => {
    const oa = orderIndex(a.stage);
    const ob = orderIndex(b.stage);
    if (oa !== ob) return oa - ob;
    return a.timestamp.localeCompare(b.timestamp);
  });
});

const hasEntries = computed(() => sortedEntries.value.length > 0);

const formatTime = (ts: string): string => {
  if (!ts) return '';
  // Pass through ISO strings; the audit detail page already formats with
  // full precision. We only need a short, sortable display here.
  return ts.length > 19 ? ts.slice(0, 19).replace('T', ' ') : ts;
};
</script>

<template>
  <el-card
    class="audit-timeline"
    shadow="never"
    data-testid="audit-timeline"
  >
    <template #header>
      <span>请求生命周期</span>
    </template>

    <p
      v-if="!hasEntries"
      class="audit-timeline-empty"
      data-testid="audit-timeline-empty"
    >
      数据不可用：未找到该请求的审计阶段记录。
    </p>

    <el-timeline v-else>
      <el-timeline-item
        v-for="(entry, idx) in sortedEntries"
        :key="`${entry.stage}-${entry.timestamp}-${idx}`"
        :data-testid="`audit-timeline-entry-${entry.stage}`"
        :timestamp="formatTime(entry.timestamp)"
        placement="top"
      >
        <div class="audit-timeline-stage">
          {{ STAGE_LABELS[entry.stage] ?? entry.stage }}
        </div>
        <div class="audit-timeline-summary">{{ entry.summary }}</div>
      </el-timeline-item>
    </el-timeline>
  </el-card>
</template>

<style scoped>
.audit-timeline {
  margin-bottom: var(--kg-space-4);
}

.audit-timeline-empty {
  margin: 0;
  padding: var(--kg-space-2) 0;
  color: var(--kg-color-text-mute);
  font-style: italic;
}

.audit-timeline-stage {
  font-weight: 600;
  color: var(--kg-color-text-primary);
  margin-bottom: var(--kg-space-1);
}

.audit-timeline-summary {
  color: var(--kg-color-text-secondary);
  word-break: break-all;
}
</style>
