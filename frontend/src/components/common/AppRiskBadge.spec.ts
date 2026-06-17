import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import AppRiskBadge, {
  type AppRiskLevel,
} from './AppRiskBadge.vue';

const LEVELS: AppRiskLevel[] = ['L0', 'L1', 'L2', 'L3', 'L4', 'Inject'];

describe('AppRiskBadge', () => {
  it.each<AppRiskLevel>(LEVELS)(
    'renders the %s code with the matching level class',
    (level) => {
      const wrapper = mount(AppRiskBadge, { props: { level } });
      const badge = wrapper.find('[data-testid^="app-risk-badge-"]');
      expect(badge.exists()).toBe(true);
      expect(badge.classes()).toContain(`kg-risk-badge--${level.toLowerCase()}`);
      expect(badge.text()).toContain(level);
    },
  );

  it('renders the Chinese label by default for L0..L4', () => {
    const wrapper = mount(AppRiskBadge, { props: { level: 'L2' } });
    expect(wrapper.text()).toContain('需确认');
  });

  it('renders the "提示词注入" label for Inject', () => {
    const wrapper = mount(AppRiskBadge, { props: { level: 'Inject' } });
    expect(wrapper.text()).toContain('提示词注入');
  });

  it('hides the label when compact=true', () => {
    const wrapper = mount(AppRiskBadge, { props: { level: 'L4', compact: true } });
    // The label is hidden, but the code still renders.
    expect(wrapper.text().trim()).toBe('L4');
  });

  it('hides the label when showLabel=false', () => {
    const wrapper = mount(AppRiskBadge, { props: { level: 'L3', showLabel: false } });
    expect(wrapper.text().trim()).toBe('L3');
  });

  it('falls back to a level-specific title attribute', () => {
    const wrapper = mount(AppRiskBadge, { props: { level: 'L4' } });
    const badge = wrapper.find('[data-testid^="app-risk-badge-"]');
    expect(badge.attributes('title')).toContain('直接拦截');
  });

  it('uses a custom tooltip when provided', () => {
    const wrapper = mount(AppRiskBadge, {
      props: { level: 'L2', tooltip: 'Restart service (custom)' },
    });
    const badge = wrapper.find('[data-testid^="app-risk-badge-"]');
    expect(badge.attributes('title')).toBe('Restart service (custom)');
  });

  it('applies the variant class', () => {
    const wrapper = mount(AppRiskBadge, {
      props: { level: 'L1', variant: 'solid' },
    });
    expect(wrapper.classes()).toContain('kg-risk-badge--solid');
  });

  it('applies the size class', () => {
    const wrapper = mount(AppRiskBadge, {
      props: { level: 'L1', size: 'sm' },
    });
    expect(wrapper.classes()).toContain('kg-risk-badge--sm');
  });
});
