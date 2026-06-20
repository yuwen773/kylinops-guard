import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import ElementPlus from 'element-plus';

const elementPlusMocks = vi.hoisted(() => ({
  elMessageSuccessMock: vi.fn(),
  elMessageErrorMock: vi.fn(),
}));

vi.mock('element-plus', async () => {
  const actual = await vi.importActual<typeof import('element-plus')>('element-plus');
  return {
    ...actual,
    ElMessage: {
      success: elementPlusMocks.elMessageSuccessMock,
      error: elementPlusMocks.elMessageErrorMock,
      info: vi.fn(),
    },
  };
});

vi.mock('@/api/inspection', () => ({
  runPlan: vi.fn(),
  getExecution: vi.fn(),
}));

import RunNowProgressDialog from '../components/RunNowProgressDialog.vue';
import * as inspectionApi from '@/api/inspection';
import type { InspectionExecutionDetail, InspectionExecutionStatus } from '@/types/inspection';

function buildExecution(
  status: InspectionExecutionStatus,
  overrides: Partial<InspectionExecutionDetail> = {},
): InspectionExecutionDetail {
  return {
    planId: 'plan-001',
    executionId: 'exec-001',
    status,
    triggerType: 'MANUAL',
    operator: 'admin',
    startedAt: '2026-06-19T03:00:00',
    finishedAt: status === 'RUNNING' ? null : '2026-06-19T03:00:05',
    abnormal: status === 'FAILED',
    summary: status === 'FAILED' ? '巡检 FAILED: 磁盘使用率 95% 超过阈值' : '巡检完成',
    planSnapshotJson: '{}',
    errorMessage: status === 'FAILED' ? '巡检 FAILED: 磁盘使用率 95% 超过阈值' : null,
    ...overrides,
  };
}

async function mountDialog(planId = 'plan-001', planName = '测试计划') {
  const wrapper = mount(RunNowProgressDialog, {
    props: { visible: true, planId, planName },
    global: { plugins: [ElementPlus] },
  });
  await flushPromises();
  return wrapper;
}

describe('RunNowProgressDialog — 启动后立即触发 runPlan + 轮询', () => {
  beforeEach(() => {
    vi.mocked(inspectionApi.runPlan).mockReset();
    vi.mocked(inspectionApi.getExecution).mockReset();
    elementPlusMocks.elMessageSuccessMock.mockReset();
    elementPlusMocks.elMessageErrorMock.mockReset();
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('visible=true 时立即调 runPlan(planId),并把 executionId 写入展示', async () => {
    vi.mocked(inspectionApi.runPlan).mockResolvedValue({
      executionId: 'exec-001',
      status: 'RUNNING',
    });
    vi.mocked(inspectionApi.getExecution).mockResolvedValue(buildExecution('SUCCESS'));

    await mountDialog('plan-001');

    expect(inspectionApi.runPlan).toHaveBeenCalledWith('plan-001');
    expect(inspectionApi.getExecution).toHaveBeenCalledWith('exec-001');
  });

  it('getExecution 返回 SUCCESS → 展示「✓」并 emit done', async () => {
    vi.useFakeTimers();
    vi.mocked(inspectionApi.runPlan).mockResolvedValue({
      executionId: 'exec-001',
      status: 'RUNNING',
    });
    vi.mocked(inspectionApi.getExecution).mockResolvedValue(buildExecution('SUCCESS'));

    const wrapper = await mountDialog('plan-001');

    // 跑完即时 + 一次轮询
    await vi.runOnlyPendingTimersAsync();
    await flushPromises();

    expect(elementPlusMocks.elMessageSuccessMock).toHaveBeenCalled();
    expect(wrapper.find('[data-testid="run-progress-status"]').text()).toContain('成功');
    expect(wrapper.emitted('done')).toBeTruthy();
  });

  it('getExecution 返回 FAILED → 展示「✗」+ errorMessage,emit done', async () => {
    vi.useFakeTimers();
    vi.mocked(inspectionApi.runPlan).mockResolvedValue({
      executionId: 'exec-002',
      status: 'RUNNING',
    });
    vi.mocked(inspectionApi.getExecution).mockResolvedValue(buildExecution('FAILED'));

    const wrapper = await mountDialog('plan-001', '失败计划');

    await vi.runOnlyPendingTimersAsync();
    await flushPromises();

    expect(elementPlusMocks.elMessageErrorMock).toHaveBeenCalled();
    const errEl = wrapper.find('[data-testid="run-progress-error"]');
    expect(errEl.exists()).toBe(true);
    expect(errEl.text()).toContain('磁盘使用率');
    expect(wrapper.emitted('done')).toBeTruthy();
  });

  it('getExecution 持续返回 RUNNING → 60s 后展示「未在预期时间内完成」', async () => {
    vi.useFakeTimers();
    vi.mocked(inspectionApi.runPlan).mockResolvedValue({
      executionId: 'exec-003',
      status: 'RUNNING',
    });
    // 每次轮询都返回 RUNNING(永不收敛)
    vi.mocked(inspectionApi.getExecution).mockResolvedValue(buildExecution('RUNNING'));

    const wrapper = await mountDialog('plan-001');

    // 立即触发一次
    await vi.runOnlyPendingTimersAsync();
    await flushPromises();

    // 推进到 60s + 1
    await vi.advanceTimersByTimeAsync(61_000);
    await flushPromises();

    const timeoutEl = wrapper.find('[data-testid="run-progress-timeout"]');
    expect(timeoutEl.exists()).toBe(true);
    expect(timeoutEl.text()).toContain('未在预期时间内完成');
    // 60s timeout 后仍可能 emit done(让父组件关闭 dialog),但不调用 ElMessage.success
    expect(elementPlusMocks.elMessageSuccessMock).not.toHaveBeenCalled();
  });

  it('组件 unmount 时清理 setInterval,避免内存泄漏', async () => {
    vi.useFakeTimers();
    vi.mocked(inspectionApi.runPlan).mockResolvedValue({
      executionId: 'exec-004',
      status: 'RUNNING',
    });
    vi.mocked(inspectionApi.getExecution).mockResolvedValue(buildExecution('RUNNING'));

    const wrapper = await mountDialog('plan-001');
    const callCountBefore = vi.mocked(inspectionApi.getExecution).mock.calls.length;
    wrapper.unmount();
    await vi.advanceTimersByTimeAsync(10_000);
    const callCountAfter = vi.mocked(inspectionApi.getExecution).mock.calls.length;
    // unmount 后不再轮询
    expect(callCountAfter).toBe(callCountBefore);
  });

  it('visible=false → 清理轮询(下一次 visible=true 时重新启动)', async () => {
    vi.useFakeTimers();
    vi.mocked(inspectionApi.runPlan).mockResolvedValue({
      executionId: 'exec-005',
      status: 'RUNNING',
    });
    vi.mocked(inspectionApi.getExecution).mockResolvedValue(buildExecution('RUNNING'));

    const wrapper = mount(RunNowProgressDialog, {
      props: { visible: true, planId: 'plan-001', planName: 'x' },
      global: { plugins: [ElementPlus] },
    });
    await flushPromises();
    const callCountAfterStart = vi.mocked(inspectionApi.getExecution).mock.calls.length;
    // 关闭 dialog
    await wrapper.setProps({ visible: false });
    await vi.advanceTimersByTimeAsync(5_000);
    const callCountAfterClose = vi.mocked(inspectionApi.getExecution).mock.calls.length;
    expect(callCountAfterClose).toBe(callCountAfterStart);
  });
});
