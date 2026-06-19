import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mount, flushPromises, enableAutoUnmount } from '@vue/test-utils';
import { createRouter, createMemoryHistory, type Router } from 'vue-router';
import ElementPlus from 'element-plus';
import AuditLogPage from './index.vue';
import * as auditApi from '@/api/audit';
import { ApiError } from '@/api/client';
import type {
  AuditLogDetail,
  AuditLogPage,
  AuditLogSummary,
} from '@/types/audit';

enableAutoUnmount(afterEach);

/**
 * AuditLog page contract (Task 16 / Task 6):
 *   - On mount: calls getAuditLogs({ page: 0, size: 20 }).
 *   - Pagination: changing page re-calls getAuditLogs with the new page.
 *   - Filters: risk / keyword / status / date range — each re-calls
 *     getAuditLogs with the matching query parameter.
 *   - URL query `?auditId=...` opens the detail drawer automatically.
 *   - Clicking a row opens the drawer and triggers getAuditDetail.
 *   - Detail drawer renders: AuditTimeline + tool failures + risk checks +
 *     pending action + execution result + final answer.
 *   - JSON-like fields (matchedRules, executionResult) are parsed defensively;
 *     on parse failure, the raw sanitized string is rendered instead.
 *   - Loading / error / empty states are all visible.
 */

function buildSummary(overrides: Partial<AuditLogSummary> = {}): AuditLogSummary {
  return {
    auditId: 'audit-1',
    sessionId: 'session-1',
    userInput: '查看系统状态',
    intentType: 'SYSTEM_CHECK',
    riskLevel: 'L0',
    riskDecision: 'ALLOW',
    status: 'SUCCESS',
    toolCallCount: 6,
    createdAt: '2026-06-12T10:00:00',
    ...overrides,
  };
}

function buildPage(overrides: Partial<AuditLogPage> = {}): AuditLogPage {
  return {
    content: [buildSummary()],
    totalElements: 1,
    totalPages: 1,
    number: 0,
    size: 20,
    ...overrides,
  };
}

function buildDetail(overrides: Partial<AuditLogDetail> = {}): AuditLogDetail {
  return {
    auditId: 'audit-1',
    sessionId: 'session-1',
    userInput: '重启 nginx',
    intentType: 'SERVICE_DIAGNOSIS',
    riskLevel: 'L2',
    riskDecision: 'CONFIRM',
    status: 'CONFIRMED',
    confirmationRequired: true,
    confirmationStatus: 'CONFIRMED',
    matchedRules: '["confirm_service_restart"]',
    executionResult: '{"restarted":"nginx","ok":true}',
    finalAnswer: 'nginx 已重启',
    createdAt: '2026-06-12T10:00:00',
    updatedAt: '2026-06-12T10:00:05',
    toolCalls: [
      {
        toolCallId: 't-1',
        toolName: 'service_status_tool',
        status: 'SUCCESS',
        durationMs: 320,
      },
    ],
    riskChecks: [
      {
        riskCheckId: 'r-1',
        targetType: 'command',
        riskLevel: 'L2',
        riskDecision: 'CONFIRM',
        matchedRules: '["confirm_service_restart"]',
        reason: '服务重启需用户确认',
        checkedAt: '2026-06-12T10:00:01',
      },
    ],
    pendingAction: {
      actionId: 'act-1',
      actionType: 'safe_service_restart',
      toolName: 'safe_service_restart',
      status: 'CONFIRMED',
      executionResult: '{"ok":true}',
    },
    ...overrides,
  };
}

function buildRouter(initialPath = '/audit'): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', redirect: '/audit' },
      { path: '/audit', name: 'audit', component: { template: '<div />' } },
    ],
  });
}

async function mountPage(options: {
  router?: Router;
  pageData?: AuditLogPage;
} = {}) {
  const router = options.router ?? buildRouter();
  await router.push(options.router?.currentRoute.value.fullPath ?? '/audit');
  await router.isReady();

  if (options.pageData) {
    vi.spyOn(auditApi, 'getAuditLogs').mockResolvedValue(options.pageData);
  } else {
    vi.spyOn(auditApi, 'getAuditLogs').mockResolvedValue(buildPage());
  }

  // Attach to document.body so el-drawer's teleport target is reachable.
  const wrapper = mount(AuditLogPage, {
    global: { plugins: [router, ElementPlus] },
    attachTo: document.body,
  });
  await flushPromises();
  return { wrapper, router };
}

