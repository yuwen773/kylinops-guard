<script setup lang="ts">
// NotificationSettings — Phase P1-01 Task 6 + Task 7.
//
// Responsibilities:
//   * Global switches: enabled + dryRun, with optimistic-lock version.
//   * Channel table: id / type / enabled / url / secretConfigured / timeoutMs
//     with edit / delete / test actions + 「上次测试」 badge.
//   * Channel create/edit dialog (ChannelEditDialog.vue).
//   * "最近测试记录" panel below the table — most recent 20 TEST records.
//   * Each write goes through @/api/notification (see types/notification.ts
//     for the secret contract — never echoed back from the server).
//   * Independent loading/error state per section; a failure in one does
//     not block the others from rendering.
//
// SAFETY CONTRACT — secret handling:
//   * secretConfigured=true is rendered as 「已配置」 (a tag), never the
//     secret value. There is NO path in this module that reads the actual
//     secret — the wire shape does not carry it.
//   * On edit the dialog opens with an EMPTY secret field. If the user
//     wants to change it they type a new value; if they leave it empty
//     AND don't toggle "清除已存密钥", the server keeps the stored value.
//   * Conflict (409) on any write → page re-fetches getSettings() and
//     surfaces an ElMessage.error so the user can retry against the new
//     version. We never silently swallow conflicts.

import { computed, onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  createChannel,
  deleteChannel,
  getRecentTestRecords,
  getSettings,
  testChannel,
  updateChannel,
  updateSettings,
} from '@/api/notification';
import { ApiError } from '@/api/client';
import type {
  ChannelForm,
  NotificationChannel,
  NotificationSettings,
  NotificationTestRecordSummary,
} from '@/types/notification';
import ChannelEditDialog from './ChannelEditDialog.vue';

// ── state ────────────────────────────────────────────────────────────────

const settings = ref<NotificationSettings | null>(null);
const loading = ref(false);
const loadError = ref<string | null>(null);

// Per-write loaders so a slow save doesn't lock the page.
const settingsSaving = ref(false);
const channelSaving = ref(false);
const channelDeletingId = ref<string | null>(null);
const channelTestingId = ref<string | null>(null);

const dialogOpen = ref(false);
const dialogMode = ref<'create' | 'edit'>('create');
const dialogChannel = ref<NotificationChannel | null>(null);

// Test records panel state (P1-01 Task 7)
const testRecords = ref<NotificationTestRecordSummary[]>([]);
const testRecordsLoading = ref(false);
const testRecordsError = ref<string | null>(null);

// ── derived ──────────────────────────────────────────────────────────────

const channels = computed<NotificationChannel[]>(
  () => settings.value?.channels ?? [],
);

const isHttpStatusConflict = (err: unknown): boolean => {
  if (err instanceof ApiError) {
    return err.httpStatus === 409 || err.code === 409;
  }
  return false;
};

const conflictMessage = (action: string): string =>
  `配置已被其他操作修改（${action}），请在最新版本上重试`;

// ── loaders ──────────────────────────────────────────────────────────────

async function refresh(): Promise<void> {
  loading.value = true;
  loadError.value = null;
  try {
    settings.value = await getSettings();
  } catch (err) {
    const e = err as ApiError;
    loadError.value = e?.message ?? '通知设置加载失败';
    settings.value = null;
  } finally {
    loading.value = false;
  }
  // Always refresh the recent-records panel too — it is independent of the
  // settings/channels list and is cheap (≤ 20 records).
  await refreshTestRecords();
}

async function refreshTestRecords(): Promise<void> {
  testRecordsLoading.value = true;
  testRecordsError.value = null;
  try {
    testRecords.value = await getRecentTestRecords();
  } catch (err) {
    const e = err as ApiError;
    testRecordsError.value = e?.message ?? '最近测试记录加载失败';
    testRecords.value = [];
  } finally {
    testRecordsLoading.value = false;
  }
}

onMounted(refresh);

// ── global toggles ───────────────────────────────────────────────────────

