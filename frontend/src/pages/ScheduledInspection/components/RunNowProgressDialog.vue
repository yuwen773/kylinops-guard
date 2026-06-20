<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import ExecutionStatusBadge from './ExecutionStatusBadge.vue';
import { ApiError } from '@/api/client';
import { getExecution, runPlan } from '@/api/inspection';
import type {
  InspectionExecutionDetail,
  InspectionExecutionStatus,
} from '@/types/inspection';

/**
 * RunNowProgressDialog — 立即执行进度弹窗。
 *
 * 流程(来自 Inspection §11.2):
 *   1. visible=true → POST /api/inspections/plans/{planId}/run,获取 executionId
 *   2. 立即 GET /api/inspections/executions/{id} 查 status
 *   3. 若仍 RUNNING → setInterval(1500ms) 轮询
 *   4. status ∈ {SUCCESS, PARTIAL_SUCCESS, FAILED, SKIPPED} → 终态,clearInterval + emit done
 *   5. 60s timeout → 展示"未在预期时间内完成",clearInterval + emit done
 *
 * 资源释放:
 *   * 组件 unmount 时 clearInterval(避免内存泄漏)
 *   * visible=false 时立刻 clearInterval(用户主动关闭)
 *
 * 红线:
 *   * 不调用 ElMessage.success 当 timeout(timeout 是用户等待超时,不是成功)
 *   * 不静默关闭:终态必须 ElMessage + emit done 让父组件 reload
 */

const POLL_INTERVAL_MS = 1500;
const TIMEOUT_MS = 60_000;

const props = defineProps<{
  visible: boolean;
  planId: string;
  planName: string;
}>();

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void;
  (
    e: 'done',
    payload: {
      executionId: string;
      status: InspectionExecutionStatus | 'TIMEOUT';
      execution?: InspectionExecutionDetail;
    },
  ): void;
}>();

const isPolling = ref(false);
const isTimeout = ref(false);
const isError = ref(false);
const isSuccess = ref(false);
const isFailed = ref(false);
const currentStatus = ref<InspectionExecutionStatus | 'PENDING' | 'TIMEOUT'>('PENDING');
const executionId = ref<string | null>(null);
const execution = ref<InspectionExecutionDetail | null>(null);
const errorMessage = ref<string | null>(null);

let pollTimer: ReturnType<typeof setInterval> | null = null;
let timeoutTimer: ReturnType<typeof setTimeout> | null = null;
let startedAt = 0;

function clearTimers(): void {
  if (pollTimer !== null) {
    clearInterval(pollTimer);
    pollTimer = null;
  }
  if (timeoutTimer !== null) {
    clearTimeout(timeoutTimer);
    timeoutTimer = null;
  }
}

function closeDialog(): void {
  clearTimers();
  isPolling.value = false;
  emit('update:visible', false);
}

function isTerminal(status: InspectionExecutionStatus): boolean {
  return (
    status === 'SUCCESS' ||
    status === 'PARTIAL_SUCCESS' ||
    status === 'FAILED' ||
    status === 'SKIPPED'
  );
}

async function pollExecution(): Promise<void> {
  if (!executionId.value) return;
  if (Date.now() - startedAt > TIMEOUT_MS) {
    handleTimeout();
    return;
  }
  try {
    const exec = await getExecution(executionId.value);
    execution.value = exec;
    currentStatus.value = exec.status;
    if (isTerminal(exec.status)) {
      handleTerminal(exec);
    }
  } catch (err) {
    // 单次轮询失败:记录到 errorMessage(展示在 dialog 里)但不打断轮询;
    // 网络抖动允许重试,只有 60s timeout 才彻底停。
    errorMessage.value = err instanceof ApiError ? err.message : '巡检状态查询失败';
  }
}

function handleTerminal(exec: InspectionExecutionDetail): void {
  clearTimers();
  isPolling.value = false;
  if (exec.status === 'SUCCESS' || exec.status === 'PARTIAL_SUCCESS') {
    isSuccess.value = true;
    ElMessage.success(`巡检完成:${exec.status === 'SUCCESS' ? '成功' : '部分成功'}`);
  } else if (exec.status === 'FAILED') {
    isFailed.value = true;
    errorMessage.value = exec.errorMessage ?? exec.summary ?? '巡检失败';
    ElMessage.error(errorMessage.value);
  } else {
    // SKIPPED
    ElMessage.warning('巡检已跳过');
  }
  emit('done', { executionId: exec.executionId, status: exec.status, execution: exec });
}

