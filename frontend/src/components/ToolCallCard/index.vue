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
  margin-bottom: 0.75rem;
}

.tool-call-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
  margin-bottom: 0.5rem;
}

.tool-call-name {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-weight: 600;
  color: #1f2d3d;
}

.tool-call-section {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  margin-top: 0.5rem;
}

.tool-call-label {
  font-size: 0.75rem;
  color: #909399;
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.tool-call-mono {
  margin: 0;
  padding: 0.5rem 0.75rem;
  background: #f5f7fa;
  border-radius: 4px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 0.8rem;
  white-space: pre-wrap;
  word-break: break-all;
  color: #303133;
}

.tool-call-error .tool-call-mono {
  background: #fef0f0;
  color: #c45656;
}

.tool-call-meta {
  flex-direction: row;
  align-items: center;
  gap: 0.5rem;
}

.tool-call-meta .tool-call-label {
  margin-right: 0.25rem;
}
</style>
