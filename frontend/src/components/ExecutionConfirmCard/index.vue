<script setup lang="ts">
// ExecutionConfirmCard — user-facing confirm/cancel surface for L2
// PendingAction entries.
//
// SAFETY CONTRACT:
//   * This component does NOT call any API. The parent owns the network
//     round-trip to /api/actions/confirm and the loading/error UI.
//   * It MUST disable both buttons the moment either one is clicked
//     ("in-flight" state) so a network hiccup cannot produce a duplicate
//     confirm or cancel request.
//   * It MUST surface the backend's risk verdict verbatim. No "soften" of
//     L2 CONFIRM. If the parent passes BLOCK, the buttons disappear — the
//     user has nothing to confirm.
//
// Events emitted (both pass the actionId so the parent can correlate):
//   * confirm(actionId)  — user agreed, parent should call /api/actions/confirm.
//   * cancel(actionId)   — user declined, parent should call /api/actions/confirm
//                          with confirmed=false.
import { computed, ref } from 'vue';
import RiskLevelTag from '@/components/RiskLevelTag/index.vue';
import type { RiskDecision, RiskLevel } from '@/types/safety';

interface Props {
  /** Backend PendingAction id. The same id is sent on confirm/cancel. */
  actionId: string;
  /** Short human-readable summary of what is being confirmed. */
  summary: string;
  /** Backend-returned risk level. */
  riskLevel: RiskLevel;
  /** Backend-returned decision. L2 = CONFIRM (show buttons), BLOCK = hide. */
  decision: RiskDecision;
  /** Optional verbose detail (matched rules, suggested safer alternative, ...). */
  detail?: string;
}

const props = defineProps<Props>();
const emit = defineEmits<{
  (e: 'confirm', actionId: string): void;
  (e: 'cancel', actionId: string): void;
}>();

// inFlight is a local guard. Once either button is clicked, both go
// disabled. We deliberately do NOT auto-reset — the parent will unmount
// this card after the API call settles, and re-enabling could re-emit.
const inFlight = ref(false);

const isConfirmable = computed(() => props.decision === 'CONFIRM');

const handleConfirm = () => {
  if (inFlight.value) return;
  if (!isConfirmable.value) return;
  inFlight.value = true;
  emit('confirm', props.actionId);
};

const handleCancel = () => {
  if (inFlight.value) return;
  inFlight.value = true;
  emit('cancel', props.actionId);
};
</script>

<template>
  <el-card
    class="execution-confirm-card"
    shadow="never"
    :data-testid="`execution-confirm-${actionId}`"
  >
    <div class="execution-confirm-header">
      <RiskLevelTag :level="riskLevel" :decision="decision" />
      <span class="execution-confirm-title">待确认操作</span>
    </div>

    <p class="execution-confirm-summary">{{ summary }}</p>

    <pre
      v-if="detail"
      class="execution-confirm-detail"
      :data-testid="`execution-confirm-detail-${actionId}`"
    >{{ detail }}</pre>

    <div v-if="isConfirmable" class="execution-confirm-actions">
      <el-button
        type="primary"
        :loading="inFlight"
        :disabled="inFlight"
        :data-testid="`execution-confirm-confirm-${actionId}`"
        @click="handleConfirm"
      >
        确认执行
      </el-button>
      <el-button
        :loading="inFlight"
        :disabled="inFlight"
        :data-testid="`execution-confirm-cancel-${actionId}`"
        @click="handleCancel"
      >
        取消
      </el-button>
    </div>

    <p
      v-else
      class="execution-confirm-blocked"
      :data-testid="`execution-confirm-blocked-${actionId}`"
    >
      该操作已被安全规则阻断，无须用户确认。
    </p>
  </el-card>
</template>

<style scoped>
.execution-confirm-card {
  border-left: 4px solid #e6a23c;
  margin-bottom: 0.75rem;
}

.execution-confirm-header {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-bottom: 0.5rem;
}

.execution-confirm-title {
  font-weight: 600;
  color: #1f2d3d;
}

.execution-confirm-summary {
  margin: 0.25rem 0 0.5rem 0;
  color: #303133;
}

.execution-confirm-detail {
  margin: 0.25rem 0 0.75rem 0;
  padding: 0.5rem 0.75rem;
  background: #fdf6ec;
  border-radius: 4px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 0.8rem;
  white-space: pre-wrap;
  word-break: break-all;
  color: #b88230;
}

.execution-confirm-actions {
  display: flex;
  gap: 0.5rem;
  margin-top: 0.5rem;
}

.execution-confirm-blocked {
  margin: 0.5rem 0 0 0;
  color: #c45656;
  font-weight: 500;
}
</style>