describe('AuditLog page — list + filters', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('calls getAuditLogs on mount with default page=0 and size=20', async () => {
    const spy = vi.spyOn(auditApi, 'getAuditLogs').mockResolvedValue(buildPage());
    const router = buildRouter();
    await router.push('/audit');
    await router.isReady();
    const wrapper = mount(AuditLogPage, {
      global: { plugins: [router, ElementPlus] },
    });
    await flushPromises();

    expect(spy).toHaveBeenCalledTimes(1);
    const arg = spy.mock.calls[0]?.[0] as Record<string, unknown>;
    expect(arg.page).toBe(0);
    expect(arg.size).toBe(20);

    wrapper.unmount();
  });

  it('renders each audit row with risk level, status and toolCallCount', async () => {
    const { wrapper } = await mountPage({
      pageData: buildPage({
        content: [
          buildSummary({ auditId: 'a-1', toolCallCount: 3, riskLevel: 'L0' }),
          buildSummary({ auditId: 'a-2', toolCallCount: 0, riskLevel: 'L4' }),
        ],
      }),
    });
    expect(wrapper.find('[data-testid="audit-row-a-1"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="audit-row-a-2"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('3');
    expect(wrapper.text()).toContain('L4');
  });

  it('triggers getAuditLogs with the new page number when paginating', async () => {
    const { wrapper } = await mountPage({
      pageData: buildPage({ totalElements: 60, totalPages: 3, number: 0 }),
    });
    await flushPromises();
    const spy = vi.mocked(auditApi.getAuditLogs);
    spy.mockClear();

    // Element Plus pagination exposes a `.btn-next` button.
    const pag = wrapper.find('.el-pagination');
    expect(pag.exists()).toBe(true);
    const next = pag.find('.btn-next');
    if (next.exists()) {
      await next.trigger('click');
    } else {
      // Fallback: el-pagination renders the next button as the last
      // `.el-pager` button or a sibling next arrow.
      const buttons = pag.findAll('button');
      await buttons[buttons.length - 1]?.trigger('click');
    }
    await flushPromises();

    const calls = spy.mock.calls.map((c) => c[0] as Record<string, unknown>);
    expect(calls.some((c) => c.page === 1)).toBe(true);
  });

  it('changing risk filter calls getAuditLogs with riskLevel set', async () => {
    const spy = vi.spyOn(auditApi, 'getAuditLogs').mockResolvedValue(buildPage());
    const router = buildRouter();
    await router.push('/audit');
    await router.isReady();
    const wrapper = mount(AuditLogPage, {
      global: { plugins: [router, ElementPlus] },
    });
    await flushPromises();
    spy.mockClear();

    // Element Plus select is a div — direct v-model mutation through the
    // child component instance is the cleanest way to trigger the change
    // event in jsdom (the dropdown popup doesn't open).
    const select = wrapper.findComponent({ name: 'ElSelect' });
    expect(select.exists()).toBe(true);
    await select.vm.$emit('update:modelValue', 'L4');
    await select.vm.$emit('change', 'L4');
    await flushPromises();

    expect(spy.mock.calls.length).toBeGreaterThanOrEqual(1);
    const last = spy.mock.calls[spy.mock.calls.length - 1]?.[0] as
      | Record<string, unknown>
      | undefined;
    expect(last?.riskLevel).toBe('L4');
  });

  it('changing keyword filter calls getAuditLogs with keyword set', async () => {
    const spy = vi.spyOn(auditApi, 'getAuditLogs').mockResolvedValue(buildPage());
    const router = buildRouter();
    await router.push('/audit');
    await router.isReady();
    const wrapper = mount(AuditLogPage, {
      global: { plugins: [router, ElementPlus] },
    });
    await flushPromises();
    spy.mockClear();

    const input = wrapper.find('[data-testid="audit-filter-keyword"]');
    expect(input.exists()).toBe(true);
    await input.setValue('rm');
    await input.trigger('keydown', { key: 'Enter' });
    await flushPromises();

    expect(spy.mock.calls.length).toBeGreaterThanOrEqual(1);
    const last = spy.mock.calls[spy.mock.calls.length - 1]?.[0] as
      | Record<string, unknown>
      | undefined;
    expect(last?.keyword).toBe('rm');
  });

  it('shows empty state when API returns an empty page', async () => {
    const { wrapper } = await mountPage({
      pageData: buildPage({ content: [], totalElements: 0, totalPages: 0 }),
    });
    expect(wrapper.find('[data-testid="audit-empty"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('暂无审计记录');
  });

  it('shows Chinese error when getAuditLogs rejects', async () => {
    vi.spyOn(auditApi, 'getAuditLogs').mockRejectedValue(
      new ApiError({ code: 500, message: '后端服务暂时不可用', data: null }),
    );
    const router = buildRouter();
    await router.push('/audit');
    await router.isReady();
    const wrapper = mount(AuditLogPage, {
      global: { plugins: [router, ElementPlus] },
    });
    await flushPromises();

    expect(wrapper.find('[data-testid="audit-error"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('后端服务暂时不可用');
  });
});

describe('AuditLog page — detail drawer', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('opens drawer and calls getAuditDetail when a row is clicked', async () => {
    const detailSpy = vi
      .spyOn(auditApi, 'getAuditDetail')
      .mockResolvedValue(buildDetail({
        toolCalls: [{
          toolCallId: 't-fail',
          toolName: 'service_status_tool',
          status: 'FAILED',
          errorMessage: '服务状态读取失败',
        }],
      }));
    const { wrapper } = await mountPage({
      pageData: buildPage({ content: [buildSummary({ auditId: 'audit-click-1' })] }),
    });

    const row = wrapper.find('[data-testid="audit-row-audit-click-1"]');
    expect(row.exists()).toBe(true);
    await row.trigger('click');
    await flushPromises();

    expect(detailSpy).toHaveBeenCalledTimes(1);
    expect(detailSpy).toHaveBeenCalledWith('audit-click-1');

    // Drawer visible with sections: timeline, tool failures, risk checks,
    // pending action, execution result, final answer.
    expect(wrapper.find('[data-testid="audit-detail-drawer"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="audit-timeline"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="audit-tool-failure"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="audit-risk-check"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="audit-pending-action"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="audit-execution-result"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="audit-final-answer"]').exists()).toBe(true);
  });

  it('opens drawer automatically when route.query.auditId is present on mount', async () => {
    const detailSpy = vi
      .spyOn(auditApi, 'getAuditDetail')
      .mockResolvedValue(buildDetail({ auditId: 'audit-auto-1' }));
    const router = buildRouter();
    await router.push({ path: '/audit', query: { auditId: 'audit-auto-1' } });
    await router.isReady();
    vi.spyOn(auditApi, 'getAuditLogs').mockResolvedValue(buildPage());

    const wrapper = mount(AuditLogPage, {
      global: { plugins: [router, ElementPlus] },
    });
    await flushPromises();

    expect(detailSpy).toHaveBeenCalledWith('audit-auto-1');
    expect(wrapper.find('[data-testid="audit-detail-drawer"]').exists()).toBe(true);
  });

  it('renders parsed JSON fields safely in the detail drawer', async () => {
    vi.spyOn(auditApi, 'getAuditDetail').mockResolvedValue(
      buildDetail({
        matchedRules: '["rule-a","rule-b"]',
        executionResult: '{"ok":true,"code":200}',
      }),
    );
    const { wrapper } = await mountPage({
      pageData: buildPage({ content: [buildSummary({ auditId: 'a-parse' })] }),
    });
    await wrapper.find('[data-testid="audit-row-a-parse"]').trigger('click');
    await flushPromises();

    // Parsed JSON should expose its keys — but never via v-html. The DOM
    // should contain key/value text rendered via text interpolation.
    const drawer = wrapper.find('[data-testid="audit-detail-drawer"]');
    expect(drawer.exists()).toBe(true);
    // The parsed matchedRules list must surface "rule-a".
    expect(drawer.text()).toContain('rule-a');
    // Parsed executionResult must surface the "ok" key value.
    expect(drawer.text()).toContain('true');
  });

  it('falls back to the raw sanitized string when JSON parsing fails', async () => {
    // Intentionally malformed JSON.
    vi.spyOn(auditApi, 'getAuditDetail').mockResolvedValue(
      buildDetail({
        matchedRules: '{not-valid-json',
        executionResult: 'plain text result',
      }),
    );
    const { wrapper } = await mountPage({
      pageData: buildPage({ content: [buildSummary({ auditId: 'a-raw' })] }),
    });
    await wrapper.find('[data-testid="audit-row-a-raw"]').trigger('click');
    await flushPromises();

    const drawer = wrapper.find('[data-testid="audit-detail-drawer"]');
    expect(drawer.exists()).toBe(true);
    // The raw string is shown as-is when parsing fails.
    expect(drawer.text()).toContain('{not-valid-json');
    expect(drawer.text()).toContain('plain text result');
  });

  it('shows Chinese error in the drawer when getAuditDetail rejects', async () => {
    vi.spyOn(auditApi, 'getAuditDetail').mockRejectedValue(
      new ApiError({ code: 404, message: '审计日志不存在', data: null }),
    );
    const { wrapper } = await mountPage({
      pageData: buildPage({ content: [buildSummary({ auditId: 'a-404' })] }),
    });
    await wrapper.find('[data-testid="audit-row-a-404"]').trigger('click');
    await flushPromises();

    expect(wrapper.find('[data-testid="audit-detail-error"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('审计日志不存在');
  });

  it('renders notification records card when detail has notificationRecords', async () => {
    vi.spyOn(auditApi, 'getAuditDetail').mockResolvedValue(
      buildDetail({
        notificationRecords: [
          {
            recordId: 'nr-1',
            eventId: 'evt-1',
            auditId: 'audit-1',
            channelId: 'ch-1',
            channelType: 'FEISHU',
            status: 'SENT',
            responseCode: 200,
            errorMessage: null,
            retryCount: 0,
            sentAt: '2026-06-12T10:00:06',
            createdAt: '2026-06-12T10:00:03',
          },
        ],
      }),
    );
    const { wrapper } = await mountPage({
      pageData: buildPage({ content: [buildSummary({ auditId: 'a-notif' })] }),
    });
    await wrapper.find('[data-testid="audit-row-a-notif"]').trigger('click');
    await flushPromises();

    const drawer = wrapper.find('[data-testid="audit-detail-drawer"]');
    expect(drawer.exists()).toBe(true);
    expect(wrapper.find('[data-testid="audit-notification-records"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('飞书');
    expect(wrapper.text()).toContain('已发送');
  });

  it('shows empty state when notificationRecords is null', async () => {
    vi.spyOn(auditApi, 'getAuditDetail').mockResolvedValue(
      buildDetail({ notificationRecords: null }),
    );
    const { wrapper } = await mountPage({
      pageData: buildPage({ content: [buildSummary({ auditId: 'a-null' })] }),
    });
    await wrapper.find('[data-testid="audit-row-a-null"]').trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('暂无通知发送记录');
  });

  it('renders FAILED notification records with error message', async () => {
    vi.spyOn(auditApi, 'getAuditDetail').mockResolvedValue(
      buildDetail({
        notificationRecords: [
          {
            recordId: 'nr-fail',
            eventId: 'evt-fail',
            auditId: 'audit-1',
            channelId: 'ch-1',
            channelType: 'WEBHOOK',
            status: 'FAILED',
            responseCode: 500,
            errorMessage: '连接超时',
            retryCount: 0,
            sentAt: null,
            createdAt: '2026-06-12T10:00:03',
          },
        ],
      }),
    );
    const { wrapper } = await mountPage({
      pageData: buildPage({ content: [buildSummary({ auditId: 'a-fail-nr' })] }),
    });
    await wrapper.find('[data-testid="audit-row-a-fail-nr"]').trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('Webhook');
    expect(wrapper.text()).toContain('发送失败');
    expect(wrapper.text()).toContain('连接超时');
    expect(wrapper.text()).toContain('500');
  });

  it('renders a tool-failure card when a ToolCallInfo.status indicates failure', async () => {
    vi.spyOn(auditApi, 'getAuditDetail').mockResolvedValue(
      buildDetail({
        toolCalls: [
          {
            toolCallId: 't-fail',
            toolName: 'disk_usage_tool',
            status: 'FAILED',
            errorMessage: '磁盘读取失败',
          },
        ],
      }),
    );
    const { wrapper } = await mountPage({
      pageData: buildPage({ content: [buildSummary({ auditId: 'a-fail' })] }),
    });
    await wrapper.find('[data-testid="audit-row-a-fail"]').trigger('click');
    await flushPromises();

    expect(wrapper.find('[data-testid="audit-tool-failure"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('磁盘读取失败');
  });
});
