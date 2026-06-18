import { ref, watchEffect, onMounted, computed } from 'vue';

export type KgTheme = 'dark' | 'light';
const STORAGE_KEY = 'kg-theme';
const ATTR_NAME = 'data-theme';

// Module-level singleton; all useTheme() callers share state
const theme = ref<KgTheme>('dark');

export function useTheme() {
  // Synchronous localStorage sync - the source of truth.
  // Resets the module-level singleton when persistence is empty or invalid,
  // which provides test isolation across cases that share the module ref.
  try {
    const saved = localStorage.getItem(STORAGE_KEY);
    if (saved === 'light' || saved === 'dark') {
      theme.value = saved;
    } else {
      theme.value = 'dark';
    }
  } catch {
    theme.value = 'dark';
  }

  onMounted(() => {
    try {
      const saved = localStorage.getItem(STORAGE_KEY);
      if (saved === 'light' || saved === 'dark') {
        theme.value = saved;
      } else {
        theme.value = 'dark';
      }
    } catch {
      theme.value = 'dark';
    }
  });

  // Sync to <html data-theme="...">
  watchEffect(() => {
    document.documentElement.setAttribute(ATTR_NAME, theme.value);
  });

  // Sync to localStorage
  watchEffect(() => {
    try {
      localStorage.setItem(STORAGE_KEY, theme.value);
    } catch {
      // localStorage disabled in private mode, fail silent
    }
  });

  function toggleTheme() {
    theme.value = theme.value === 'dark' ? 'light' : 'dark';
    // Synchronous flush - tests and DoD both expect instant DOM + persistence
    // without waiting for watchEffect's next scheduler tick.
    document.documentElement.setAttribute(ATTR_NAME, theme.value);
    try { localStorage.setItem(STORAGE_KEY, theme.value); } catch {}
  }

  const isDark = computed(() => theme.value === 'dark');
  const isLight = computed(() => theme.value === 'light');

  return { theme, toggleTheme, isDark, isLight };
}