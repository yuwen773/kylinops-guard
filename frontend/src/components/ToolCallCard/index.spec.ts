import { afterEach, describe, expect, it } from 'vitest';
import { mount, enableAutoUnmount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import ToolCallCard from './index.vue';
import type { ToolCallDisplayStatus } from '@/types/safety';

enableAutoUnmount(afterEach);

function mountCard(props: Partial<InstanceType<typeof ToolCallCard>['$props']> = {}) {
  return mount(ToolCallCard, {
    props: { toolName: 'cpu_status_tool', status: 'success', ...props },
    global: { plugins: [ElementPlus] },
  });
}

describe('ToolCallCard', () => {
  it('renders the tool name as the card title', () => {
    const wrapper = mountCard({ toolName: 'disk_usage_tool' });
    expect(wrapper.text()).toContain('disk_usage_tool');
  });

  it.each<ToolCallDisplayStatus>(['success', 'failed', 'timeout', 'blocked'])(
    'shows the %s status badge with the Chinese label',
    (status) => {
      const wrapper = mountCard({ status });
      const tag = wrapper.find(`[data-testid="tool-call-status-${status}"]`);
      expect(tag.exists()).toBe(true);
    },
  );

  it('shows the error message section for failed status', () => {
    const wrapper = mountCard({
      status: 'failed',
      errorMessage: '权限不足',
    });
    expect(wrapper.text()).toContain('错误信息');
    expect(wrapper.text()).toContain('权限不足');
  });

  it('shows the error message section for timeout status', () => {
    const wrapper = mountCard({
      status: 'timeout',
      errorMessage: '执行超过 3000ms',
    });
    expect(wrapper.text()).toContain('执行超过 3000ms');
  });

  it('does not render the error section on success even if errorMessage is set', () => {
    const wrapper = mountCard({
      status: 'success',
      errorMessage: 'should not be shown',
    });
    expect(wrapper.text()).not.toContain('错误信息');
  });

  it('renders input and output sections when provided', () => {
    const wrapper = mountCard({
      toolName: 'cpu_status_tool',
      status: 'success',
      input: '{"sample":1}',
      output: 'cpu usage: 12%',
    });
    expect(wrapper.text()).toContain('输入');
    expect(wrapper.text()).toContain('{"sample":1}');
    expect(wrapper.text()).toContain('输出');
    expect(wrapper.text()).toContain('cpu usage: 12%');
  });

  it('formats sub-second durations in ms and ≥ 1s durations in seconds', () => {
    const ms = mountCard({ status: 'success', durationMs: 750 });
    expect(ms.text()).toContain('750 ms');

    const sec = mountCard({ status: 'success', durationMs: 2500 });
    expect(sec.text()).toContain('2.50 s');
  });

  it('omits the duration line when durationMs is missing', () => {
    const wrapper = mountCard({ status: 'success' });
    expect(wrapper.text()).not.toContain('耗时');
  });

  it('exposes a stable data-testid for the tool name', () => {
    const wrapper = mountCard({ toolName: 'memory_status_tool' });
    expect(wrapper.find('[data-testid="tool-call-memory_status_tool"]').exists()).toBe(true);
  });
});
