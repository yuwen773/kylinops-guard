import { afterEach, describe, expect, it } from 'vitest';
import { mount, enableAutoUnmount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import ReportPreview from './index.vue';

enableAutoUnmount(afterEach);

const SAMPLE_BODY = [
  '系统健康检查摘要',
  'CPU 使用率 12%，正常。',
  '磁盘使用率 86%，需要关注。',
].join('\n\n');

function mountReport(overrides: Partial<{
  reportId: string;
  title: string;
  bodyMarkdown: string;
  auditId: string;
  createdAt: string;
}> = {}) {
  return mount(ReportPreview, {
    props: {
      reportId: 'rpt-001',
      title: '系统健康检查报告',
      bodyMarkdown: SAMPLE_BODY,
      auditId: 'audit-9001',
      createdAt: '2026-06-12T13:00:00Z',
      ...overrides,
    },
    global: { plugins: [ElementPlus] },
  });
}

describe('ReportPreview', () => {
  it('renders the title and metadata', () => {
    const wrapper = mountReport();
    expect(wrapper.text()).toContain('系统健康检查报告');
    expect(wrapper.text()).toContain('rpt-001');
    expect(wrapper.text()).toContain('audit-9001');
  });

  it('splits the body into paragraphs on blank lines and renders each as text', () => {
    const wrapper = mountReport();
    const body = wrapper.find('[data-testid="report-preview-body-rpt-001"]');
    const paragraphs = body.findAll('.report-preview-paragraph');
    expect(paragraphs).toHaveLength(3);
    expect(paragraphs[0].text()).toBe('系统健康检查摘要');
    expect(paragraphs[1].text()).toBe('CPU 使用率 12%，正常。');
    expect(paragraphs[2].text()).toBe('磁盘使用率 86%，需要关注。');
  });

  it('renders an empty-state placeholder when body is empty', () => {
    const wrapper = mountReport({ bodyMarkdown: '' });
    expect(wrapper.find('[data-testid="report-preview-empty-rpt-001"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('报告内容为空');
  });

  it('does not use v-html — HTML in the body is shown as literal text', () => {
    const body = '<img src=x onerror="window.__pwned = true">\n\n<script>window.__pwned = true</script>';
    const wrapper = mountReport({ bodyMarkdown: body });
    const node = wrapper.find('[data-testid="report-preview-body-rpt-001"]');
    // The literal text must be present, but no <img> / <script> element
    // should be parsed into the DOM.
    expect(node.find('img').exists()).toBe(false);
    expect(node.find('script').exists()).toBe(false);
    // Escaped text may contain the word "onerror", but no DOM element may
    // receive it as an executable attribute.
    expect((node.element as HTMLElement).querySelector('[onerror]')).toBeNull();
    // And globally, no DOM-side execution happened.
    expect((globalThis as unknown as Record<string, unknown>).__pwned).toBeUndefined();
  });

  it('renders single-paragraph body when there are no blank lines', () => {
    const wrapper = mountReport({ bodyMarkdown: '一行报告' });
    const paragraphs = wrapper.findAll('.report-preview-paragraph');
    expect(paragraphs).toHaveLength(1);
    expect(paragraphs[0].text()).toBe('一行报告');
  });

  it('omits the audit id row when auditId is not provided', () => {
    const wrapper = mountReport({ auditId: undefined });
    expect(wrapper.find('[data-testid="report-preview-audit-rpt-001"]').exists()).toBe(false);
  });
});
