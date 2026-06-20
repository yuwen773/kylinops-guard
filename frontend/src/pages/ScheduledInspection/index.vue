<script setup lang="ts">
// 定时巡检 — P1-02 Scheduled Inspection 一级页面。
//
// 设计要点(来自 Inspection §11):
//   * 顶部 header + 两个 section:计划列表 + 执行记录
//   * 列表 / 执行记录均重新拉取(无乐观更新,保证与服务端一致)
//   * 模板字段由后端动态下发,前端不硬编码 HEALTH/DISK/SERVICE 字段
//   * 立即执行触发进度弹窗,后端轮询 1.5s 一次,60s timeout
//   * 删除前 ElMessageBox 确认(文案明示"历史执行、报告和审计记录不会被删除")
//
// 红线(项目级):
//   * 所有 OS 写操作(配置 / 启停)由后端 RiskCheck 兜底,前端不绕过
//   * operator 永远从 session 拿,前端从不传
//   * 删除执行记录不存在 — deletePlan 不会级联删 execution/audit/report
import { onMounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import PlanListTable from './components/PlanListTable.vue';
import PlanFormDialog from './components/PlanFormDialog.vue';
import RunNowProgressDialog from './components/RunNowProgressDialog.vue';
import ExecutionListTable from './components/ExecutionListTable.vue';
import {
  createPlan as apiCreatePlan,
  getExecution as apiGetExecution,
  getTemplates as apiGetTemplates,
  listExecutions as apiListExecutions,
  listPlans as apiListPlans,
  updatePlan as apiUpdatePlan,
} from '@/api/inspection';
import { ApiError } from '@/api/client';
import type {
  InspectionExecutionStatus,
  InspectionExecutionSummary,
  InspectionPlanRequest,
  InspectionPlanSummary,
  InspectionPlanUpdateRequest,
  InspectionTemplateView,
} from '@/types/inspection';

const plans = ref<InspectionPlanSummary[]>([]);
const templates = ref<InspectionTemplateView[]>([]);
const executions = ref<InspectionExecutionSummary[]>([]);
const detailByExecutionId = reactive<
  Record<string, { auditId?: string | null; reportId?: string | null }>
>({});

const isLoadingPlans = ref(false);
const isLoadingExecutions = ref(false);
const isSubmittingPlan = ref(false);

const planDialogVisible = ref(false);
const planDialogMode = ref<'create' | 'update'>('create');
const editingPlan = ref<
  | (InspectionPlanRequest & { version?: number; planId?: string })
  | null
>(null);

const runDialogVisible = ref(false);
const runDialogPlanId = ref('');
const runDialogPlanName = ref('');

const executionFilter = reactive<{
  planId: string;
  status: InspectionExecutionStatus | '';
}>({
  planId: '',
  status: '',
});

const STATUS_OPTIONS: { value: InspectionExecutionStatus; label: string }[] = [
  { value: 'RUNNING', label: '执行中' },
  { value: 'SUCCESS', label: '成功' },
  { value: 'PARTIAL_SUCCESS', label: '部分成功' },
  { value: 'FAILED', label: '失败' },
  { value: 'SKIPPED', label: '已跳过' },
];

async function loadPlans(): Promise<void> {
  isLoadingPlans.value = true;
  try {
    plans.value = await apiListPlans();
  } catch (err) {
    ElMessage.error(err instanceof ApiError ? err.message : '加载巡检计划失败');
  } finally {
    isLoadingPlans.value = false;
  }
}

async function loadTemplates(): Promise<void> {
  try {
    templates.value = await apiGetTemplates();
  } catch (err) {
    ElMessage.error(err instanceof ApiError ? err.message : '加载巡检模板失败');
  }
}

async function loadExecutions(): Promise<void> {
  isLoadingExecutions.value = true;
  try {
    const params: { planId?: string; status?: InspectionExecutionStatus; size: number } = {
      size: 50,
    };
    if (executionFilter.planId) params.planId = executionFilter.planId;
    if (executionFilter.status) params.status = executionFilter.status;
    executions.value = await apiListExecutions(params);
  } catch (err) {
    ElMessage.error(err instanceof ApiError ? err.message : '加载执行记录失败');
  } finally {
    isLoadingExecutions.value = false;
  }
}

async function loadExecutionDetails(): Promise<void> {
  // 对每条记录,异步 fetch detail 来获取 auditId / reportId(用于行内跳转)。
  // 仅在 executions 已加载时执行;不阻塞列表渲染。
  for (const exec of executions.value) {
    if (detailByExecutionId[exec.executionId]) continue;
    try {
      const detail = await apiGetExecution(exec.executionId);
      detailByExecutionId[exec.executionId] = {
        auditId: detail.auditId ?? null,
        reportId: detail.reportId ?? null,
      };
    } catch {
      // 静默失败:行内按钮不显示即可
    }
  }
}

function openCreateDialog(): void {
  planDialogMode.value = 'create';
  editingPlan.value = null;
  planDialogVisible.value = true;
}

function openEditDialog(plan: InspectionPlanSummary): void {
  // 拉 detail 以拿到 templateParams / thresholds JSON
  void (async () => {
    try {
      const detail = await import('@/api/inspection').then((m) => m.getPlan(plan.planId));
      const params = detail.templateParamsJson
        ? (JSON.parse(detail.templateParamsJson) as Record<string, unknown>)
        : {};
      const thresholds = detail.thresholdsJson
        ? (JSON.parse(detail.thresholdsJson) as Record<string, unknown>)
        : {};
      const scheduleCfg = detail.scheduleConfigJson
        ? (JSON.parse(detail.scheduleConfigJson) as Record<string, unknown>)
        : {};
      editingPlan.value = {
        name: detail.name,
        description: detail.description ?? '',
        templateType: detail.templateType,
        templateParams: params,
        thresholds,
        scheduleType: detail.scheduleType,
        localTime: typeof scheduleCfg.localTime === 'string' ? scheduleCfg.localTime : '03:00:00',
        timezone: detail.timezone,
        dayOfWeek: (scheduleCfg.dayOfWeek as InspectionPlanRequest['dayOfWeek']) ?? undefined,
        dayOfMonth: typeof scheduleCfg.dayOfMonth === 'number' ? scheduleCfg.dayOfMonth : undefined,
        notificationPolicy: detail.notificationPolicy,
        version: detail.version,
        planId: detail.planId,
      };
      planDialogMode.value = 'update';
      planDialogVisible.value = true;
    } catch (err) {
      ElMessage.error(err instanceof ApiError ? err.message : '加载计划详情失败');
    }
  })();
}

async function handlePlanSubmit(
  payload: InspectionPlanRequest | InspectionPlanUpdateRequest,
  mode: 'create' | 'update',
): Promise<void> {
  isSubmittingPlan.value = true;
  try {
    if (mode === 'create') {
      await apiCreatePlan(payload as InspectionPlanRequest);
      ElMessage.success('巡检计划已创建');
    } else if (editingPlan.value?.planId) {
      await apiUpdatePlan(editingPlan.value.planId, payload as InspectionPlanUpdateRequest);
      ElMessage.success('巡检计划已更新');
    }
    planDialogVisible.value = false;
    await loadPlans();
  } catch (err) {
    ElMessage.error(err instanceof ApiError ? err.message : '保存巡检计划失败');
  } finally {
    isSubmittingPlan.value = false;
  }
}

function handlePlanRun(payload: { planId: string; executionId: string }): void {
  const plan = plans.value.find((p) => p.planId === payload.planId);
  if (!plan) return;
  runDialogPlanId.value = plan.planId;
  runDialogPlanName.value = plan.name;
  runDialogVisible.value = true;
}

async function handleRunDone(): Promise<void> {
  await loadExecutions();
  await loadExecutionDetails();
}

function applyFilter(): void {
  void loadExecutions();
}

function resetFilter(): void {
  executionFilter.planId = '';
  executionFilter.status = '';
  void loadExecutions();
}

onMounted(async () => {
  await Promise.all([loadTemplates(), loadPlans()]);
  await loadExecutions();
  await loadExecutionDetails();
});
</script>

<template>
  <div class="scheduled-inspection-page" data-testid="scheduled-inspection-page">
    <header class="page-header">
      <div>
        <h1 class="page-title">定时巡检</h1>
        <p class="page-subtitle">配置定时执行的系统巡检计划,自动调用多工具链路并落地报告与审计</p>
      </div>
      <el-button
        type="primary"
        :data-testid="'plan-new-button'"
        @click="openCreateDialog"
      >
        新建计划
      </el-button>
    </header>

    <section class="page-section" data-testid="plan-list-section">
      <h2 class="section-title">巡检计划</h2>
      <PlanListTable
        :plans="plans"
        :loading="isLoadingPlans"
        @changed="loadPlans"
        @edit="openEditDialog"
        @run="handlePlanRun"
      />
    </section>

    <section class="page-section" data-testid="execution-list-section">
      <h2 class="section-title">执行记录</h2>
      <div class="filter-bar">
        <el-select
          v-model="executionFilter.planId"
          placeholder="按计划过滤"
          clearable
          data-testid="execution-filter-plan"
          style="width: 220px"
          @change="applyFilter"
        >
          <el-option
            v-for="p in plans"
            :key="p.planId"
            :value="p.planId"
            :label="p.name"
          />
        </el-select>
        <el-select
          v-model="executionFilter.status"
          placeholder="按状态过滤"
          clearable
          data-testid="execution-filter-status"
          style="width: 160px"
          @change="applyFilter"
        >
          <el-option
            v-for="s in STATUS_OPTIONS"
            :key="s.value"
            :value="s.value"
            :label="s.label"
          />
        </el-select>
        <el-button @click="resetFilter">重置</el-button>
      </div>
      <ExecutionListTable
        :executions="executions"
        :loading="isLoadingExecutions"
        v-model:detail="detailByExecutionId"
      />
    </section>

    <PlanFormDialog
      v-model:visible="planDialogVisible"
      :templates="templates"
      :initial-plan="editingPlan"
      :submitting="isSubmittingPlan"
      @submit="handlePlanSubmit"
    />

    <RunNowProgressDialog
      v-model:visible="runDialogVisible"
      :plan-id="runDialogPlanId"
      :plan-name="runDialogPlanName"
      @done="handleRunDone"
    />
  </div>
</template>

<style scoped>
.scheduled-inspection-page {
  display: flex;
  flex-direction: column;
  gap: var(--kg-space-6, 24px);
  padding: 0;
}

.page-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--kg-space-4, 16px);
}
.page-title {
  margin: 0 0 var(--kg-space-1, 4px) 0;
  font-size: var(--kg-text-xl, 20px);
  font-weight: 600;
  color: var(--kg-color-text-primary);
}
.page-subtitle {
  margin: 0;
  color: var(--kg-color-text-mute);
  font-size: var(--kg-text-sm, 13px);
}

.page-section {
  background: var(--kg-color-surface, #fff);
  border: 1px solid var(--kg-color-border, #ebeef5);
  border-radius: var(--kg-radius-md, 6px);
  padding: var(--kg-space-5, 20px);
  display: flex;
  flex-direction: column;
  gap: var(--kg-space-3, 12px);
}
.section-title {
  margin: 0;
  font-size: var(--kg-text-md, 14px);
  font-weight: 600;
  color: var(--kg-color-text-primary);
}

.filter-bar {
  display: flex;
  gap: var(--kg-space-3, 12px);
  align-items: center;
  flex-wrap: wrap;
}
</style>
