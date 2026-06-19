<script setup lang="ts">
// ChannelEditDialog — create/update dialog for a single notification channel.
//
// Two modes, distinguished by `mode`:
//   * 'create' — channelId is editable, version is null. submit() calls createChannel().
//   * 'edit'   — channelId is read-only and version is locked. submit() calls updateChannel().
//
// SECRET SAFETY:
//   * The bound secret input is ALWAYS empty on open. There is no path in
//     this component that echoes a stored secret back into the input —
//     doing so would be a security regression (the server never returns
//     the secret value via GET; we MUST NOT surface it client-side either).
//   * If the user types a new secret it replaces the stored value on save.
//   * If the user toggles "清除已存密钥" (clearSecret) on edit, the stored
//     secret is wiped on the server.
//
// TEST HOOKS:
//   * Each form field is wrapped in a `<div data-testid="...">` so unit
//     tests have a stable DOM anchor that survives Element Plus's
//     fallthrough-attribute quirks (el-input strips some attrs).

import { computed, ref, watch } from 'vue';
import {
  ElMessage,
  type FormInstance,
  type FormRules,
} from 'element-plus';
import type { ChannelForm, ChannelType, NotificationChannel } from '@/types/notification';

interface Props {
  modelValue: boolean;
  /** Edit source row; null when creating a new channel. */
  channel: NotificationChannel | null;
  /** 'create' submits via createChannel; 'edit' submits via updateChannel. */
  mode: 'create' | 'edit';
  /** Async submit handler invoked with the filled form. */
  onSubmit: (form: ChannelForm) => Promise<void>;
}

const props = defineProps<Props>();
const emit = defineEmits<{
  'update:modelValue': [value: boolean];
  saved: [];
}>();

const formRef = ref<FormInstance | null>(null);
const submitting = ref(false);

const DEFAULT_TIMEOUT = 3000;
const MIN_TIMEOUT = 500;
const MAX_TIMEOUT = 30_000;

const channelId = ref('');
const type = ref<ChannelType>('WEBHOOK');
const enabled = ref(true);
const url = ref('');
const secret = ref('');
const clearSecret = ref(false);
const timeoutMs = ref<number>(DEFAULT_TIMEOUT);
const version = ref<number | null>(null);

const channelTypeOptions: { label: string; value: ChannelType }[] = [
  { label: 'Webhook', value: 'WEBHOOK' },
  { label: '飞书', value: 'FEISHU' },
];

const title = computed(() => (props.mode === 'create' ? '新建通道' : '编辑通道'));

const isEdit = computed(() => props.mode === 'edit');

function reset(): void {
  channelId.value = '';
  type.value = 'WEBHOOK';
  enabled.value = true;
  url.value = '';
  secret.value = '';
  clearSecret.value = false;
  timeoutMs.value = DEFAULT_TIMEOUT;
  version.value = null;
}

function fillFromChannel(row: NotificationChannel): void {
  // IMPORTANT: `secret` is NOT copied from the row. The wire shape has no
  // secret field; even if a future schema accidentally carries one, this
  // component must NEVER echo it back into the input.
  channelId.value = row.id;
  type.value = row.type;
  enabled.value = row.enabled;
  url.value = row.url;
  secret.value = '';
  clearSecret.value = false;
  timeoutMs.value = row.timeoutMs ?? DEFAULT_TIMEOUT;
  version.value = row.version ?? null;
}

watch(
  () => [props.modelValue, props.mode, props.channel] as const,
  ([open]) => {
    if (!open) return;
    if (props.mode === 'edit' && props.channel) {
      fillFromChannel(props.channel);
    } else {
      reset();
    }
  },
  { immediate: true },
);

const rules: FormRules = {
  channelId: [
    {
      required: true,
      message: '通道 ID 不能为空',
      trigger: 'blur',
    },
    {
      pattern: /^[A-Za-z0-9_-]+$/,
      message: '仅支持字母、数字、下划线和短横线',
      trigger: 'blur',
    },
  ],
  url: [
    { required: true, message: 'URL 不能为空', trigger: 'blur' },
    { type: 'url', message: 'URL 格式不合法', trigger: 'blur' },
  ],
  timeoutMs: [
    { required: true, message: '超时不能为空', trigger: 'blur' },
    {
      validator: (_rule, value: number, callback) => {
        if (!Number.isFinite(value)) {
          callback(new Error('超时必须是数字'));
          return;
        }
        if (value < MIN_TIMEOUT || value > MAX_TIMEOUT) {
          callback(new Error(`超时范围 ${MIN_TIMEOUT}–${MAX_TIMEOUT} ms`));
          return;
        }
        callback();
      },
      trigger: 'blur',
    },
  ],
  secret: [
    {
      validator: (_rule, value: string | undefined, callback) => {
        // FEISHU channels REQUIRE a secret on create. On edit, leaving the
        // field empty AND not toggling clearSecret means "keep the stored
        // value", which is allowed.
        if (type.value !== 'FEISHU' || props.mode !== 'create') {
          callback();
          return;
        }
        if (!value || value.trim() === '') {
          callback(new Error('飞书通道必须设置密钥'));
          return;
        }
        callback();
      },
      trigger: 'blur',
    },
  ],
};

