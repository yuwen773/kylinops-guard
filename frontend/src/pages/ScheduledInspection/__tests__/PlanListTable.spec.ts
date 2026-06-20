import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import ElementPlus from 'element-plus';

// Element Plus 的 ElMessage / ElMessageBox stub,沿用 NotificationSettings 模式。
// vi.hoisted 必须在文件顶层 — vi.mock 工厂会被提升到 import 之前。
const elementPlusMocks = vi.hoisted(() => ({
  elMessageSuccessMock: vi.fn(),
  elMessageErrorMock: vi.fn(),
  elMessageInfoMock: vi.fn(),
  elMessageBoxConfirmMock: vi.fn(async () => 'confirm'),
}));

vi.mock('element-plus', async () => {
  const actual = await vi.importActual<typeof import('element-plus')>('element-plus');
  return {
    ...actual,
    ElMessage: {
      success: elementPlusMocks.elMessageSuccessMock,
      error: elementPlusMocks.elMessageErrorMock,
      info: elementPlusMocks.elMessageInfoMock,
    },
    ElMessageBox: {
      confirm: elementPlusMocks.elMessageBoxConfirmMock,
    },
  };
});

// 重要:vi.mock 必须在 import 组件之前
vi.mock('@/api/inspection', () => ({
  enablePlan: vi.fn(),
  disablePlan: vi.fn(),
  deletePlan: vi.fn(),
  runPlan: vi.fn(),
}));

import PlanListTable from '../components/PlanListTable.vue';
import * as inspectionApi from '@/api/inspection';
import { ApiError } from '@/api/client';
import type { InspectionPlanSummary } from '@/types/inspection';

function buildPlan(overrides: Partial<InspectionPlanSummary> = {}): InspectionPlanSummary {
  return {
    planId: 'plan-001',
    name: 'nginx 健康巡检',
    description: '每日 03:00 巡检 nginx',
    templateType: 'HEALTH',
    scheduleType: 'DAILY',
    timezone: 'Asia/Shanghai',
    notificationPolicy: 'ON_ABNORMAL',
    enabled: true,
    nextRunAt: '2026-06-20T03:00:00',
    lastRunAt: '2026-06-19T03:00:05',
    version: 3,
    ...overrides,
  };
}

async function mountTable(plans: InspectionPlanSummary[] = [buildPlan()]) {
  const wrapper = mount(PlanListTable, {
    props: { plans, loading: false },
    global: { plugins: [ElementPlus] },
  });
  await flushPromises();
  return wrapper;
}

describe('PlanListTable — 渲染计划行', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('展示计划名称 / 模板类型 / 调度类型 / 启用状态', async () => {
    const wrapper = await mountTable([
      buildPlan({ planId: 'plan-001', name: 'nginx 健康巡检', templateType: 'HEALTH', scheduleType: 'DAILY', enabled: true }),
      buildPlan({ planId: 'plan-002', name: '磁盘扫描', templateType: 'DISK', scheduleType: 'WEEKLY', enabled: false, version: 1 }),
    ]);

    expect(wrapper.find('[data-testid="plan-row-plan-001"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="plan-row-plan-001"]').text()).toContain('nginx 健康巡检');
    expect(wrapper.find('[data-testid="plan-row-plan-002"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="plan-row-plan-002"]').text()).toContain('磁盘扫描');
    // 模板类型展示
    expect(wrapper.find('[data-testid="plan-row-plan-001"]').text()).toContain('健康');
    expect(wrapper.find('[data-testid="plan-row-plan-002"]').text()).toContain('磁盘');
  });

  it('空状态展示占位文案', async () => {
    const wrapper = await mountTable([]);
    expect(wrapper.find('[data-testid="plan-list-empty"]').exists()).toBe(true);
  });
});

