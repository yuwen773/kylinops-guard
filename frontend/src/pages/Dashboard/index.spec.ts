import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mount, flushPromises, enableAutoUnmount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import DashboardPage from './index.vue';
import * as dashboardApi from '@/api/dashboard';
import { ApiError } from '@/api/client';
import type { DashboardOverview } from '@/types/dashboard';

enableAutoUnmount(afterEach);

/**
 * Dashboard page contract (Task 10):
 *   - On mount: calls getDashboardOverview() exactly once.
 *   - Renders score, auditId, collectedAt from the payload.
 *   - score === null renders "—" (never a fake number).
 *   - Each metric is rendered via StatusMetricCard; failed metrics show
 *     "数据不可用" / "部分降级" (independent failure display).
 *   - Manual refresh button: disabled while in-flight, and the button text
 *     shows "采集中…" while loading.
 *   - Stale-data behavior: a failed refresh KEEPS the previous successful
 *     snapshot on screen and shows a stale banner — the page is never
 *     blanked by a transient failure.
 *   - No hard-coded OS numbers. The spec only asserts on the absence of
 *     suspicious literal thresholds inside the rendered output.
 *   - Per-metric thresholds: when a tool payload reports e.g. CPU usage
 *     > 80%, the corresponding StatusMetricCard switches to a "critical"
 *     tone tag — purely cosmetic, the backend score is not mutated.
 */

function buildOverview(overrides: Partial<DashboardOverview> = {}): DashboardOverview {
  return {
    score: 88,
    successfulMetricCount: 5,
    totalMetricCount: 6,
    degraded: true,
    auditId: 'audit-dash-1',
    collectedAt: '2026-06-12T08:30:00Z',
    metrics: [
      {
        toolName: 'cpu_status_tool',
        status: 'success',
        data: { usagePercent: 42.5, loadAvg1: 0.5 },
        durationMs: 12,
      },
      {
        toolName: 'memory_status_tool',
        status: 'success',
        data: { totalMB: 8192, usedMB: 4096, usedPercent: 50 },
        durationMs: 8,
      },
      {
        toolName: 'disk_usage_tool',
        status: 'success',
        data: { partitions: [{ mount: '/', usedPercent: 86, size: '100G', used: '86G' }] },
        durationMs: 14,
      },
      {
        toolName: 'service_status_tool',
        status: 'success',
        data: { serviceName: 'nginx', activeState: 'inactive', subState: 'dead' },
        durationMs: 9,
      },
      {
        toolName: 'network_port_tool',
        status: 'success',
        data: { ports: [] },
        durationMs: 7,
      },
      {
        toolName: 'journal_log_tool',
        status: 'failed',
        errorMessage: 'journalctl 不可用',
        durationMs: 3000,
      },
    ],
    ...overrides,
  };
}

async function mountPage(
  overview: DashboardOverview | 'reject' | 'pending' = buildOverview(),
) {
  const spy = vi.spyOn(dashboardApi, 'getDashboardOverview');
  if (overview === 'reject') {
    spy.mockRejectedValue(
      new ApiError({ code: 500, message: '概览加载失败', data: null }),
    );
  } else if (overview === 'pending') {
    spy.mockReturnValue(new Promise<DashboardOverview>(() => {}));
  } else {
    spy.mockResolvedValue(overview);
  }
  const wrapper = mount(DashboardPage, {
    global: { plugins: [ElementPlus] },
    attachTo: document.body,
  });
  await flushPromises();
  return { wrapper, spy };
}

describe('Dashboard — initial load', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('calls getDashboardOverview on mount', async () => {
    const { spy } = await mountPage();
    expect(spy).toHaveBeenCalledTimes(1);
  });

  it('renders score, auditId, and collected time from the payload', async () => {
    const { wrapper } = await mountPage();
    const scoreCard = wrapper.find('[data-testid="dashboard-score-card"]');
    expect(scoreCard.exists()).toBe(true);
    expect(scoreCard.text()).toContain('88');
    expect(wrapper.text()).toContain('audit-dash-1');
    // The collectedAt text comes from toLocaleString; the ISO substring is
    // present as the source value before formatting.
    expect(wrapper.find('[data-testid="dashboard-collected-at"]').exists()).toBe(true);
  });

  it('shows the coverage (successful/total) and degraded marker', async () => {
    const { wrapper } = await mountPage();
    const coverage = wrapper.find('[data-testid="dashboard-coverage-card"]');
    expect(coverage.exists()).toBe(true);
    expect(coverage.text()).toContain('5');
    expect(coverage.text()).toContain('6');
    // degraded=true on the fixture must surface somewhere.
    const degraded = wrapper.find('[data-testid="dashboard-degraded-tag"]');
    expect(degraded.exists()).toBe(true);
  });

  it('renders "—" when score is null and does not invent a number', async () => {
    const overview = buildOverview({ score: null, successfulMetricCount: 0, totalMetricCount: 6, degraded: true });
    const { wrapper } = await mountPage(overview);
    const scoreCard = wrapper.find('[data-testid="dashboard-score-card"]');
    expect(scoreCard.text()).toContain('—');
    // Make sure the placeholder is rendered, not a fake 0 or 100.
    expect(scoreCard.text()).not.toMatch(/分数\s*0\b/);
  });
});

