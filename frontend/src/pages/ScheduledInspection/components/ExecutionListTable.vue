<script setup lang="ts">
import { useRouter } from 'vue-router';
import ExecutionStatusBadge from './ExecutionStatusBadge.vue';
import type { InspectionExecutionSummary } from '@/types/inspection';

const props = defineProps<{
  executions: InspectionExecutionSummary[];
  loading?: boolean;
}>();

const router = useRouter();

function formatDateTime(value: string | null | undefined): string {
  if (!value) return '-';
  return value.length >= 16 ? `${value.slice(0, 10)} ${value.slice(11, 16)}` : value;
}

const TRIGGER_LABEL: Record<string, string> = {
  SCHEDULED: '定时',
  MANUAL: '手动',
};

function triggerLabel(t: string): string {
  return TRIGGER_LABEL[t] ?? t;
}

// 行内跳转 — 与现有 Audit / Report 详情路由对齐(均已存在)。
// auditId / reportId 由 InspectionExecutionDetail 提供;Summary 没有 detail
// 字段,所以这里仅在父组件传入详情时才能跳转。当前 props 是 Summary,父组件
// 需要时可调用 getExecution 获取 detail 后再 setDetail。
function viewAudit(auditId: string | null | undefined): void {
  if (!auditId) return;
  void router.push({ path: '/audit', query: { auditId } });
}
function viewReport(reportId: string | null | undefined): void {
  if (!reportId) return;
  void router.push({ path: '/reports', query: { reportId } });
}

// 暴露方法给父组件:由父组件维护 detail map,行内按钮可点击时调用 viewAudit/Report
// Summary 默认不携带 auditId/reportId,所以行内按钮根据 props 的 planId 行
// 是否需要 detail 由父组件决策(用 ref<Record<executionId, {auditId,reportId}>>)。
const detailById = defineModel<Record<string, { auditId?: string | null; reportId?: string | null }>>('detail', { default: () => ({}) });

function getAuditId(executionId: string): string | null | undefined {
  return detailById.value[executionId]?.auditId;
}
function getReportId(executionId: string): string | null | undefined {
  return detailById.value[executionId]?.reportId;
}

void props; // 保持 props 被识别(否则 eslint 会报 unused)
</script>

<template>
  <div class="execution-list-table" data-testid="execution-list-table">
    <el-table
      v-loading="loading"
      :data="executions"
      stripe
      empty-text="暂无执行记录"
    >
      <el-table-column label="执行 ID" min-width="200" prop="executionId">
        <template #default="{ row }: { row: InspectionExecutionSummary }">
          <code class="execution-id">{{ row.executionId }}</code>
        </template>
      </el-table-column>
      <el-table-column label="触发方式" width="100">
        <template #default="{ row }: { row: InspectionExecutionSummary }">
          {{ triggerLabel(row.triggerType) }}
        </template>
      </el-table-column>
      <el-table-column label="操作者" width="120" prop="operator" />
      <el-table-column label="开始时间" width="150">
        <template #default="{ row }: { row: InspectionExecutionSummary }">
          {{ formatDateTime(row.startedAt) }}
        </template>
      </el-table-column>
      <el-table-column label="完成时间" width="150">
        <template #default="{ row }: { row: InspectionExecutionSummary }">
          {{ formatDateTime(row.finishedAt) }}
        </template>
      </el-table-column>
      <el-table-column label="状态" width="120">
        <template #default="{ row }: { row: InspectionExecutionSummary }">
          <ExecutionStatusBadge :status="row.status" />
        </template>
      </el-table-column>
      <el-table-column label="异常" width="70">
        <template #default="{ row }: { row: InspectionExecutionSummary }">
          <el-tag v-if="row.abnormal" type="warning" size="small" effect="plain">异常</el-tag>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{ row }: { row: InspectionExecutionSummary }">
          <el-button
            v-if="getReportId(row.executionId)"
            :data-testid="`execution-report-${row.executionId}`"
            size="small"
            @click="viewReport(getReportId(row.executionId))"
          >
            查看报告
          </el-button>
          <el-button
            v-if="getAuditId(row.executionId)"
            :data-testid="`execution-audit-${row.executionId}`"
            size="small"
            @click="viewAudit(getAuditId(row.executionId))"
          >
            查看审计
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
.execution-list-table {
  background: var(--kg-color-surface, #fff);
  border-radius: var(--kg-radius-sm, 4px);
}
.execution-id {
  font-family: var(--kg-font-mono, monospace);
  font-size: var(--kg-text-xs, 12px);
  color: var(--kg-color-text-secondary, #666);
}
</style>
