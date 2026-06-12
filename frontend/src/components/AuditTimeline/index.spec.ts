import { afterEach, describe, expect, it } from 'vitest';
import { mount, enableAutoUnmount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import AuditTimeline, { type AuditTimelineEntry } from './index.vue';

enableAutoUnmount(afterEach);

function mountTimeline(entries: ReadonlyArray<AuditTimelineEntry>) {
  return mount(AuditTimeline, {
    props: { entries },
    global: { plugins: [ElementPlus] },
  });
}

describe('AuditTimeline', () => {
  it('renders an empty-state placeholder when entries is empty', () => {
    const wrapper = mountTimeline([]);
    expect(wrapper.find('[data-testid="audit-timeline-empty"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('数据不可用');
  });

  it('renders entries in the canonical stage order', () => {
    const entries: AuditTimelineEntry[] = [
      { stage: 'answer', timestamp: '2026-06-12T10:05:00Z', summary: '落库回复' },
      { stage: 'risk-check', timestamp: '2026-06-12T10:01:00Z', summary: '前置风险校验' },
      { stage: 'execution', timestamp: '2026-06-12T10:04:00Z', summary: 'SafeExecutor 执行' },
      { stage: 'tool-call', timestamp: '2026-06-12T10:02:00Z', summary: '调用 3 个工具' },
      { stage: 'confirmation', timestamp: '2026-06-12T10:03:00Z', summary: '用户确认' },
    ];
    const wrapper = mountTimeline(entries);

    // Each canonical stage should be present.
    for (const stage of ['risk-check', 'tool-call', 'confirmation', 'execution', 'answer']) {
      expect(wrapper.find(`[data-testid="audit-timeline-entry-${stage}"]`).exists()).toBe(true);
    }

    // Verify the rendered order by walking the DOM and collecting summaries.
    const summaries = wrapper.findAll('.audit-timeline-summary').map((n) => n.text());
    expect(summaries).toEqual([
      '前置风险校验',
      '调用 3 个工具',
      '用户确认',
      'SafeExecutor 执行',
      '落库回复',
    ]);
  });

  it('does not synthesise missing stages — only backend-provided entries appear', () => {
    // L0/L1 flows have no confirmation / execution stages. The timeline
    // must show ONLY the stages the backend actually recorded, never
    // phantom confirm/execute steps.
    const entries: AuditTimelineEntry[] = [
      { stage: 'risk-check', timestamp: '2026-06-12T11:00:00Z', summary: 'L0 ALLOW' },
      { stage: 'tool-call', timestamp: '2026-06-12T11:00:01Z', summary: 'cpu_status_tool' },
      { stage: 'answer', timestamp: '2026-06-12T11:00:02Z', summary: '当前 CPU 使用率 12%' },
    ];
    const wrapper = mountTimeline(entries);
    expect(wrapper.find('[data-testid="audit-timeline-entry-confirmation"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="audit-timeline-entry-execution"]').exists()).toBe(false);
  });

  it('renders the Chinese label for canonical stages and falls back to raw for unknown', () => {
    const entries: AuditTimelineEntry[] = [
      { stage: 'risk-check', timestamp: '2026-06-12T12:00:00Z', summary: 'X' },
      { stage: 'custom-stage', timestamp: '2026-06-12T12:00:01Z', summary: 'Y' },
    ];
    const wrapper = mountTimeline(entries);
    const stages = wrapper.findAll('.audit-timeline-stage').map((n) => n.text());
    expect(stages).toContain('风险校验');
    expect(stages).toContain('custom-stage');
  });
});