function handleTimeout(): void {
  clearTimers();
  isPolling.value = false;
  isTimeout.value = true;
  currentStatus.value = 'TIMEOUT';
  emit('done', {
    executionId: executionId.value ?? '',
    status: 'TIMEOUT',
    execution: execution.value ?? undefined,
  });
}

async function startRun(planId: string): Promise<void> {
  isPolling.value = true;
  isTimeout.value = false;
  isError.value = false;
  isSuccess.value = false;
  isFailed.value = false;
  errorMessage.value = null;
  currentStatus.value = 'RUNNING';
  startedAt = Date.now();
  try {
    const resp = await runPlan(planId);
    executionId.value = resp.executionId;
    currentStatus.value = resp.status;
    // 立即查一次,若是终态直接收敛
    await pollExecution();
    if (isPolling.value && !isTerminal(currentStatus.value as InspectionExecutionStatus) && !isTimeout.value) {
      // 启动轮询
      pollTimer = setInterval(() => {
        void pollExecution();
      }, POLL_INTERVAL_MS);
    }
  } catch (err) {
    isPolling.value = false;
    isError.value = true;
    errorMessage.value = err instanceof ApiError ? err.message : '立即执行失败';
    ElMessage.error(errorMessage.value);
    emit('done', { executionId: '', status: 'FAILED' });
  }
}

watch(
  () => props.visible,
  (next) => {
    if (next) {
      void startRun(props.planId);
    } else {
      clearTimers();
      isPolling.value = false;
    }
  },
  { immediate: true },
);

onUnmounted(() => {
  clearTimers();
});
</script>

<template>
  <el-dialog
    :model-value="visible"
    title="立即执行"
    width="520"
    :close-on-click-modal="false"
    :show-close="!isPolling"
    align-center
    @update:model-value="(v: boolean) => emit('update:visible', v)"
    @close="closeDialog"
  >
    <div class="run-progress" data-testid="run-progress-dialog">
      <p class="run-progress__title">
        正在执行巡检计划:<b>{{ planName }}</b>
      </p>

      <div v-if="executionId" class="run-progress__id">
        执行 ID:<code>{{ executionId }}</code>
      </div>

      <div class="run-progress__status" data-testid="run-progress-status">
        <span v-if="isPolling">执行中,请稍候...</span>
        <ExecutionStatusBadge v-else-if="currentStatus !== 'PENDING' && currentStatus !== 'TIMEOUT'" :status="currentStatus" />
        <span v-else-if="isTimeout">已超时</span>
      </div>

      <el-progress
        v-if="isPolling"
        :percentage="isTimeout ? 100 : 60"
        :status="isTimeout ? 'exception' : undefined"
        :indeterminate="!isTimeout"
        :duration="2"
      />

      <div
        v-if="isTimeout"
        class="run-progress__timeout"
        data-testid="run-progress-timeout"
      >
        巡检未在预期时间内完成,请稍后到「执行记录」查看最新状态。
      </div>

      <div
        v-if="(isFailed || isError) && errorMessage"
        class="run-progress__error"
        data-testid="run-progress-error"
      >
        失败原因:{{ errorMessage }}
      </div>
    </div>

    <template #footer>
      <el-button
        v-if="!isPolling"
        type="primary"
        data-testid="run-progress-close"
        @click="closeDialog"
      >
        关闭
      </el-button>
      <el-button v-else disabled>执行中...</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.run-progress {
  display: flex;
  flex-direction: column;
  gap: var(--kg-space-3, 12px);
  padding: var(--kg-space-2, 8px) 0;
}
.run-progress__title {
  margin: 0;
  font-size: var(--kg-text-md, 14px);
  color: var(--kg-color-text-primary);
}
.run-progress__id {
  font-size: var(--kg-text-xs, 12px);
  color: var(--kg-color-text-mute);
}
.run-progress__id code {
  font-family: var(--kg-font-mono, monospace);
  margin-left: var(--kg-space-1, 4px);
}
.run-progress__status {
  display: flex;
  align-items: center;
  gap: var(--kg-space-2, 8px);
  font-size: var(--kg-text-sm, 13px);
}
.run-progress__timeout,
.run-progress__error {
  padding: var(--kg-space-3, 12px);
  border-radius: var(--kg-radius-sm, 4px);
  background: var(--kg-color-warning-soft, #fff7e6);
  color: var(--kg-color-text-primary, #333);
  font-size: var(--kg-text-sm, 13px);
}
.run-progress__error {
  background: var(--kg-color-danger-soft, #fff1f0);
}
</style>
