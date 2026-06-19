import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mount, flushPromises, enableAutoUnmount } from '@vue/test-utils';
import { createRouter, createMemoryHistory, type Router } from 'vue-router';
import ElementPlus from 'element-plus';

// Element Plus's ElMessageBox / ElMessage are imported as named exports
// from 'element-plus'. We stub them at module-load time so the page can
// keep calling them as `ElMessageBox.confirm(...)` / `ElMessage.error(...)`
// in unit tests. The page never depends on the real DOM dialog/messages.
//
// vi.hoisted() is required because vi.mock factories are hoisted to the
// top of the file — any const declared at module top-level would not be
// initialized yet when the factory runs.
const elementPlusMocks = vi.hoisted(() => ({
  elMessageBoxConfirmMock: vi.fn(async () => 'confirm'),
  elMessageInfoMock: vi.fn(),
  elMessageErrorMock: vi.fn(),
  elMessageSuccessMock: vi.fn(),
  elMessageWarningMock: vi.fn(),
}));

vi.mock('element-plus', async () => {
  const actual = await vi.importActual<typeof import('element-plus')>('element-plus');
  return {
    ...actual,
    ElMessage: {
      info: elementPlusMocks.elMessageInfoMock,
      error: elementPlusMocks.elMessageErrorMock,
      success: elementPlusMocks.elMessageSuccessMock,
      warning: elementPlusMocks.elMessageWarningMock,
    },
    ElMessageBox: {
      confirm: elementPlusMocks.elMessageBoxConfirmMock,
    },
  };
});

// Import the page AFTER vi.mock so it picks up the stubs.
import NotificationSettingsPage from './index.vue';
import * as notificationApi from '@/api/notification';
import { ApiError } from '@/api/client';
import type {
  NotificationChannel,
  NotificationSettings,
  NotificationTestRecordSummary,
} from '@/types/notification';

enableAutoUnmount(afterEach);

function buildChannel(overrides: Partial<NotificationChannel> = {}): NotificationChannel {
  return {
    id: 'ops-default',
    type: 'WEBHOOK',
    enabled: true,
    url: 'https://example.com/hook',
    secretConfigured: true,
    timeoutMs: 3000,
    version: 7,
    createdAt: '2026-06-12T10:00:00',
    updatedAt: '2026-06-15T11:00:00',
    ...overrides,
  };
}

function buildSettings(
  overrides: Partial<NotificationSettings> = {},
): NotificationSettings {
  return {
    enabled: true,
    dryRun: false,
    version: 3,
    channels: [
      buildChannel({ id: 'wh-1', type: 'WEBHOOK' }),
      buildChannel({ id: 'feishu-oncall', type: 'FEISHU', secretConfigured: false }),
    ],
    ...overrides,
  };
}

function buildRouter(initialPath = '/notification-settings'): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', redirect: '/notification-settings' },
      {
        path: '/notification-settings',
        name: 'notification-settings',
        component: { template: '<div />' },
      },
    ],
  });
}

interface MountOptions {
  initial?: NotificationSettings | 'reject';
  updateReject?: boolean;
  createReject?: boolean;
  updateChannelReject?: boolean;
  deleteChannelReject?: boolean;
  testChannel?: 'success' | 'failed' | 'reject';
  testRecords?: NotificationTestRecordSummary[];
}