async function onEnabledChange(next: boolean): Promise<void> {
  if (!settings.value) return;
  const currentVersion = settings.value.version;
  const previous = settings.value.enabled;
  settings.value.enabled = next; // optimistic
  settingsSaving.value = true;
  try {
    const updated = await updateSettings({
      enabled: next,
      dryRun: settings.value.dryRun,
      version: currentVersion,
    });
    settings.value = updated;
    ElMessage.success('通知总开关已更新');
  } catch (err) {
    settings.value.enabled = previous; // revert
    if (isHttpStatusConflict(err)) {
      ElMessage.error(conflictMessage('切换总开关'));
      await refresh();
    } else {
      const e = err as ApiError;
      ElMessage.error(e?.message ?? '总开关更新失败');
    }
  } finally {
    settingsSaving.value = false;
  }
}

async function onDryRunChange(next: boolean): Promise<void> {
  if (!settings.value) return;
  const currentVersion = settings.value.version;
  const previous = settings.value.dryRun;
  settings.value.dryRun = next;
  settingsSaving.value = true;
  try {
    const updated = await updateSettings({
      enabled: settings.value.enabled,
      dryRun: next,
      version: currentVersion,
    });
    settings.value = updated;
    ElMessage.success('Dry-Run 模式已更新');
  } catch (err) {
    settings.value.dryRun = previous;
    if (isHttpStatusConflict(err)) {
      ElMessage.error(conflictMessage('切换 Dry-Run'));
      await refresh();
    } else {
      const e = err as ApiError;
      ElMessage.error(e?.message ?? 'Dry-Run 更新失败');
    }
  } finally {
    settingsSaving.value = false;
  }
}

// ── channel table actions ────────────────────────────────────────────────

function openCreate(): void {
  dialogMode.value = 'create';
  dialogChannel.value = null;
  dialogOpen.value = true;
}

function openEdit(row: NotificationChannel): void {
  dialogMode.value = 'edit';
  dialogChannel.value = row;
  dialogOpen.value = true;
}

async function handleDialogSubmit(form: ChannelForm): Promise<void> {
  channelSaving.value = true;
  try {
    if (dialogMode.value === 'create') {
      await createChannel({
        channelId: form.channelId ?? '',
        type: form.type,
        enabled: form.enabled,
        url: form.url,
        secret: form.secret,
        clearSecret: form.clearSecret,
        timeoutMs: form.timeoutMs,
      });
    } else {
      const id = dialogChannel.value?.id;
      if (!id) throw new Error('缺少通道 ID');
      await updateChannel(id, {
        type: form.type,
        enabled: form.enabled,
        url: form.url,
        secret: form.secret,
        clearSecret: form.clearSecret,
        timeoutMs: form.timeoutMs,
        version: form.version ?? 0,
      });
    }
    await refresh();
  } finally {
    channelSaving.value = false;
  }
}

