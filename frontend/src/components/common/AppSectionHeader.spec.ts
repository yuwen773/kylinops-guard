import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import AppSectionHeader, { type AppSectionHeaderLevel } from './AppSectionHeader.vue';

describe('AppSectionHeader', () => {
  it('renders the title', () => {
    const wrapper = mount(AppSectionHeader, { props: { title: '系统总览' } });
    expect(wrapper.text()).toContain('系统总览');
  });

  it('renders the subtitle when provided', () => {
    const wrapper = mount(AppSectionHeader, {
      props: { title: '风险规则', subtitle: '当前加载的规则不可变快照' },
    });
    expect(wrapper.text()).toContain('当前加载的规则不可变快照');
  });

  it.each<AppSectionHeaderLevel>(['page', 'section'])(
    'exposes a stable data-testid for the %s level',
    (level) => {
      const wrapper = mount(AppSectionHeader, {
        props: { title: 'X', level },
      });
      expect(wrapper.find(`[data-testid="app-section-header-${level}"]`).exists()).toBe(true);
    },
  );

  it('renders the actions slot when provided', () => {
    const wrapper = mount(AppSectionHeader, {
      props: { title: 'X' },
      slots: { actions: '<button class="refresh">刷新</button>' },
    });
    expect(wrapper.find('.refresh').exists()).toBe(true);
  });
});
