import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import AppEmptyState, { type AppEmptyVariant } from './AppEmptyState.vue';

describe('AppEmptyState', () => {
  it('renders the variant-specific default title and description', () => {
    const wrapper = mount(AppEmptyState, { props: { variant: 'no-tool' } });
    expect(wrapper.text()).toContain('暂无注册工具');
    expect(wrapper.text()).toContain('请联系管理员');
  });

  it('uses the default variant when none is provided', () => {
    const wrapper = mount(AppEmptyState);
    expect(wrapper.text()).toContain('暂无数据');
    expect(wrapper.find('[data-testid="app-empty-state-default"]').exists()).toBe(true);
  });

  it.each<AppEmptyVariant>([
    'default', 'no-audit', 'no-tool', 'no-event', 'no-report', 'unavailable',
  ])('exposes a stable data-testid for the %s variant', (variant) => {
    const wrapper = mount(AppEmptyState, { props: { variant } });
    expect(wrapper.find(`[data-testid="app-empty-state-${variant}"]`).exists()).toBe(true);
  });

  it('prefers custom title/description over the variant defaults', () => {
    const wrapper = mount(AppEmptyState, {
      props: {
        variant: 'no-tool',
        title: '自定义标题',
        description: '自定义说明',
      },
    });
    expect(wrapper.text()).toContain('自定义标题');
    expect(wrapper.text()).toContain('自定义说明');
    expect(wrapper.text()).not.toContain('暂无注册工具');
  });

  it('renders the action slot when provided', () => {
    const wrapper = mount(AppEmptyState, {
      props: { variant: 'no-event' },
      slots: { action: '<button class="refresh-btn">刷新</button>' },
    });
    expect(wrapper.find('.refresh-btn').exists()).toBe(true);
  });
});