async function mountPage(options: MountOptions = {}) {
  const router = buildRouter();
  await router.push('/notification-settings');
  await router.isReady();

  if (options.initial === 'reject') {
    vi.spyOn(notificationApi, 'getSettings').mockRejectedValue(
      new ApiError({ code: 500, message: '通知设置加载失败', data: null }),
    );
  } else {
    vi.spyOn(notificationApi, 'getSettings').mockResolvedValue(
      options.initial ?? buildSettings(),
    );
  }

  if (options.updateReject) {
    vi.spyOn(notificationApi, 'updateSettings').mockRejectedValue(
      new ApiError({
        code: 409,
        message: '通知设置已被其他操作修改，请重新加载后重试',
        data: null,
        httpStatus: 409,
      }),
    );
  } else {
    vi.spyOn(notificationApi, 'updateSettings').mockImplementation(
      async (payload) => ({
        ...buildSettings(),
        enabled: payload.enabled,
        dryRun: payload.dryRun,
        version: payload.version + 1,
      }),
    );
  }

  if (options.createReject) {
    vi.spyOn(notificationApi, 'createChannel').mockRejectedValue(
      new ApiError({ code: 409, message: '版本冲突', data: null, httpStatus: 409 }),
    );
  } else {
    vi.spyOn(notificationApi, 'createChannel').mockImplementation(
      async (payload) =>
        buildChannel({
          id: payload.channelId,
          type: payload.type,
          enabled: payload.enabled,
          url: payload.url,
          secretConfigured: Boolean(payload.secret),
          timeoutMs: payload.timeoutMs,
          version: 1,
        }),
    );
  }

  if (options.updateChannelReject) {
    vi.spyOn(notificationApi, 'updateChannel').mockRejectedValue(
      new ApiError({ code: 409, message: '版本冲突', data: null, httpStatus: 409 }),
    );
  } else {
    vi.spyOn(notificationApi, 'updateChannel').mockImplementation(
      async (channelId, payload) =>
        buildChannel({
          id: channelId,
          type: payload.type,
          enabled: payload.enabled,
          url: payload.url,
          secretConfigured: payload.clearSecret ? false : true,
          timeoutMs: payload.timeoutMs,
          version: payload.version + 1,
        }),
    );
  }

  if (options.deleteChannelReject) {
    vi.spyOn(notificationApi, 'deleteChannel').mockRejectedValue(
      new ApiError({ code: 409, message: '版本冲突', data: null, httpStatus: 409 }),
    );
  } else {
    vi.spyOn(notificationApi, 'deleteChannel').mockResolvedValue();
  }

  // testChannel / getRecentTestRecords (P1-01 Task 7)
  if (options.testChannel === 'success') {
    vi.spyOn(notificationApi, 'testChannel').mockResolvedValue({
      recordId: 'rec-ok',
      channelId: 'wh-1',
      eventType: 'TEST',
      status: 'SENT',
      responseCode: 200,
      errorMessage: null,
      sentAt: '2026-06-19T10:00:00',
      durationMs: 120,
    });
  } else if (options.testChannel === 'failed') {
    vi.spyOn(notificationApi, 'testChannel').mockResolvedValue({
      recordId: 'rec-fail',
      channelId: 'wh-1',
      eventType: 'TEST',
      status: 'FAILED',
      responseCode: 500,
      errorMessage: 'HTTP 500',
      sentAt: '2026-06-19T10:00:00',
      durationMs: 220,
    });
  } else if (options.testChannel === 'reject') {
    vi.spyOn(notificationApi, 'testChannel').mockRejectedValue(
      new ApiError({ code: 400, message: 'channel not found', data: null, httpStatus: 400 }),
    );
  } else {
    vi.spyOn(notificationApi, 'testChannel').mockResolvedValue({
      recordId: 'rec-ok',
      channelId: 'wh-1',
      eventType: 'TEST',
      status: 'SENT',
      responseCode: 200,
      errorMessage: null,
      sentAt: '2026-06-19T10:00:00',
      durationMs: 120,
    });
  }
  vi.spyOn(notificationApi, 'getRecentTestRecords').mockResolvedValue(
    options.testRecords ?? [
      {
        recordId: 'rec-1',
        channelId: 'wh-1',
        eventType: 'TEST',
        status: 'SENT',
        responseCode: 200,
        sentAt: '2026-06-19T10:00:00',
        durationMs: 120,
      },
    ],
  );

  const wrapper = mount(NotificationSettingsPage, {
    global: {
      plugins: [router, ElementPlus],
    },
    attachTo: document.body,
  });

  await flushPromises();
  return { wrapper, router };
}

