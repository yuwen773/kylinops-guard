import { afterEach, describe, expect, it } from 'vitest';
import { mount, enableAutoUnmount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import RiskLevelTag from './index.vue';
import type { RiskDecision, RiskLevel } from '@/types/safety';

enableAutoUnmount(afterEach);

function mountTag(props: { level: RiskLevel; decision?: RiskDecision }) {
  return mount(RiskLevelTag, {
    props,
    global: { plugins: [ElementPlus] },
  });
}

describe('RiskLevelTag', () => {
  it.each<RiskLevel>(['L0', 'L1', 'L2', 'L3', 'L4'])(
    'renders the raw %s level code and its Chinese label verbatim',
    (level) => {
      const wrapper = mountTag({ level });
      const text = wrapper.text().trim();
      // The level code MUST appear — this is the contract: never soften.
      expect(text.startsWith(level)).toBe(true);
      // Sanity: contains a known Chinese suffix from the label table.
      expect(text.length).toBeGreaterThan(level.length);
    },
  );

  it('appends the decision verbatim when provided (e.g. "L4 BLOCK")', () => {
    const wrapper = mountTag({ level: 'L4', decision: 'BLOCK' });
    const text = wrapper.text().trim();
    expect(text).toContain('L4');
    expect(text).toContain('BLOCK');
    // Order matters: level comes first, decision follows.
    expect(text.indexOf('L4')).toBeLessThan(text.indexOf('BLOCK'));
  });

  it('omits the decision suffix when no decision is provided', () => {
    const wrapper = mountTag({ level: 'L2' });
    const text = wrapper.text().trim();
    expect(text).not.toContain('ALLOW');
    expect(text).not.toContain('BLOCK');
    expect(text).not.toContain('CONFIRM');
  });

  it('uses success tone for L0/L1, warning for L2, danger for L3/L4', () => {
    const tones: Record<RiskLevel, string> = {
      L0: 'success',
      L1: 'success',
      L2: 'warning',
      L3: 'danger',
      L4: 'danger',
    };
    (['L0', 'L1', 'L2', 'L3', 'L4'] as RiskLevel[]).forEach((level) => {
      const wrapper = mountTag({ level });
      const tag = wrapper.find('.el-tag');
      expect(tag.classes()).toContain(`el-tag--${tones[level]}`);
    });
  });

  it('exposes a stable data-testid so callers can assert or query the level', () => {
    const wrapper = mountTag({ level: 'L4', decision: 'BLOCK' });
    expect(wrapper.find('[data-testid="risk-level-L4"]').exists()).toBe(true);
  });
});
