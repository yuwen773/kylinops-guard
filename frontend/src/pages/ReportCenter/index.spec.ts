import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mount, enableAutoUnmount, flushPromises } from '@vue/test-utils';
import { createRouter, createMemoryHistory, type Router } from 'vue-router';
import ElementPlus from 'element-plus';
import ReportCenter from './index.vue';
import * as reportsApi from '@/api/reports';
import MarkdownIt from 'markdown-it';
import type { ReportDetail, ReportPage, ReportSummary } from '@/types/report';

// ---------------------------------------------------------------------------
// Fixtures
// ---------------------------------------------------------------------------

const REPORT_1: ReportSummary = {
  reportId: 'rpt-001',
  title: '系统健康检查报告',
  reportType: 'HEALTH',
  riskLevel: 'L0',
  sessionId: 's-1',
  auditId: 'audit-1001',
  createdAt: '2026-06-12T10:00:00',
};

const REPORT_2: ReportSummary = {
  reportId: 'rpt-002',
  title: 'nginx 服务诊断',
  reportType: 'SERVICE',
  riskLevel: 'L2',
  sessionId: 's-2',
  auditId: 'audit-1002',
  createdAt: '2026-06-12T11:00:00',
};

const PAGE_RESPONSE: ReportPage = {
  content: [REPORT_1, REPORT_2],
  totalElements: 2,
  totalPages: 1,
  number: 0,
  size: 20,
};

const REPORT_DETAIL: ReportDetail = {
  reportId: 'rpt-001',
  title: '系统健康检查报告',
  reportType: 'HEALTH',
  riskLevel: 'L0',
  sessionId: 's-1',
  auditId: 'audit-1001',
  bodyMarkdown:
    '## 健康摘要\n\nCPU 正常\n\n- 内存 12%\n- 磁盘 86%\n\n详见审计日志。',
  createdAt: '2026-06-12T10:00:00',
};

const EMPTY_PAGE: ReportPage = {
  content: [],
  totalElements: 0,
  totalPages: 0,
  number: 0,
  size: 20,
};

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

enableAutoUnmount(afterEach);

function buildRouter(initialQuery: Record<string, string> = {}): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', redirect: '/reports' },
      {
        path: '/reports',
        name: 'reports',
        component: { template: '<div data-testid="reports-stub" />' },
      },
      {
        path: '/audit',
        name: 'audit',
        component: { template: '<div data-testid="audit-page" />' },
      },
    ],
  });
}

function mountPage(query: Record<string, string> = {}) {
  const router = buildRouter(query);
  router.push({ path: '/reports', query });
  return router.isReady().then(() =>
    mount(ReportCenter, {
      global: { plugins: [router, ElementPlus] },
    }),
  );
}

// ---------------------------------------------------------------------------
// Tests — list + pagination
// ---------------------------------------------------------------------------