async function confirmDelete(row: NotificationChannel): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认删除通道「${row.id}」？删除后无法恢复。`,
      '删除通道',
      {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消',
      },
    );
  } catch {
    // user cancelled — ElMessageBox rejects on cancel
    return;
  }

  channelDeletingId.value = row.id;
  try {
    await deleteChannel(row.id, row.version);
    ElMessage.success(`通道「${row.id}」已删除`);
    await refresh();
  } catch (err) {
    if (isHttpStatusConflict(err)) {
      ElMessage.error(conflictMessage('删除通道'));
      await refresh();
    } else {
      const e = err as ApiError;
      ElMessage.error(e?.message ?? '删除失败');
    }
  } finally {
    channelDeletingId.value = null;
  }
}

// ── connection test (P1-01 Task 7) ──────────────────────────────────────

async function testChannelByRow(row: NotificationChannel): Promise<void> {
  channelTestingId.value = row.id;
  try {
    const result = await testChannel({ channelId: row.id });
    if (result.status === 'SENT') {
      ElMessage.success(`通道「${row.id}」测试成功（${result.durationMs} ms）`);
    } else {
      ElMessage.error(
        `通道「${row.id}」测试失败：${result.errorMessage ?? `HTTP ${result.responseCode ?? '?'}`}`,
      );
    }
    // SENT/FAILED 都是预期结果,刷新列表与最近测试记录面板
    await refresh();
  } catch (err) {
    const e = err as ApiError;
    ElMessage.error(e?.message ?? '测试请求失败');
  } finally {
    channelTestingId.value = null;
  }
}

// ── presentation helpers ────────────────────────────────────────────────

const typeLabel: Record<NotificationChannel['type'], string> = {
  WEBHOOK: 'Webhook',
  FEISHU: '飞书',
};

function formatTimestamp(iso: string | undefined): string {
  if (!iso) return '—';
  return iso.replace('T', ' ').slice(0, 19);
}

function lastTestLabel(row: NotificationChannel): {
  text: string;
  type: 'success' | 'danger';
} | null {
  const t = row.lastTestResult;
  if (!t) return null;
  return {
    text: t.status === 'SENT' ? '成功' : '失败',
    type: t.status === 'SENT' ? 'success' : 'danger',
  };
}
</script>

<template>
  <div class="notification-settings-page" data-testid="notification-settings-page">
    <header class="notification-settings-header">
      <h1 class="notification-settings-title">通知配置</h1>
      <p class="notification-settings-subtitle">
        管理通知发送开关、Dry-Run 模式以及通知通道（Webhook / 飞书）。
        所有写操作通过乐观锁保证一致性，密钥写入后不可回显。
      </p>
    </header>

    <!--
      飞书卡片「查看审计详情」按钮依赖部署侧 KYLINOPS_PUBLIC_BASE_URL。
      该变量当前无前端查询 API，因此固定展示一条 info 提示；
      部署侧已正确配置时按钮仍正常渲染，本提示不会误导行为。
    -->
    <el-alert
      class="notification-settings-public-link-hint"
      type="info"
      :closable="false"
      show-icon
      data-testid="notification-settings-public-link-hint"
      title="飞书卡片「查看审计详情」按钮依赖部署侧公网 URL"
      description="后端环境变量 KYLINOPS_PUBLIC_BASE_URL 未配置时按钮将不出现在飞书卡片中（启动期日志 warn 一次）。生产部署请设置为飞书用户能访问的公网 https 地址；dev / 演示可不配。详见 docs/deploy/install-and-deploy-guide.md §2.4。"
    />

    <!-- Global toggles -->
    <section class="notification-settings-section" data-testid="notification-settings-global">
      <h2 class="notification-settings-section-title">全局设置</h2>
      <div v-if="loading && !settings" class="notification-settings-loading">加载中…</div>
      <div
        v-else-if="loadError"
        class="notification-settings-error"
        data-testid="notification-settings-error"
      >
        <span>加载失败：{{ loadError }}</span>
        <el-button size="small" @click="refresh">重试</el-button>
      </div>
      <div v-else-if="settings" class="notification-settings-global-grid">
        <div class="notification-settings-toggle">
          <span class="notification-settings-toggle-label">启用通知</span>
          <span class="notification-settings-toggle-hint">
            关闭后所有通知事件仅记录，不实际发送。
          </span>
          <div data-testid="notification-settings-enabled">
            <el-switch
              :model-value="settings.enabled"
              :loading="settingsSaving"
              @update:model-value="(v: boolean | string | number) => onEnabledChange(Boolean(v))"
            />
          </div>
        </div>
        <div class="notification-settings-toggle">
          <span class="notification-settings-toggle-label">Dry-Run 模式</span>
          <span class="notification-settings-toggle-hint">
            开启后通知事件按真实链路发送，但显式标记为演练，便于回归验证。
          </span>
          <div data-testid="notification-settings-dry-run">
            <el-switch
              :model-value="settings.dryRun"
              :loading="settingsSaving"
              @update:model-value="(v: boolean | string | number) => onDryRunChange(Boolean(v))"
            />
          </div>
        </div>
        <div class="notification-settings-version">
          <span class="notification-settings-toggle-label">配置版本</span>
          <span class="notification-settings-version-value">v{{ settings.version }}</span>
        </div>
      </div>
    </section>

    <!-- Channel list -->
    <section class="notification-settings-section" data-testid="notification-settings-channels">
      <div class="notification-settings-section-header">
        <h2 class="notification-settings-section-title">通知通道</h2>
        <el-button
          type="primary"
          :loading="channelSaving"
          data-testid="notification-channel-add"
          @click="openCreate"
        >
          新建通道
        </el-button>
      </div>

      <div v-if="loading && channels.length === 0" class="notification-settings-loading">
        加载通道中…
      </div>
      <div
        v-else-if="!loading && channels.length === 0 && !loadError"
        class="notification-settings-empty"
        data-testid="notification-settings-empty"
      >
        尚未配置任何通知通道，点击右上角「新建通道」开始。
      </div>
      <el-table
        v-else
        :data="channels"
        row-key="id"
        class="notification-settings-table"
        data-testid="notification-settings-table"
      >
        <el-table-column prop="id" label="通道 ID" min-width="160">
          <template #default="{ row }: { row: NotificationChannel }">
            <span
              class="notification-settings-channel-id"
              :data-testid="`notification-channel-row-${row.id}`"
            >{{ row.id }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="type" label="类型" width="100">
          <template #default="{ row }: { row: NotificationChannel }">
            {{ typeLabel[row.type] ?? row.type }}
          </template>
        </el-table-column>
        <el-table-column prop="enabled" label="启用" width="80">
          <template #default="{ row }: { row: NotificationChannel }">
            <el-tag
              :type="row.enabled ? 'success' : 'info'"
              size="small"
              effect="light"
            >
              {{ row.enabled ? '已启用' : '已停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="url" label="URL" min-width="220">
          <template #default="{ row }: { row: NotificationChannel }">
            <code class="notification-settings-url">{{ row.url }}</code>
          </template>
        </el-table-column>
        <el-table-column prop="secretConfigured" label="密钥" width="100">
          <template #default="{ row }: { row: NotificationChannel }">
            <el-tag
              :type="row.secretConfigured ? 'warning' : 'info'"
              size="small"
              effect="plain"
            >
              {{ row.secretConfigured ? '已配置' : '未配置' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="timeoutMs" label="超时 (ms)" width="110" />
        <el-table-column prop="updatedAt" label="更新时间" width="170">
          <template #default="{ row }: { row: NotificationChannel }">
            {{ formatTimestamp(row.updatedAt) }}
          </template>
        </el-table-column>
        <el-table-column label="上次测试" width="110">
          <template #default="{ row }: { row: NotificationChannel }">
            <template v-if="lastTestLabel(row)">
              <span
                class="notification-settings-last-test"
                :data-testid="`notification-channel-row-${row.id}-last-test`"
              >
                <span class="notification-settings-last-test-prefix">上次测试:</span>
                <el-tag
                  :type="lastTestLabel(row)!.type"
                  size="small"
                  effect="light"
                >{{ lastTestLabel(row)!.text }}</el-tag>
              </span>
            </template>
            <span v-else class="notification-settings-last-test-empty">未测试</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }: { row: NotificationChannel }">
            <el-button
              size="small"
              :loading="channelTestingId === row.id"
              :data-testid="`notification-channel-row-${row.id}-test`"
              @click="testChannelByRow(row)"
            >
              测试
            </el-button>
            <el-button
              size="small"
              :data-testid="`notification-channel-row-${row.id}-edit`"
              @click="openEdit(row)"
            >
              编辑
            </el-button>
            <el-button
              size="small"
              type="danger"
              plain
              :loading="channelDeletingId === row.id"
              :data-testid="`notification-channel-row-${row.id}-delete`"
              @click="confirmDelete(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <!-- 最近测试记录 (P1-01 Task 7) -->
    <section
      class="notification-settings-section"
      data-testid="notification-test-records-panel"
    >
      <div class="notification-settings-section-header">
        <h2 class="notification-settings-section-title">最近测试记录</h2>
        <el-button size="small" :loading="testRecordsLoading" @click="refreshTestRecords">
          刷新
        </el-button>
      </div>
      <div v-if="testRecordsLoading && testRecords.length === 0" class="notification-settings-loading">
        加载测试记录中…
      </div>
      <div
        v-else-if="testRecordsError"
        class="notification-settings-error"
        data-testid="notification-test-records-error"
      >
        <span>加载失败：{{ testRecordsError }}</span>
        <el-button size="small" @click="refreshTestRecords">重试</el-button>
      </div>
      <div
        v-else-if="testRecords.length === 0"
        class="notification-settings-empty"
        data-testid="notification-test-records-empty"
      >
        暂无测试记录。点击任一通道的「测试」按钮即可发起一次连接测试。
      </div>
      <el-table
        v-else
        :data="testRecords"
        row-key="recordId"
        class="notification-settings-table"
        data-testid="notification-test-records-table"
      >
        <el-table-column prop="sentAt" label="时间" width="170">
          <template #default="{ row }: { row: NotificationTestRecordSummary }">
            {{ formatTimestamp(row.sentAt) }}
          </template>
        </el-table-column>
        <el-table-column prop="channelId" label="通道" min-width="160">
          <template #default="{ row }: { row: NotificationTestRecordSummary }">
            <span :data-testid="`notification-test-record-row-${row.recordId}`">
              <code class="notification-settings-channel-id">{{ row.channelId }}</code>
            </span>
          </template>
        </el-table-column>
        <el-table-column label="结果" width="100">
          <template #default="{ row }: { row: NotificationTestRecordSummary }">
            <el-tag
              :type="row.status === 'SENT' ? 'success' : 'danger'"
              size="small"
              effect="light"
            >{{ row.status === 'SENT' ? '成功' : '失败' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="responseCode" label="HTTP" width="80" />
        <el-table-column prop="durationMs" label="耗时 (ms)" width="110" />
        <el-table-column prop="errorMessage" label="错误信息" min-width="200">
          <template #default="{ row }: { row: NotificationTestRecordSummary }">
            <span v-if="row.errorMessage" class="notification-settings-test-error">
              {{ row.errorMessage }}
            </span>
            <span v-else>—</span>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <ChannelEditDialog
      v-model="dialogOpen"
      :channel="dialogChannel"
      :mode="dialogMode"
      :on-submit="handleDialogSubmit"
    />
  </div>
</template>

<style scoped>
.notification-settings-page {
  max-width: 1280px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 24px;
  padding: 16px 0 48px;
}

.notification-settings-header {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.notification-settings-title {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  color: #1f2d3d;
}

.notification-settings-subtitle {
  margin: 0;
  font-size: 13px;
  color: #5b6b80;
  line-height: 1.6;
}

.notification-settings-section {
  background: #ffffff;
  border: 1px solid #e6e8eb;
  border-radius: 6px;
  padding: 20px 24px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.notification-settings-section-title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: #1f2d3d;
}

.notification-settings-section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.notification-settings-loading {
  padding: 24px;
  text-align: center;
  color: #8a99b3;
  font-size: 13px;
}

.notification-settings-error {
  padding: 16px;
  border: 1px solid #f56c6c;
  background: #fef0f0;
  color: #c45656;
  border-radius: 4px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.notification-settings-empty {
  padding: 32px;
  text-align: center;
  color: #8a99b3;
  font-size: 13px;
}

.notification-settings-global-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 16px;
}

.notification-settings-toggle {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px 16px;
  border: 1px solid #ebeef5;
  border-radius: 4px;
}

.notification-settings-toggle-label {
  font-size: 13px;
  font-weight: 600;
  color: #1f2d3d;
}

.notification-settings-toggle-hint {
  font-size: 12px;
  color: #8a99b3;
  line-height: 1.5;
}

.notification-settings-version {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px 16px;
  border: 1px solid #ebeef5;
  border-radius: 4px;
}

.notification-settings-version-value {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 16px;
  font-weight: 600;
  color: #409eff;
}

.notification-settings-channel-id {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
  color: #1f2d3d;
}

.notification-settings-url {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
  color: #5b6b80;
  word-break: break-all;
}

.notification-settings-table {
  width: 100%;
}

.notification-settings-last-test {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.notification-settings-last-test-prefix {
  font-size: 12px;
  color: #5b6b80;
}

.notification-settings-last-test-empty {
  font-size: 12px;
  color: #c0c4cc;
}

.notification-settings-test-error {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
  color: #c45656;
  word-break: break-all;
}
</style>