beforeEach(() => {
  elementPlusMocks.elMessageBoxConfirmMock.mockReset();
  elementPlusMocks.elMessageBoxConfirmMock.mockResolvedValue('confirm');
  elementPlusMocks.elMessageInfoMock.mockReset();
  elementPlusMocks.elMessageErrorMock.mockReset();
  elementPlusMocks.elMessageSuccessMock.mockReset();
  elementPlusMocks.elMessageWarningMock.mockReset();
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('NotificationSettings page — initial load', () => {
  it('calls getSettings on mount', async () => {
    const spy = vi
      .spyOn(notificationApi, 'getSettings')
      .mockResolvedValue(buildSettings());
    const router = buildRouter();
    await router.push('/notification-settings');
    await router.isReady();

    mount(NotificationSettingsPage, {
      global: { plugins: [router, ElementPlus] },
    });
    await flushPromises();

    expect(spy).toHaveBeenCalledTimes(1);
  });

  it('renders both channel rows with id, type, url and a 「未配置」 tag for secretConfigured=false', async () => {
    const { wrapper } = await mountPage();
    const rows = wrapper.findAll(
      '[data-testid^="notification-channel-row-"]:not([data-testid$="-edit"]):not([data-testid$="-delete"]):not([data-testid$="-test"]):not([data-testid$="-last-test"])',
    );
    expect(rows.length).toBe(2);

    // The data-testid is on the inner id span. Climb to the parent <tr> so we
    // assert against the full row content (every cell, including type/url).
    const wh1Span = wrapper.find('[data-testid="notification-channel-row-wh-1"]');
    expect(wh1Span.exists()).toBe(true);
    const wh1Row = wh1Span.element.closest('tr');
    expect(wh1Row).not.toBeNull();
    expect(wh1Row!.textContent ?? '').toContain('wh-1');
    expect(wh1Row!.textContent ?? '').toContain('Webhook');
    expect(wh1Row!.textContent ?? '').toContain('https://example.com/hook');

    const feishuSpan = wrapper.find('[data-testid="notification-channel-row-feishu-oncall"]');
    expect(feishuSpan.exists()).toBe(true);
    const feishuRow = feishuSpan.element.closest('tr');
    expect(feishuRow).not.toBeNull();
    expect(feishuRow!.textContent ?? '').toContain('飞书');
    // secretConfigured=false → must show 「未配置」, never an existing secret.
    expect(feishuRow!.textContent ?? '').toContain('未配置');
  });

  it('shows a local error inside the page when getSettings rejects', async () => {
    const { wrapper } = await mountPage({ initial: 'reject' });
    expect(wrapper.find('[data-testid="notification-settings-error"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('通知设置加载失败');
  });
});

describe('NotificationSettings page — global toggles', () => {
  it('PUTs updateSettings when the enabled switch is flipped', async () => {
    const { wrapper } = await mountPage();
    const updateSpy = vi.mocked(notificationApi.updateSettings);
    updateSpy.mockClear();

    const enabledSwitch = wrapper.find(
      '[data-testid="notification-settings-enabled"] .el-switch',
    );
    expect(enabledSwitch.exists()).toBe(true);
    await enabledSwitch.trigger('click');
    await flushPromises();

    expect(updateSpy).toHaveBeenCalledTimes(1);
    const arg = updateSpy.mock.calls[0]?.[0];
    expect(arg?.enabled).toBe(false);
    expect(arg?.dryRun).toBe(false);
    expect(arg?.version).toBe(3);
  });

  it('re-fetches settings and shows an error toast when updateSettings rejects with 409', async () => {
    const { wrapper } = await mountPage({ updateReject: true });
    const getSpy = vi.mocked(notificationApi.getSettings);
    getSpy.mockClear();

    const enabledSwitch = wrapper.find(
      '[data-testid="notification-settings-enabled"] .el-switch',
    );
    await enabledSwitch.trigger('click');
    await flushPromises();

    // 409 → page must auto-reload via getSettings and surface a message.
    expect(getSpy).toHaveBeenCalled();
    expect(elementPlusMocks.elMessageErrorMock).toHaveBeenCalled();
  });
});

describe('NotificationSettings page — channel create', () => {
  it('opens the dialog when 「新建通道」 is clicked', async () => {
    const { wrapper } = await mountPage();
    const addBtn = wrapper.find('[data-testid="notification-channel-add"]');
    expect(addBtn.exists()).toBe(true);
    await addBtn.trigger('click');
    await flushPromises();
    // el-dialog teleports its body to document.body by default — query body directly.
    const dialog = document.body.querySelector(
      '[data-testid="notification-channel-dialog"]',
    );
    expect(dialog).not.toBeNull();
    // Dialog is for create — channel id field is editable (not readonly).
    const channelIdInput = document.body.querySelector(
      '[data-testid="notification-channel-dialog-channel-id"] input',
    ) as HTMLInputElement | null;
    expect(channelIdInput).not.toBeNull();
    expect(channelIdInput?.readOnly).toBe(false);
  });

  it('calls createChannel with the form values when 保存 is clicked in the dialog', async () => {
    const { wrapper } = await mountPage();
    const createSpy = vi.mocked(notificationApi.createChannel);
    createSpy.mockClear();

    await wrapper.find('[data-testid="notification-channel-add"]').trigger('click');
    await flushPromises();

    const setInputValue = async (
      testid: string,
      value: string,
    ): Promise<void> => {
      const input = document.body.querySelector(
        `${testid} input`,
      ) as HTMLInputElement | null;
      expect(input, `input for ${testid}`).not.toBeNull();
      input!.value = value;
      input!.dispatchEvent(new Event('input', { bubbles: true }));
      input!.dispatchEvent(new Event('change', { bubbles: true }));
    };

    await setInputValue(
      '[data-testid="notification-channel-dialog-channel-id"]',
      'feishu-new',
    );
    await setInputValue(
      '[data-testid="notification-channel-dialog-url"]',
      'https://open.feishu.cn/hook/xyz',
    );
    await setInputValue(
      '[data-testid="notification-channel-dialog-secret"]',
      'sec_new',
    );
    await setInputValue(
      '[data-testid="notification-channel-dialog-timeout"]',
      '5000',
    );
    await flushPromises();

    // Submit button is inside the dialog footer (still inside the dialog component tree).
    const submitBtn = document.body.querySelector(
      '[data-testid="notification-channel-dialog-submit"]',
    ) as HTMLButtonElement | null;
    expect(submitBtn).not.toBeNull();
    submitBtn!.click();
    await flushPromises();

    expect(createSpy).toHaveBeenCalledTimes(1);
    const arg = createSpy.mock.calls[0]?.[0];
    expect(arg?.channelId).toBe('feishu-new');
    expect(arg?.url).toBe('https://open.feishu.cn/hook/xyz');
    expect(arg?.secret).toBe('sec_new');
    expect(arg?.timeoutMs).toBe(5000);
    expect(arg?.type).toBe('WEBHOOK'); // default type when dialog first opens
  });
});

describe('NotificationSettings page — channel edit', () => {
  it('opens the dialog pre-filled with the channel row values (secret field is EMPTY)', async () => {
    const { wrapper } = await mountPage();
    const editBtn = wrapper.find(
      '[data-testid="notification-channel-row-wh-1-edit"]',
    );
    expect(editBtn.exists()).toBe(true);
    await editBtn.trigger('click');
    await flushPromises();

    const dialog = document.body.querySelector(
      '[data-testid="notification-channel-dialog"]',
    );
    expect(dialog).not.toBeNull();

    // Channel id is derived from the row; dialog shows it read-only.
    const channelIdInput = document.body.querySelector(
      '[data-testid="notification-channel-dialog-channel-id"] input',
    ) as HTMLInputElement | null;
    expect(channelIdInput).not.toBeNull();
    expect(channelIdInput?.value).toBe('wh-1');
    expect(channelIdInput?.readOnly).toBe(true);

    // URL preserved.
    const urlInput = document.body.querySelector(
      '[data-testid="notification-channel-dialog-url"] input',
    ) as HTMLInputElement | null;
    expect(urlInput).not.toBeNull();
    expect(urlInput?.value).toBe('https://example.com/hook');

    // SECRET FIELD MUST BE EMPTY when editing — never echo the stored value.
    const secretInput = document.body.querySelector(
      '[data-testid="notification-channel-dialog-secret"] input',
    ) as HTMLInputElement | null;
    expect(secretInput).not.toBeNull();
    expect(secretInput?.value).toBe('');
  });

  it('calls updateChannel with the row version and form values when 保存 is clicked', async () => {
    const { wrapper } = await mountPage();
    const updateSpy = vi.mocked(notificationApi.updateChannel);
    updateSpy.mockClear();

    await wrapper
      .find('[data-testid="notification-channel-row-wh-1-edit"]')
      .trigger('click');
    await flushPromises();

    const urlInput = document.body.querySelector(
      '[data-testid="notification-channel-dialog-url"] input',
    ) as HTMLInputElement | null;
    expect(urlInput).not.toBeNull();
    urlInput!.value = 'https://example.com/hook-v2';
    urlInput!.dispatchEvent(new Event('input', { bubbles: true }));
    urlInput!.dispatchEvent(new Event('change', { bubbles: true }));
    await flushPromises();

    const submitBtn = document.body.querySelector(
      '[data-testid="notification-channel-dialog-submit"]',
    ) as HTMLButtonElement | null;
    expect(submitBtn).not.toBeNull();
    submitBtn!.click();
    await flushPromises();

    expect(updateSpy).toHaveBeenCalledTimes(1);
    const call = updateSpy.mock.calls[0];
    expect(call?.[0]).toBe('wh-1');
    const arg = call?.[1];
    expect(arg?.url).toBe('https://example.com/hook-v2');
    expect(arg?.version).toBe(7); // row's stored version
  });
});

describe('NotificationSettings page — channel delete', () => {
  it('asks for confirmation via ElMessageBox before calling deleteChannel', async () => {
    const { wrapper } = await mountPage();
    const deleteSpy = vi.mocked(notificationApi.deleteChannel);
    deleteSpy.mockClear();

    const deleteBtn = wrapper.find(
      '[data-testid="notification-channel-row-wh-1-delete"]',
    );
    await deleteBtn.trigger('click');
    await flushPromises();

    // Confirmation required before delete fires.
    expect(elementPlusMocks.elMessageBoxConfirmMock).toHaveBeenCalledTimes(1);
    expect(deleteSpy).toHaveBeenCalledTimes(1);
    expect(deleteSpy.mock.calls[0]).toEqual(['wh-1', 7]);
  });

  it('does NOT call deleteChannel when the user cancels the confirmation', async () => {
    elementPlusMocks.elMessageBoxConfirmMock.mockReset();
    elementPlusMocks.elMessageBoxConfirmMock.mockRejectedValueOnce(new Error('cancel'));
    const { wrapper } = await mountPage();
    const deleteSpy = vi.mocked(notificationApi.deleteChannel);
    deleteSpy.mockClear();

    await wrapper
      .find('[data-testid="notification-channel-row-wh-1-delete"]')
      .trigger('click');
    await flushPromises();

    expect(elementPlusMocks.elMessageBoxConfirmMock).toHaveBeenCalledTimes(1);
    expect(deleteSpy).not.toHaveBeenCalled();
  });

  it('shows an error message and re-fetches when deleteChannel rejects with 409', async () => {
    const { wrapper } = await mountPage({ deleteChannelReject: true });
    const getSpy = vi.mocked(notificationApi.getSettings);
    getSpy.mockClear();

    await wrapper
      .find('[data-testid="notification-channel-row-wh-1-delete"]')
      .trigger('click');
    await flushPromises();

    expect(elementPlusMocks.elMessageErrorMock).toHaveBeenCalled();
    expect(getSpy).toHaveBeenCalled();
  });
});

// =====================================================================
// P1-01 Task 7 — 连接测试
// =====================================================================

describe('NotificationSettings page — channel test button', () => {
  it('renders a 测试 button for every channel row', async () => {
    const { wrapper } = await mountPage();
    const wh1TestBtn = wrapper.find(
      '[data-testid="notification-channel-row-wh-1-test"]',
    );
    const feishuTestBtn = wrapper.find(
      '[data-testid="notification-channel-row-feishu-oncall-test"]',
    );
    expect(wh1TestBtn.exists()).toBe(true);
    expect(feishuTestBtn.exists()).toBe(true);
    expect(wh1TestBtn.text()).toContain('测试');
  });

  it('clicking 测试 calls testChannel with the row channelId and refreshes records on success', async () => {
    const { wrapper } = await mountPage({ testChannel: 'success' });
    const testSpy = vi.mocked(notificationApi.testChannel);
    testSpy.mockClear();
    const recordsSpy = vi.mocked(notificationApi.getRecentTestRecords);
    recordsSpy.mockClear();

    await wrapper
      .find('[data-testid="notification-channel-row-wh-1-test"]')
      .trigger('click');
    await flushPromises();

    expect(testSpy).toHaveBeenCalledTimes(1);
    const arg = testSpy.mock.calls[0]?.[0] as Record<string, unknown>;
    expect(arg.channelId).toBe('wh-1');

    // Success → ElMessage.success was called + recent-records refresh fired
    expect(elementPlusMocks.elMessageSuccessMock).toHaveBeenCalled();
    expect(recordsSpy).toHaveBeenCalled();
  });

  it('external 5xx surfaces as 失败 toast and refreshes the records panel', async () => {
    const { wrapper } = await mountPage({ testChannel: 'failed' });

    await wrapper
      .find('[data-testid="notification-channel-row-wh-1-test"]')
      .trigger('click');
    await flushPromises();

    expect(elementPlusMocks.elMessageErrorMock).toHaveBeenCalled();
    // Records panel still refreshes even on FAILED — users want the latest history.
    expect(notificationApi.getRecentTestRecords).toHaveBeenCalled();
  });

  it('rejects with toast and no records refresh on transport/business error', async () => {
    const { wrapper } = await mountPage({ testChannel: 'reject' });
    const recordsSpy = vi.mocked(notificationApi.getRecentTestRecords);
    recordsSpy.mockClear();

    await wrapper
      .find('[data-testid="notification-channel-row-wh-1-test"]')
      .trigger('click');
    await flushPromises();

    expect(elementPlusMocks.elMessageErrorMock).toHaveBeenCalled();
    // transport/business error → we do NOT refresh records (would only show old data)
    expect(recordsSpy).not.toHaveBeenCalled();
  });

  it('row shows 「上次测试: 成功 / 失败」 badge when lastTestResult is present', async () => {
    const lastTest = {
      recordId: 'rec-1',
      channelId: 'wh-1',
      eventType: 'TEST' as const,
      status: 'SENT' as const,
      responseCode: 200,
      sentAt: '2026-06-19T10:00:00',
      durationMs: 120,
    };
    const settings = buildSettings({
      channels: [
        buildChannel({ id: 'wh-1', type: 'WEBHOOK', lastTestResult: lastTest }),
        buildChannel({ id: 'feishu-oncall', type: 'FEISHU' }),
      ],
    });
    const { wrapper } = await mountPage({ initial: settings });

    const wh1Span = wrapper.find('[data-testid="notification-channel-row-wh-1"]');
    const wh1Row = wh1Span.element.closest('tr');
    expect(wh1Row?.textContent ?? '').toContain('上次测试');
    expect(wh1Row?.textContent ?? '').toContain('成功');

    const feishuSpan = wrapper.find(
      '[data-testid="notification-channel-row-feishu-oncall"]',
    );
    const feishuRow = feishuSpan.element.closest('tr');
    expect(feishuRow?.textContent ?? '').not.toContain('上次测试');
  });
});

describe('NotificationSettings page — recent test records panel', () => {
  it('renders the panel section with at least one record row', async () => {
    const { wrapper } = await mountPage({
      testRecords: [
        {
          recordId: 'rec-1',
          channelId: 'wh-1',
          eventType: 'TEST',
          status: 'SENT',
          responseCode: 200,
          sentAt: '2026-06-19T10:00:00',
          durationMs: 120,
        },
        {
          recordId: 'rec-2',
          channelId: 'feishu-oncall',
          eventType: 'TEST',
          status: 'FAILED',
          responseCode: 500,
          errorMessage: 'HTTP 500',
          sentAt: '2026-06-19T09:00:00',
          durationMs: 220,
        },
      ],
    });

    const panel = wrapper.find('[data-testid="notification-test-records-panel"]');
    expect(panel.exists()).toBe(true);

    const rows = wrapper.findAll('[data-testid^="notification-test-record-row-"]');
    expect(rows.length).toBe(2);
    expect(panel.text()).toContain('成功');
    expect(panel.text()).toContain('失败');
  });

  it('renders an empty hint when there are no test records', async () => {
    const { wrapper } = await mountPage({ testRecords: [] });
    const panel = wrapper.find('[data-testid="notification-test-records-panel"]');
    expect(panel.exists()).toBe(true);
    expect(panel.text()).toContain('暂无');
  });
});