describe('ReportCenter — list', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('calls getReports on mount and renders the returned summaries', async () => {
    const spy = vi.spyOn(reportsApi, 'getReports').mockResolvedValue(PAGE_RESPONSE);
    const wrapper = await mountPage();
    await flushPromises();

    expect(spy).toHaveBeenCalledTimes(1);
    // Default pagination must be the documented page=0, size=20.
    expect(spy).toHaveBeenCalledWith({ page: 0, size: 20 });

    expect(wrapper.find(`[data-testid="report-row-${REPORT_1.reportId}"]`).exists()).toBe(true);
    expect(wrapper.find(`[data-testid="report-row-${REPORT_2.reportId}"]`).exists()).toBe(true);
    expect(wrapper.text()).toContain('系统健康检查报告');
    expect(wrapper.text()).toContain('nginx 服务诊断');
  });

  it('renders an empty state when the page is empty', async () => {
    vi.spyOn(reportsApi, 'getReports').mockResolvedValue(EMPTY_PAGE);
    const wrapper = await mountPage();
    await flushPromises();

    expect(wrapper.find('[data-testid="report-empty"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('暂无报告');
  });

  it('renders a Chinese error message when getReports fails', async () => {
    vi.spyOn(reportsApi, 'getReports').mockRejectedValue(
      new Error('后端服务暂时不可用'),
    );
    const wrapper = await mountPage();
    await flushPromises();

    expect(wrapper.find('[data-testid="report-error"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('后端服务暂时不可用');
  });

  it('shows a pagination control when totalElements > 0', async () => {
    vi.spyOn(reportsApi, 'getReports').mockResolvedValue({
      ...PAGE_RESPONSE,
      totalElements: 45,
      totalPages: 3,
    });
    const wrapper = await mountPage();
    await flushPromises();

    expect(wrapper.find('[data-testid="report-pagination"]').exists()).toBe(true);
  });

  it('hides the pagination when totalElements is 0', async () => {
    vi.spyOn(reportsApi, 'getReports').mockResolvedValue(EMPTY_PAGE);
    const wrapper = await mountPage();
    await flushPromises();

    expect(wrapper.find('[data-testid="report-pagination"]').exists()).toBe(false);
  });
});

// ---------------------------------------------------------------------------
// Tests — detail drawer
// ---------------------------------------------------------------------------

describe('ReportCenter — detail', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('opens the detail drawer automatically when route.query.reportId is present', async () => {
    vi.spyOn(reportsApi, 'getReports').mockResolvedValue(PAGE_RESPONSE);
    const detailSpy = vi
      .spyOn(reportsApi, 'getReportDetail')
      .mockResolvedValue(REPORT_DETAIL);

    const wrapper = await mountPage({ reportId: 'rpt-001' });
    await flushPromises();

    expect(detailSpy).toHaveBeenCalledTimes(1);
    expect(detailSpy).toHaveBeenCalledWith('rpt-001');
    expect(wrapper.find('[data-testid="report-detail"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="report-detail-title"]').text()).toContain(
      '系统健康检查报告',
    );
  });

  it('does NOT auto-open the detail drawer when no reportId is in the query', async () => {
    vi.spyOn(reportsApi, 'getReports').mockResolvedValue(PAGE_RESPONSE);
    const detailSpy = vi
      .spyOn(reportsApi, 'getReportDetail')
      .mockResolvedValue(REPORT_DETAIL);

    const wrapper = await mountPage();
    await flushPromises();

    expect(detailSpy).not.toHaveBeenCalled();
    expect(wrapper.find('[data-testid="report-detail"]').exists()).toBe(false);
  });

  it('renders a "查看源审计" link that points at /audit?auditId=...', async () => {
    vi.spyOn(reportsApi, 'getReports').mockResolvedValue(PAGE_RESPONSE);
    vi.spyOn(reportsApi, 'getReportDetail').mockResolvedValue(REPORT_DETAIL);

    const wrapper = await mountPage({ reportId: 'rpt-001' });
    await flushPromises();

    const link = wrapper.find('[data-testid="report-detail-source-audit-link"]');
    expect(link.exists()).toBe(true);
    expect(link.attributes('href')).toBe(
      `/audit?auditId=${encodeURIComponent('audit-1001')}`,
    );
  });

  it('renders the Markdown body via markdown-it (sanitized, html:false)', async () => {
    vi.spyOn(reportsApi, 'getReports').mockResolvedValue(PAGE_RESPONSE);
    vi.spyOn(reportsApi, 'getReportDetail').mockResolvedValue({
      ...REPORT_DETAIL,
      bodyMarkdown:
        '## 健康摘要\n\nCPU 正常\n\n- 内存 12%\n- 磁盘 86%',
    });

    const wrapper = await mountPage({ reportId: 'rpt-001' });
    await flushPromises();

    const body = wrapper.find('[data-testid="report-detail-body"]');
    expect(body.exists()).toBe(true);
    // Real Markdown headings/lists are emitted by the renderer.
    expect(body.find('h2').exists()).toBe(true);
    expect(body.find('ul').exists()).toBe(true);
    expect(body.find('li').exists()).toBe(true);
  });

  it('does NOT render raw HTML injected into the Markdown body (XSS guard)', async () => {
    const malicious =
      '## 标题\n\n<script>window.__pwned = true</script>\n<img src=x onerror="window.__pwned2=true">';
    vi.spyOn(reportsApi, 'getReports').mockResolvedValue(PAGE_RESPONSE);
    vi.spyOn(reportsApi, 'getReportDetail').mockResolvedValue({
      ...REPORT_DETAIL,
      bodyMarkdown: malicious,
    });

    const wrapper = await mountPage({ reportId: 'rpt-001' });
    await flushPromises();

    const body = wrapper.find('[data-testid="report-detail-body"]');
    // No <script> / <img> element should have been parsed into the DOM.
    expect(body.find('script').exists()).toBe(false);
    expect(body.find('img').exists()).toBe(false);
    // No inline event handler should be on any descendant.
    const html = (body.element as HTMLElement).innerHTML;
    expect(html).not.toContain('onerror');
    // And nothing executed.
    expect((globalThis as unknown as Record<string, unknown>).__pwned).toBeUndefined();
    expect((globalThis as unknown as Record<string, unknown>).__pwned2).toBeUndefined();
  });

  it('shows the unavailable placeholder when bodyMarkdown is missing', async () => {
    vi.spyOn(reportsApi, 'getReports').mockResolvedValue(PAGE_RESPONSE);
    vi.spyOn(reportsApi, 'getReportDetail').mockResolvedValue({
      ...REPORT_DETAIL,
      bodyMarkdown: undefined,
    });

    const wrapper = await mountPage({ reportId: 'rpt-001' });
    await flushPromises();

    expect(wrapper.find('[data-testid="report-detail-body-empty"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('数据不可用');
  });

  it('shows the unavailable placeholder for each missing field on the detail', async () => {
    vi.spyOn(reportsApi, 'getReports').mockResolvedValue(PAGE_RESPONSE);
    vi.spyOn(reportsApi, 'getReportDetail').mockResolvedValue({
      reportId: 'rpt-003',
      title: '仅 ID 的报告',
      reportType: 'AUDIT',
    });

    const wrapper = await mountPage({ reportId: 'rpt-003' });
    await flushPromises();

    const detail = wrapper.find('[data-testid="report-detail"]');
    expect(detail.exists()).toBe(true);
    // The frontend never fabricates missing values — every absent field
    // surfaces as 数据不可用.
    expect(detail.text()).toContain('数据不可用');
  });

  it('falls back to the raw body text when Markdown rendering throws', async () => {
    vi.spyOn(reportsApi, 'getReports').mockResolvedValue(PAGE_RESPONSE);
    vi.spyOn(reportsApi, 'getReportDetail').mockResolvedValue({
      ...REPORT_DETAIL,
      bodyMarkdown: '原始报告正文',
    });

    // Stub the renderer path by monkey-patching the global instance.
    // The page should detect the failure and surface the raw text.
    const original = MarkdownIt.prototype.render;
    MarkdownIt.prototype.render = function () {
      throw new Error('render failed');
    };
    try {
      const wrapper = await mountPage({ reportId: 'rpt-001' });
      await flushPromises();
      expect(wrapper.find('[data-testid="report-detail-body-fallback"]').exists()).toBe(true);
      expect(wrapper.text()).toContain('原始报告正文');
    } finally {
      MarkdownIt.prototype.render = original;
    }
  });
});

// ---------------------------------------------------------------------------
// Tests — row click also opens the detail drawer
// ---------------------------------------------------------------------------

describe('ReportCenter — row click', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('clicking a row opens the detail drawer for that report', async () => {
    vi.spyOn(reportsApi, 'getReports').mockResolvedValue(PAGE_RESPONSE);
    const detailSpy = vi
      .spyOn(reportsApi, 'getReportDetail')
      .mockResolvedValue(REPORT_DETAIL);

    const wrapper = await mountPage();
    await flushPromises();

    // Sanity: no detail yet.
    expect(wrapper.find('[data-testid="report-detail"]').exists()).toBe(false);

    await wrapper
      .find(`[data-testid="report-row-${REPORT_2.reportId}"]`)
      .trigger('click');
    await flushPromises();

    expect(detailSpy).toHaveBeenCalledWith('rpt-002');
    expect(wrapper.find('[data-testid="report-detail"]').exists()).toBe(true);
  });
});