describe('Dashboard — per-metric rendering', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('renders one StatusMetricCard per backend metric', async () => {
    const { wrapper } = await mountPage();
    expect(wrapper.find('[data-testid="status-metric-cpu_status_tool"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="status-metric-memory_status_tool"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="status-metric-disk_usage_tool"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="status-metric-service_status_tool"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="status-metric-network_port_tool"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="status-metric-journal_log_tool"]').exists()).toBe(true);
  });

  it('marks a failed metric as unavailable (independent failure display)', async () => {
    const { wrapper } = await mountPage();
    const failed = wrapper.find('[data-testid="status-metric-tone-unavailable"]');
    expect(failed.exists()).toBe(true);
    // The failed metric should show its error reason so the operator can debug.
    expect(wrapper.text()).toContain('journalctl 不可用');
  });

  it('switches the cpu metric to "critical" tone when usage > 80', async () => {
    const overview = buildOverview();
    overview.metrics[0].data = { usagePercent: 92, loadAvg1: 4.2 };
    const { wrapper } = await mountPage(overview);
    expect(wrapper.find('[data-testid="status-metric-tone-critical"]').exists()).toBe(true);
  });

  it('switches the memory metric to "warning" tone when usedPercent is in (75, 90]', async () => {
    const overview = buildOverview();
    overview.metrics[1].data = { usedPercent: 82, totalMB: 8192, usedMB: 6717 };
    const { wrapper } = await mountPage(overview);
    expect(wrapper.find('[data-testid="status-metric-tone-warning"]').exists()).toBe(true);
  });
});

describe('Dashboard — manual refresh and stale data', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('triggers a new fetch when the refresh button is clicked', async () => {
    const { wrapper, spy } = await mountPage();
    spy.mockClear();
    const second = buildOverview({ score: 91, auditId: 'audit-dash-2' });
    spy.mockResolvedValueOnce(second);
    const button = wrapper.find('[data-testid="dashboard-refresh-button"]');
    expect(button.exists()).toBe(true);
    await button.trigger('click');
    await flushPromises();
    expect(spy).toHaveBeenCalledTimes(1);
    expect(wrapper.text()).toContain('audit-dash-2');
  });

  it('disables the refresh button and shows "采集中…" while in-flight', async () => {
    // First load completes; second call is pending.
    const first = buildOverview();
    const pending = new Promise<DashboardOverview>(() => {});
    const spy = vi.spyOn(dashboardApi, 'getDashboardOverview')
      .mockResolvedValueOnce(first)
      .mockReturnValueOnce(pending);
    const wrapper = mount(DashboardPage, {
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    });
    await flushPromises();
    const button = wrapper.find('[data-testid="dashboard-refresh-button"]');
    expect(button.exists()).toBe(true);
    await button.trigger('click');
    await flushPromises();
    const buttonAfter = wrapper.find('[data-testid="dashboard-refresh-button"]');
    // Element Plus renders the disabled prop as a `disabled` attribute and
    // adds the `is-disabled` class. We accept either signal.
    const isDisabled =
      buttonAfter.attributes('disabled') !== undefined ||
      buttonAfter.classes().includes('is-disabled');
    expect(isDisabled).toBe(true);
    expect(buttonAfter.text()).toContain('采集中');
    // The first payload is still on screen.
    expect(wrapper.text()).toContain('audit-dash-1');
    spy.mockReset();
  });

  it('keeps the previous snapshot and shows a stale banner when a refresh fails', async () => {
    const first = buildOverview({ score: 88, auditId: 'audit-dash-1' });
    const spy = vi.spyOn(dashboardApi, 'getDashboardOverview')
      .mockResolvedValueOnce(first)
      .mockRejectedValueOnce(
        new ApiError({ code: 500, message: '刷新失败', data: null }),
      );
    const wrapper = mount(DashboardPage, {
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    });
    await flushPromises();
    expect(wrapper.text()).toContain('audit-dash-1');
    const button = wrapper.find('[data-testid="dashboard-refresh-button"]');
    await button.trigger('click');
    await flushPromises();
    // The previous audit id must still be on screen.
    expect(wrapper.text()).toContain('audit-dash-1');
    // A stale banner is visible.
    const banner = wrapper.find('[data-testid="dashboard-stale-banner"]');
    expect(banner.exists()).toBe(true);
    expect(banner.text()).toContain('刷新失败');
    // The backend score from the first load is still rendered.
    const scoreCard = wrapper.find('[data-testid="dashboard-score-card"]');
    expect(scoreCard.text()).toContain('88');
    spy.mockReset();
  });
});

describe('Dashboard — no hard-coded OS data', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('does not bake threshold literals into the rendered output', async () => {
    const { wrapper } = await mountPage();
    // The page itself must not print e.g. "95%" or "42 GB" as a fixed
    // number — those are values the backend should be sending. We only
    // assert that no fixed percentage appears next to common titles when
    // the payload says nothing about it.
    const text = wrapper.text();
    // A common hard-coded trap: "CPU 12%". The fixture's CPU is 42.5, so
    // a literal "12" must not appear attached to the CPU title.
    const cpuCard = wrapper.find('[data-testid="status-metric-cpu_status_tool"]');
    expect(cpuCard.text()).not.toMatch(/\b12\s*%/);
    // The page must not mention arbitrary placeholder bytes (e.g. "16 GB").
    expect(text).not.toMatch(/\b16\s*GB\b/);
  });
});
