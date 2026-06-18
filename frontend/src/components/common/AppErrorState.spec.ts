import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import AppErrorState, { type AppErrorVariant } from './AppErrorState.vue';

describe('AppErrorState', () => {
  it('uses the default transient copy when no props are provided', () => {
    const wrapper = mount(AppErrorState);
    expect(wrapper.text()).toContain('加载失败');
    expect(wrapper.find('[data-testid="app-error-state-transient"]').exists()).toBe(true);
  });

  it.each<AppErrorVariant>(['transient', 'fatal'])(
    'exposes a stable data-testid for the %s variant',
    (variant) => {
      const wrapper = mount(AppErrorState, { props: { variant } });
      expect(wrapper.find(`[data-testid="app-error-state-${variant}"]`).exists()).toBe(true);
    },
  );

  it('renders the action slot when provided', () => {
    const wrapper = mount(AppErrorState, {
      slots: { action: '<button class="retry-btn">重试</button>' },
    });
    expect(wrapper.find('.retry-btn').exists()).toBe(true);
  });

  it('prefers custom title/description over the variant defaults', () => {
    const wrapper = mount(AppErrorState, {
      props: { title: '自定义失败', description: '自定义说明' },
    });
    expect(wrapper.text()).toContain('自定义失败');
    expect(wrapper.text()).toContain('自定义说明');
    expect(wrapper.text()).not.toContain('加载失败');
  });

  it('sets role="alert" for assistive technology', () => {
    const wrapper = mount(AppErrorState);
    expect(wrapper.attributes('role')).toBe('alert');
  });
});