function close(): void {
  if (submitting.value) return;
  emit('update:modelValue', false);
}

async function handleSubmit(): Promise<void> {
  if (!formRef.value) return;
  try {
    await formRef.value.validate();
  } catch {
    return;
  }

  const payload: ChannelForm = {
    type: type.value,
    enabled: enabled.value,
    url: url.value.trim(),
    timeoutMs: timeoutMs.value,
    clearSecret: isEdit.value ? clearSecret.value : false,
  };
  if (props.mode === 'create') {
    payload.channelId = channelId.value.trim();
    payload.version = null;
    if (secret.value.trim() !== '') {
      payload.secret = secret.value;
    }
  } else {
    payload.version = version.value;
    if (secret.value.trim() !== '') {
      payload.secret = secret.value;
    }
  }

  submitting.value = true;
  try {
    await props.onSubmit(payload);
    ElMessage.success(props.mode === 'create' ? '通道已创建' : '通道已更新');
    emit('saved');
    emit('update:modelValue', false);
  } catch (err) {
    const msg =
      err instanceof Error ? err.message : '保存失败，请稍后重试';
    ElMessage.error(msg);
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="title"
    width="520px"
    :close-on-click-modal="false"
    :close-on-press-escape="!submitting"
    data-testid="notification-channel-dialog"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
  >
    <el-form
      ref="formRef"
      :model="{ channelId, type, enabled, url, secret, timeoutMs }"
      :rules="rules"
      label-width="100px"
      label-position="right"
      data-testid="notification-channel-dialog-form"
    >
      <el-form-item label="通道 ID" prop="channelId">
        <div data-testid="notification-channel-dialog-channel-id" style="width: 100%">
          <el-input
            v-model="channelId"
            :readonly="isEdit"
            placeholder="例如 feishu-oncall"
          />
        </div>
      </el-form-item>

      <el-form-item label="通道类型" prop="type">
        <div data-testid="notification-channel-dialog-type" style="width: 100%">
          <el-select v-model="type" style="width: 100%">
            <el-option
              v-for="opt in channelTypeOptions"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </div>
      </el-form-item>

      <el-form-item label="启用">
        <div data-testid="notification-channel-dialog-enabled">
          <el-switch v-model="enabled" />
        </div>
      </el-form-item>

      <el-form-item label="URL" prop="url">
        <div data-testid="notification-channel-dialog-url" style="width: 100%">
          <el-input
            v-model="url"
            placeholder="https://example.com/hook"
          />
        </div>
      </el-form-item>

      <el-form-item label="密钥" prop="secret">
        <div data-testid="notification-channel-dialog-secret" style="width: 100%">
          <el-input
            v-model="secret"
            type="password"
            show-password
            :placeholder="
              isEdit ? '留空表示保持原值，输入则覆盖' : '可选；飞书通道必填'
            "
          />
        </div>
        <div class="notification-channel-secret-hint">
          <template v-if="isEdit">
            <div data-testid="notification-channel-dialog-clear-secret">
              <el-checkbox v-model="clearSecret">
                清除已存密钥
              </el-checkbox>
            </div>
          </template>
          <template v-else>
            <span>密钥仅写入时使用，保存后不可查看。</span>
          </template>
        </div>
      </el-form-item>

      <el-form-item label="超时 (ms)" prop="timeoutMs">
        <div data-testid="notification-channel-dialog-timeout">
          <el-input-number
            v-model="timeoutMs"
            :min="500"
            :max="30000"
            :step="500"
            controls-position="right"
          />
        </div>
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button
        :disabled="submitting"
        data-testid="notification-channel-dialog-cancel"
        @click="close"
      >
        取消
      </el-button>
      <el-button
        type="primary"
        :loading="submitting"
        data-testid="notification-channel-dialog-submit"
        @click="handleSubmit"
      >
        保存
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.notification-channel-secret-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary, #909399);
  margin-top: 4px;
}
</style>