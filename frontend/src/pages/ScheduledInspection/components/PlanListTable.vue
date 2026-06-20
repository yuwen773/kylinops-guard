<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  deletePlan as apiDeletePlan,
  disablePlan as apiDisablePlan,
  enablePlan as apiEnablePlan,
  runPlan as apiRunPlan,
} from '@/api/inspection';
import { ApiError } from '@/api/client';
import type { InspectionPlanSummary, InspectionTemplateType } from '@/types/inspection';

const props = defineProps<{
  plans: InspectionPlanSummary[];
  loading?: boolean;
}>();

void props; // keep prop reference for type-check

const emit = defineEmits<{
  (e: 'changed'): void;
  (e: 'edit', plan: InspectionPlanSummary): void;
  (e: 'run', payload: { planId: string; executionId: string }): void;
}>();

// 模板类型中文映射(与后端 InspectionTemplateRegistry.displayName 对齐)。
const TEMPLATE_LABEL: Record<InspectionTemplateType, string> = {
  HEALTH: '健康',
  DISK: '磁盘',
  SERVICE: '服务',
};

const SCHEDULE_LABEL: Record<string, string> = {
  DAILY: '每日',
  WEEKLY: '每周',
  MONTHLY: '每月',
};

const POLICY_LABEL: Record<string, string> = {
  ALWAYS: '始终通知',
  ON_ABNORMAL: '异常时通知',
  NEVER: '不通知',
};

function templateLabel(t: InspectionTemplateType): string {
  return TEMPLATE_LABEL[t] ?? t;
}

function scheduleLabel(s: string): string {
  return SCHEDULE_LABEL[s] ?? s;
}

function policyLabel(p: string): string {
  return POLICY_LABEL[p] ?? p;
}

function formatDateTime(value: string | null | undefined): string {
  if (!value) return '-';
  return value.length >= 16 ? `${value.slice(0, 10)} ${value.slice(11, 16)}` : value;
}

async function handleEnable(plan: InspectionPlanSummary): Promise<void> {
  try {
    await apiEnablePlan(plan.planId);
    ElMessage.success(`已启用计划《${plan.name}》`);
    emit('changed');
  } catch (err) {
    ElMessage.error(err instanceof ApiError ? err.message : '启用失败');
  }
}

async function handleDisable(plan: InspectionPlanSummary): Promise<void> {
  try {
    await apiDisablePlan(plan.planId);
    ElMessage.success(`已停用计划《${plan.name}》`);
    emit('changed');
  } catch (err) {
    ElMessage.error(err instanceof ApiError ? err.message : '停用失败');
  }
}

async function handleDelete(plan: InspectionPlanSummary): Promise<void> {
  // 关键:同时处理 Element Plus 的 reject(用户取消)和 mock 的 resolve('cancel')。
  // Element Plus 真实行为:confirm → resolve 'confirm';cancel → reject。
  // 测试用 mockResolvedValueOnce('cancel') 来模拟取消 — 必须检查返回值。
  let confirmed: string | unknown;
  try {
    confirmed = await ElMessageBox.confirm(
      `确认删除巡检计划《${plan.name}》?历史执行、报告和审计记录不会被删除。`,
      '删除确认',
      {
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        type: 'warning',
      },
    );
  } catch {
    return; // 真实行为下的取消
  }
  if (confirmed !== 'confirm') return; // mock 下的取消
  try {
    await apiDeletePlan(plan.planId);
    ElMessage.success(`已删除计划《${plan.name}》`);
    emit('changed');
  } catch (err) {
    ElMessage.error(err instanceof ApiError ? err.message : '删除失败');
  }
}

async function handleRun(plan: InspectionPlanSummary): Promise<void> {
  try {
    const res = await apiRunPlan(plan.planId);
    emit('run', { planId: plan.planId, executionId: res.executionId });
  } catch (err) {
    ElMessage.error(err instanceof ApiError ? err.message : '立即执行失败');
  }
}

function handleEdit(plan: InspectionPlanSummary): void {
  emit('edit', plan);
}
</script>

<template>
  <div class="plan-list-table" data-testid="plan-list-table">
    <el-table
      v-loading="loading"
      :data="plans"
      stripe
    >
      <template #empty>
        <div data-testid="plan-list-empty" class="plan-list-empty">
          暂无巡检计划,点击右上角「新建计划」开始配置
        </div>
      </template>
      <el-table-column label="计划名称" min-width="200">
        <template #default="{ row }: { row: InspectionPlanSummary }">
          <span :data-testid="`plan-row-${row.planId}`" class="plan-name-cell">
            {{ row.name }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="模板" width="100">
        <template #default="{ row }: { row: InspectionPlanSummary }">
          {{ templateLabel(row.templateType) }}
        </template>
      </el-table-column>
      <el-table-column label="调度" width="100">
        <template #default="{ row }: { row: InspectionPlanSummary }">
          {{ scheduleLabel(row.scheduleType) }}
        </template>
      </el-table-column>
      <el-table-column label="通知策略" width="120">
        <template #default="{ row }: { row: InspectionPlanSummary }">
          {{ policyLabel(row.notificationPolicy) }}
        </template>
      </el-table-column>
      <el-table-column label="下次执行" width="150">
        <template #default="{ row }: { row: InspectionPlanSummary }">
          {{ formatDateTime(row.nextRunAt) }}
        </template>
      </el-table-column>
      <el-table-column label="上次执行" width="150">
        <template #default="{ row }: { row: InspectionPlanSummary }">
          {{ formatDateTime(row.lastRunAt) }}
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }: { row: InspectionPlanSummary }">
          <el-tag v-if="row.enabled" type="success" size="small" effect="light">已启用</el-tag>
          <el-tag v-else type="info" size="small" effect="plain">已停用</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="280" fixed="right">
        <template #default="{ row }: { row: InspectionPlanSummary }">
          <el-button
            :data-testid="`plan-run-${row.planId}`"
            type="primary"
            size="small"
            plain
            @click="handleRun(row)"
          >
            立即执行
          </el-button>
          <el-button
            v-if="row.enabled"
            :data-testid="`plan-disable-${row.planId}`"
            size="small"
            @click="handleDisable(row)"
          >
            停用
          </el-button>
          <el-button
            v-else
            :data-testid="`plan-enable-${row.planId}`"
            type="success"
            size="small"
            plain
            @click="handleEnable(row)"
          >
            启用
          </el-button>
          <el-button
            :data-testid="`plan-edit-${row.planId}`"
            size="small"
            @click="handleEdit(row)"
          >
            编辑
          </el-button>
          <el-button
            :data-testid="`plan-delete-${row.planId}`"
            type="danger"
            size="small"
            plain
            @click="handleDelete(row)"
          >
            删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
.plan-list-table {
  background: var(--kg-color-surface, #fff);
  border-radius: var(--kg-radius-sm, 4px);
}
.plan-list-empty {
  padding: var(--kg-space-6, 24px) 0;
  color: var(--kg-color-text-mute, #999);
  text-align: center;
  font-size: var(--kg-text-sm, 13px);
}
.plan-name-cell {
  font-weight: 500;
  color: var(--kg-color-text-primary, #333);
}
</style>
