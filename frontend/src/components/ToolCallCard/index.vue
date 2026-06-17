<script setup lang="ts">
// ToolCallCard — visualises a single OpsTool invocation in the audit chain.
//
// Pure presentation: shows the tool name, status, key input / output fields
// and the optional error message. It does NOT call any API, does NOT decide
// whether the call is safe, and does NOT mutate the input. If the backend
// returns "BLOCKED", the UI shows "BLOCKED" — we do not soften.
import { computed } from 'vue';
import { TOOL_CALL_LABELS, type ToolCallDisplayStatus } from '@/types/safety';

export interface ToolCallProps {
  toolName: string;
  status: ToolCallDisplayStatus;
  /** Optional input JSON string the tool was invoked with. */
  input?: string;
  /** Optional output summary; full output is in the audit detail page. */
  output?: string;
  /** Optional human-readable error message (FAILED/TIMEOUT). */
  errorMessage?: string;
  /** Optional execution duration in milliseconds. */
  durationMs?: number;
}

const props = defineProps<ToolCallProps>();

// Tone mapping: success green, blocked red, failed/timeout red (failed state).
const tone = computed<'success' | 'danger' | 'info'>(() => {
  if (props.status === 'success') return 'success';
  if (props.status === 'blocked') return 'danger';
  return 'danger';
});

const statusText = computed(() => TOOL_CALL_LABELS[props.status]);

const hasError = computed(
  () => props.status === 'failed' || props.status === 'timeout' || props.status === 'blocked',
);

const durationText = computed(() => {
  if (typeof props.durationMs !== 'number' || !Number.isFinite(props.durationMs)) {
    return '';
  }
  if (props.durationMs < 1000) return `${props.durationMs} ms`;
  return `${(props.durationMs / 1000).toFixed(2)} s`;
});
</script>

<template>
  <el-card
    class="tool-call-card"
    shadow="never"
    :data-testid="`tool-call-${toolName}`"
  >
    <div class="tool-call-header">
      <span class="tool-call-name">{{ toolName }}</span>
      <el-tag
        :type="tone"
        size="small"
        :data-testid="`tool-call-status-${status}`"
      >
        {{ statusText }}
      </el-tag>
    </div>

    <div v-if="input" class="tool-call-section">
      <span class="tool-call-label">输入</span>
      <pre class="tool-call-mono">{{ input }}</pre>
    </div>

    <div v-if="output" class="tool-call-section">
      <span class="tool-call-label">输出</span>
      <pre class="tool-call-mono">{{ output }}</pre>
    </div>

    <div v-if="hasError && errorMessage" class="tool-call-section tool-call-error">
      <span class="tool-call-label">错误信息</span>
      <pre class="tool-call-mono">{{ errorMessage }}</pre>
    </div>

    <div v-if="durationText" class="tool-call-section tool-call-meta">
      <span class="tool-call-label">耗时</span>
      <span>{{ durationText }}</span>
    </div>
  </el-card>
</template>

<style scoped>
.tool-call-card {
  margin-bottom: var(--kg-space-3);
}

.tool-call-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--kg-space-3);
  margin-bottom: var(--kg-space-2);
}

.tool-call-name {
  font-family: var(--kg-font-mono);
  font-weight: 600;
  color: var(--kg-color-text-primary);
}

.tool-call-section {
  display: flex;
  flex-direction: column;
  gap: var(--kg-space-1);
  margin-top: var(--kg-space-2);
}

.tool-call-label {
  font-size: var(--kg-text-xs);
  color: var(--kg-color-text-mute);
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.tool-call-mono {
  margin: 0;
  padding: var(--kg-space-2) var(--kg-space-3);
  background: var(--kg-color-surface-code);
  border-radius: var(--kg-radius-sm);
  border: 1px solid var(--kg-color-border-mute);
  font-family: var(--kg-font-mono);
  font-size: var(--kg-text-sm);
  white-space: pre-wrap;
  word-break: break-all;
  color: var(--kg-color-text-secondary);
  line-height: var(--kg-line-base);
}

.tool-call-error .tool-call-mono {
  background: var(--kg-color-danger-soft);
  border-color: var(--kg-color-danger);
  color: var(--kg-color-danger);
}

.tool-call-meta {
  flex-direction: row;
  align-items: center;
  gap: var(--kg-space-2);
}

.tool-call-meta .tool-call-label {
  margin-right: var(--kg-space-1);
}
</style>