describe('PlanListTable — 启停 / 删除 / 立即执行', () => {
  beforeEach(() => {
    vi.mocked(inspectionApi.enablePlan).mockReset();
    vi.mocked(inspectionApi.disablePlan).mockReset();
    vi.mocked(inspectionApi.deletePlan).mockReset();
    vi.mocked(inspectionApi.runPlan).mockReset();
    elementPlusMocks.elMessageBoxConfirmMock.mockReset();
    elementPlusMocks.elMessageBoxConfirmMock.mockResolvedValue('confirm');
    elementPlusMocks.elMessageSuccessMock.mockReset();
    elementPlusMocks.elMessageErrorMock.mockReset();
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('点击「停用」按钮 → 调用 disablePlan 并 emit changed', async () => {
    vi.mocked(inspectionApi.disablePlan).mockResolvedValue(
      buildPlan({ enabled: false, version: 4 }),
    );
    const wrapper = await mountTable([buildPlan({ planId: 'plan-001', enabled: true })]);
    await wrapper.find('[data-testid="plan-disable-plan-001"]').trigger('click');
    await flushPromises();

    expect(inspectionApi.disablePlan).toHaveBeenCalledWith('plan-001');
    expect(wrapper.emitted('changed')).toBeTruthy();
  });

  it('点击「启用」按钮 → 调用 enablePlan 并 emit changed', async () => {
    vi.mocked(inspectionApi.enablePlan).mockResolvedValue(
      buildPlan({ enabled: true, version: 2 }),
    );
    const wrapper = await mountTable([buildPlan({ planId: 'plan-002', enabled: false })]);
    await wrapper.find('[data-testid="plan-enable-plan-002"]').trigger('click');
    await flushPromises();

    expect(inspectionApi.enablePlan).toHaveBeenCalledWith('plan-002');
    expect(wrapper.emitted('changed')).toBeTruthy();
  });

  it('点击「删除」 → ElMessageBox confirm 拒绝则不调 API', async () => {
    elementPlusMocks.elMessageBoxConfirmMock.mockResolvedValueOnce('cancel');
    const wrapper = await mountTable([buildPlan({ planId: 'plan-001' })]);
    await wrapper.find('[data-testid="plan-delete-plan-001"]').trigger('click');
    await flushPromises();

    expect(inspectionApi.deletePlan).not.toHaveBeenCalled();
  });

  it('点击「删除」 → ElMessageBox confirm 接受则调 deletePlan', async () => {
    vi.mocked(inspectionApi.deletePlan).mockResolvedValue(undefined);
    const wrapper = await mountTable([buildPlan({ planId: 'plan-001' })]);
    await wrapper.find('[data-testid="plan-delete-plan-001"]').trigger('click');
    await flushPromises();

    expect(inspectionApi.deletePlan).toHaveBeenCalledWith('plan-001');
    expect(wrapper.emitted('changed')).toBeTruthy();
  });

  it('「立即执行」→ 调用 runPlan 并 emit run', async () => {
    vi.mocked(inspectionApi.runPlan).mockResolvedValue({
      executionId: 'exec-001',
      status: 'RUNNING',
    });
    const wrapper = await mountTable([buildPlan({ planId: 'plan-001' })]);
    await wrapper.find('[data-testid="plan-run-plan-001"]').trigger('click');
    await flushPromises();

    expect(inspectionApi.runPlan).toHaveBeenCalledWith('plan-001');
    expect(wrapper.emitted('run')).toBeTruthy();
    expect(wrapper.emitted('run')![0]).toEqual([{ planId: 'plan-001', executionId: 'exec-001' }]);
  });

  it('API 失败(404)→ ElMessage.error,不 emit changed', async () => {
    vi.mocked(inspectionApi.disablePlan).mockRejectedValue(
      new ApiError({ code: 404, message: '[plan] 不存在: plan-001', data: null }),
    );
    const wrapper = await mountTable([buildPlan({ planId: 'plan-001' })]);
    await wrapper.find('[data-testid="plan-disable-plan-001"]').trigger('click');
    await flushPromises();

    expect(elementPlusMocks.elMessageErrorMock).toHaveBeenCalled();
    expect(wrapper.emitted('changed')).toBeFalsy();
  });
});
