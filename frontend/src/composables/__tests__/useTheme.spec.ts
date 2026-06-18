import { describe, it, expect, beforeEach, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import { useTheme } from '../useTheme';

describe('useTheme composable', () => {
  beforeEach(() => {
    localStorage.clear();
    document.documentElement.removeAttribute('data-theme');
  });

  function withTheme(setup: () => void) {
    return mount(defineComponent({
      setup() {
        setup();
        return () => h('div');
      },
    }));
  }

  it('defaults to dark theme', () => {
    let captured: ReturnType<typeof useTheme> | null = null;
    withTheme(() => {
      captured = useTheme();
    });
    expect(captured!.theme.value).toBe('dark');
  });

  it('toggleTheme() switches dark to light', () => {
    let captured: ReturnType<typeof useTheme> | null = null;
    withTheme(() => {
      captured = useTheme();
    });
    captured!.toggleTheme();
    expect(captured!.theme.value).toBe('light');
  });

  it('toggleTheme() switches light back to dark', () => {
    let captured: ReturnType<typeof useTheme> | null = null;
    withTheme(() => {
      captured = useTheme();
      captured!.theme.value = 'light';
    });
    captured!.toggleTheme();
    expect(captured!.theme.value).toBe('dark');
  });

  it('reflects theme into document.documentElement data-theme', async () => {
    let captured: ReturnType<typeof useTheme> | null = null;
    withTheme(() => {
      captured = useTheme();
    });
    await vi.dynamicImportSettled();
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
    captured!.toggleTheme();
    expect(document.documentElement.getAttribute('data-theme')).toBe('light');
  });

  it('persists to localStorage on change', async () => {
    let captured: ReturnType<typeof useTheme> | null = null;
    withTheme(() => {
      captured = useTheme();
    });
    await vi.dynamicImportSettled();
    captured!.toggleTheme();
    expect(localStorage.getItem('kg-theme')).toBe('light');
  });

  it('reads light from localStorage on mount', async () => {
    localStorage.setItem('kg-theme', 'light');
    let captured: ReturnType<typeof useTheme> | null = null;
    withTheme(() => {
      captured = useTheme();
    });
    await captured!.theme.value; // wait reactivity
    expect(captured!.theme.value).toBe('light');
  });

  it('ignores invalid localStorage value', async () => {
    localStorage.setItem('kg-theme', 'rainbow');
    let captured: ReturnType<typeof useTheme> | null = null;
    withTheme(() => {
      captured = useTheme();
    });
    expect(captured!.theme.value).toBe('dark');
  });

  it('exposes isDark and isLight computed', () => {
    let captured: ReturnType<typeof useTheme> | null = null;
    withTheme(() => {
      captured = useTheme();
    });
    expect(captured!.isDark.value).toBe(true);
    expect(captured!.isLight.value).toBe(false);
  });
});