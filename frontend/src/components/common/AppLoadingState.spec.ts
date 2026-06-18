import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import AppLoadingState, { type AppLoadingLayout } from './AppLoadingState.vue';

describe('AppLoadingState', () => {
  it('uses the default title "加载中…" when none is provided', () => {
    const wrapper = mount(AppLoadingState);
    expect(wrapper.text()).toContain('加载中');
  });

  it('renders a custom title and description', () => {
    const wrapper = mount(AppLoadingState, {
      props: { title: '正在采集系统指标…', description: '请稍候' },
    });
    expect(wrapper.text()).toContain('正在采集系统指标');
    expect(wrapper.text()).toContain('请稍候');
  });

  it.each<AppLoadingLayout>(['block', 'inline'])(
    'exposes a stable data-testid for the %s layout',
    (layout) => {
      const wrapper = mount(AppLoadingState, { props: { layout } });
      expect(wrapper.find(`[data-testid="app-loading-state-${layout}"]`).exists()).toBe(true);
    },
  );

  it('sets role="status" for assistive technology', () => {
    const wrapper = mount(AppLoadingState);
    expect(wrapper.attributes('role')).toBe('status');
  });
});
