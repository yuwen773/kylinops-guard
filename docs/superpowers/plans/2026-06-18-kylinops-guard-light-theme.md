# Light Theme G1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a manual-toggle light theme (G1 — green-tinted bg + blue primary) to KylinOps Guard frontend, on top of the existing dark "Command Center" theme, with zero regression to the dark baseline.

**Architecture:** Shared semantic `--kg-*` tokens + `:root[data-theme="light"]` override block. Element Plus `--el-*` variables inherit from `--kg-*` via CSS cascade (no remap needed). One Vue composable (`useTheme`) holds reactive theme state, syncs to `document.documentElement[data-theme]` and `localStorage['kg-theme']`. AppLayout exposes a single Sun/Moon icon button. Zero changes to existing component code.

**Tech Stack:** Vue 3 (Composition API + `<script setup>`) · TypeScript · Vite · Element Plus 2.x · Vitest · Playwright · Plain CSS (no Tailwind / SCSS / CSS-in-JS)

**Source Spec:** `docs/superpowers/specs/2026-06-18-kylinops-guard-light-theme-design.md` (commit `8b344de`)

---

## File Map

### New files
- `frontend/src/composables/useTheme.ts` — Vue composable (module-level singleton state)
- `frontend/src/composables/__tests__/useTheme.spec.ts` — Vitest unit tests
- `frontend/tests/e2e/theme-toggle.spec.ts` — Playwright E2E
- `frontend/tests/e2e/screenshots/dark/*.png` — 4 PNGs moved from `tests/e2e/screenshots/` root
- `frontend/tests/e2e/screenshots/light/*.png` — 4 new PNGs

### Modified files
- `frontend/src/styles/index.css` — add `:root[data-theme="light"]` block (~120 lines) + scan-line `animation-play-state: paused` (1 line)
- `frontend/src/layouts/AppLayout.vue` — add 1 toggle button + 3 imports + 1 computed
- `frontend/src/pages/Login/index.vue` — 2 small style additions (input wrapper + ambient orb override)
- `frontend/tests/e2e/screenshot-ui-01.mjs` — wrap loop in `themes` array + CLI arg
- `frontend/package.json` — add 2 npm scripts (`screenshot:ui01:dark`, `screenshot:ui01:light`)

### Unchanged (verified)
- 7 page .vue files (ChatConsole, Dashboard, ToolCenter, SecurityCenter, AuditLog, ReportCenter, Login — only Login style touched)
- 13 component .vue files (no changes)
- `frontend/tests/e2e/demo-live.spec.ts`, `frontend/tests/e2e/demo-mock.spec.ts` (data-testid unchanged → 0 touch)

---

## Task 0: Baseline Verification

**Files:** none (read-only)

- [ ] **Step 1: Verify clean working tree**

Run: `cd "D:/Work/code/kylin-ops-ui-01" && git status --short`
Expected: spec commit `8b344de` listed (or empty if not yet pushed); no other uncommitted changes

- [ ] **Step 2: Verify existing tests pass before any change**

Run: `cd "D:/Work/code/kylin-ops-ui-01/frontend" && npm run test:unit -- --run 2>&1 | tail -5`
Expected: `Test Files ... | 190 passed (190)` (or current baseline)

- [ ] **Step 3: Verify existing E2E (mock mode) passes**

Run: `cd "D:/Work/code/kylin-ops-ui-01/frontend" && npx playwright test tests/e2e/demo-mock.spec.ts 2>&1 | tail -5`
Expected: `19 passed (19)` (or current baseline, with skips)

If either fails, **STOP** — do not proceed. Fix the baseline first.

---

## Task 1: useTheme Composable (TDD)

**Files:**
- Create: `frontend/src/composables/__tests__/useTheme.spec.ts`
- Create: `frontend/src/composables/useTheme.ts`

- [ ] **Step 1: Create test file with failing test**

Create `frontend/src/composables/__tests__/useTheme.spec.ts`:

```ts
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd "D:/Work/code/kylin-ops-ui-01/frontend" && npx vitest run src/composables/__tests__/useTheme.spec.ts 2>&1 | tail -20`
Expected: FAIL with "Cannot find module '../useTheme'"

- [ ] **Step 3: Create the composable**

Create `frontend/src/composables/useTheme.ts`:

```ts
import { ref, watchEffect, onMounted, computed } from 'vue';

export type KgTheme = 'dark' | 'light';
const STORAGE_KEY = 'kg-theme';
const ATTR_NAME = 'data-theme';

// Module-level singleton — all useTheme() callers share state
const theme = ref<KgTheme>('dark');

export function useTheme() {
  onMounted(() => {
    const saved = localStorage.getItem(STORAGE_KEY);
    if (saved === 'light' || saved === 'dark') {
      theme.value = saved;
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
      // localStorage disabled in private mode — fail silent
    }
  });

  function toggleTheme() {
    theme.value = theme.value === 'dark' ? 'light' : 'dark';
  }

  const isDark = computed(() => theme.value === 'dark');
  const isLight = computed(() => theme.value === 'light');

  return { theme, toggleTheme, isDark, isLight };
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd "D:/Work/code/kylin-ops-ui-01/frontend" && npx vitest run src/composables/__tests__/useTheme.spec.ts 2>&1 | tail -20`
Expected: `Tests  8 passed (8)`

- [ ] **Step 5: Commit**

```bash
cd "D:/Work/code/kylin-ops-ui-01" && git add frontend/src/composables/useTheme.ts frontend/src/composables/__tests__/useTheme.spec.ts && git -c user.name="KylinOps Dev" -c user.email="dev@kylinops.local" commit -m "feat(frontend): add useTheme composable

- Module-level singleton ref
- onMounted reads localStorage 'kg-theme'
- watchEffect syncs document.documentElement[data-theme] + localStorage
- 8 unit tests (default, toggle, persistence, localStorage rehydration, invalid value, isDark/isLight)"
```

---

## Task 2: Add Light Theme CSS Token Block

**Files:**
- Modify: `frontend/src/styles/index.css:998` (append at end of file, after `prefers-reduced-motion` block)

- [ ] **Step 1: Append the light theme block**

Open `frontend/src/styles/index.css`, find the line after `@media (prefers-reduced-motion: reduce) { ... }` block (file ends at line 997). Append:

```css

/* ============================================================================
 * Light theme (G1) — green-tinted bg + blue primary.
 * Activated by `<html data-theme="light">`, set by useTheme composable.
 * All values override :root defaults. Element Plus --el-* vars inherit
 * automatically via the :root --el-* -> --kg-* mapping above.
 * ========================================================================== */

:root[data-theme="light"] {
  /* Surface */
  --kg-color-bg: #f0fdf4;
  --kg-color-surface: #ffffff;
  --kg-color-surface-soft: #f7fef9;
  --kg-color-surface-mute: #ecfdf5;
  --kg-color-surface-code: #f7fef9;

  /* Border */
  --kg-color-border: rgba(21, 128, 61, 0.18);
  --kg-color-border-strong: rgba(21, 128, 61, 0.32);
  --kg-color-border-mute: rgba(21, 128, 61, 0.10);

  /* Text */
  --kg-color-text-primary: #14532d;
  --kg-color-text-secondary: #166534;
  --kg-color-text-mute: #4d7c5b;
  --kg-color-text-placeholder: #6b7280;
  --kg-color-text-inverse: #ffffff;
  --kg-color-text-on-risk: #ffffff;

  /* Brand / Primary (deepened for AA on light bg) */
  --kg-color-primary: #2563eb;
  --kg-color-primary-hover: #1d4ed8;
  --kg-color-primary-soft: rgba(37, 99, 235, 0.12);
  --kg-color-primary-strong: #1e40af;

  /* Accent (decorative glow: cyan -> green) */
  --kg-color-accent: #15803d;
  --kg-color-accent-hover: #166534;
  --kg-color-accent-soft: rgba(21, 128, 61, 0.10);
  --kg-color-accent-glow: 0 0 16px rgba(21, 128, 61, 0.10);

  /* Risk spectrum (all deepened to pass WCAG AA on #f0fdf4) */
  --kg-color-risk-l0: #0369a1;
  --kg-color-risk-l0-soft: rgba(3, 105, 161, 0.12);
  --kg-color-risk-l1: #15803d;
  --kg-color-risk-l1-soft: rgba(21, 128, 61, 0.12);
  --kg-color-risk-l2: #b45309;
  --kg-color-risk-l2-soft: rgba(180, 83, 9, 0.12);
  --kg-color-risk-l3: #c2410c;
  --kg-color-risk-l3-soft: rgba(194, 65, 12, 0.14);
  --kg-color-risk-l4: #b91c1c;
  --kg-color-risk-l4-soft: rgba(185, 28, 28, 0.14);
  --kg-color-risk-inject: #7e22ce;
  --kg-color-risk-inject-soft: rgba(126, 34, 206, 0.14);

  /* State */
  --kg-color-success: #15803d;
  --kg-color-success-soft: rgba(21, 128, 61, 0.12);
  --kg-color-warning: #b45309;
  --kg-color-warning-soft: rgba(180, 83, 9, 0.12);
  --kg-color-danger: #b91c1c;
  --kg-color-danger-soft: rgba(185, 28, 28, 0.12);
  --kg-color-info: #0369a1;
  --kg-color-info-soft: rgba(3, 105, 161, 0.12);

  /* Glow (muted) */
  --kg-glow-primary: 0 0 16px rgba(37, 99, 235, 0.10);
  --kg-glow-accent: 0 0 16px rgba(21, 128, 61, 0.08);
  --kg-glow-danger: 0 0 16px rgba(185, 28, 28, 0.10);
  --kg-glow-warning: 0 0 16px rgba(180, 83, 9, 0.08);

  /* Glass (white-frosted, no backdrop-filter needed in light) */
  --kg-glass-bg: rgba(255, 255, 255, 0.85);
  --kg-glass-border: rgba(255, 255, 255, 0.6);
  --kg-glass-shadow: 0 4px 16px rgba(0, 0, 0, 0.06);

  /* Shadow (light) */
  --kg-shadow-card: 0 1px 3px rgba(0, 0, 0, 0.05), 0 4px 12px rgba(0, 0, 0, 0.04);
  --kg-shadow-elevated: 0 4px 16px rgba(0, 0, 0, 0.08);
  --kg-shadow-inset: inset 0 1px 0 rgba(255, 255, 255, 0.6);
}
```

- [ ] **Step 2: Manual verify (visual)**

Run: `cd "D:/Work/code/kylin-ops-ui-01/frontend" && npm run dev`
Then open browser DevTools console and run:
```js
document.documentElement.setAttribute('data-theme', 'light');
```
Expected: entire page switches to light theme (light green bg, white cards, deep blue brand mark stays).

Then:
```js
document.documentElement.removeAttribute('data-theme');
```
Expected: reverts to dark.

Stop the dev server (Ctrl+C).

- [ ] **Step 3: Run existing unit tests to verify no regression**

Run: `cd "D:/Work/code/kylin-ops-ui-01/frontend" && npm run test:unit -- --run 2>&1 | tail -5`
Expected: 190/190 pass (no change)

- [ ] **Step 4: Commit**

```bash
cd "D:/Work/code/kylin-ops-ui-01" && git add frontend/src/styles/index.css && git -c user.name="KylinOps Dev" -c user.email="dev@kylinops.local" commit -m "feat(frontend): add light theme G1 token block

- 30+ --kg-* token overrides under :root[data-theme=light]
- All risk colors deepened for WCAG AA on #f0fdf4
- Accent shifts cyan->green for NOC decoration glow
- Element Plus --el-* inherits via existing :root mapping (no remap)
- Shadow + Glass reset to light patterns (white-frosted, no backdrop)"
```

---

## Task 3: Fix Scan-Line to be Opt-In

**Files:**
- Modify: `frontend/src/styles/index.css` (one line: add `animation-play-state: paused`)

- [ ] **Step 1: Locate the rule**

In `frontend/src/styles/index.css`, find the `.kg-scan-overlay::after` block. Current content (~line 925-941):
```css
.kg-scan-overlay::after {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 2px;
  background: linear-gradient(90deg, transparent, rgba(6, 182, 212, 0.15), transparent);
  animation: kg-scanLine 4s linear infinite;
  pointer-events: none;
}
```

- [ ] **Step 2: Add `animation-play-state: paused` and the active modifier**

Replace that block with:
```css
.kg-scan-overlay::after {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 2px;
  background: linear-gradient(90deg, transparent, rgba(6, 182, 212, 0.15), transparent);
  animation: kg-scanLine 4s linear infinite;
  animation-play-state: paused; /* default: do not run; opt-in via --active modifier */
  pointer-events: none;
}

.kg-scan-overlay--active::after {
  animation-play-state: running;
}
```

For the light theme, also tint the scan line color. **Append inside the `:root[data-theme="light"]` block** (added in Task 2):
```css
  --kg-scan-line: rgba(21, 128, 61, 0.20);
```

And change the `.kg-scan-overlay::after` background to use the new token:
```css
.kg-scan-overlay::after {
  /* ... same as above, but: */
  background: linear-gradient(90deg, transparent, var(--kg-scan-line, rgba(6, 182, 212, 0.15)), transparent);
}
```

Then add `--kg-scan-line: rgba(6, 182, 212, 0.15);` to the dark `:root` block (after `--kg-glass-shadow`).

- [ ] **Step 3: Run existing tests**

Run: `cd "D:/Work/code/kylin-ops-ui-01/frontend" && npm run test:unit -- --run 2>&1 | tail -5`
Expected: 190/190 pass

- [ ] **Step 4: Commit**

```bash
cd "D:/Work/code/kylin-ops-ui-01" && git add frontend/src/styles/index.css && git -c user.name="KylinOps Dev" -c user.email="dev@kylinops.local" commit -m "fix(frontend): make .kg-scan-overlay opt-in

- Default animation-play-state: paused (was: always running)
- .kg-scan-overlay--active modifier triggers running
- Rationale: design-taste-frontend §5 'Motion must be motivated';
  trust-first product should not loop perpetual ambient motion.
- New --kg-scan-line token for theme-aware color (cyan in dark, green in light)"
```

---

## Task 4: Fix Login.vue Input Wrapper

**Files:**
- Modify: `frontend/src/pages/Login/index.vue:300-313` (input wrapper style block)

- [ ] **Step 1: Locate the input wrapper block**

In `frontend/src/pages/Login/index.vue`, find the scoped style block:
```css
.login-card :deep(.el-input__wrapper) {
  background: rgba(0, 0, 0, 0.2);
  border: 1px solid var(--kg-color-border);
  box-shadow: none;
}
```
(~line 300-304)

- [ ] **Step 2: Replace hard-coded rgba with token + add light theme override**

Replace with:
```css
.login-card :deep(.el-input__wrapper) {
  background: var(--kg-color-surface-mute);
  border: 1px solid var(--kg-color-border);
  box-shadow: none;
}

:root[data-theme="light"] .login-card :deep(.el-input__wrapper) {
  background: rgba(255, 255, 255, 0.7);
  border-color: var(--kg-color-border-strong);
}
```

- [ ] **Step 3: Manual verify**

Run: `cd "D:/Work/code/kylin-ops-ui-01/frontend" && npm run dev`
- Load `/login` in dark mode → input field bg should be muted dark surface
- DevTools console: `document.documentElement.setAttribute('data-theme', 'light')`
- Input field bg should become semi-transparent white
Stop dev server (Ctrl+C).

- [ ] **Step 4: Run existing tests**

Run: `cd "D:/Work/code/kylin-ops-ui-01/frontend" && npm run test:unit -- --run 2>&1 | tail -5`
Expected: 190/190 pass

- [ ] **Step 5: Commit**

```bash
cd "D:/Work/code/kylin-ops-ui-01" && git add frontend/src/pages/Login/index.vue && git -c user.name="KylinOps Dev" -c user.email="dev@kylinops.local" commit -m "fix(frontend): tokenize Login input wrapper bg

- Was: hard-coded rgba(0,0,0,0.2) — wrong for light theme
- Now: --kg-color-surface-mute (auto dark/light)
- Light theme override: rgba(255,255,255,0.7) for glassy white input"
```

---

## Task 5: Fix Login.vue Ambient Orbs for Light

**Files:**
- Modify: `frontend/src/pages/Login/index.vue:184-207` (ambient orb style block)

- [ ] **Step 1: Locate the orb block**

In `frontend/src/pages/Login/index.vue`, find:
```css
.login-ambient--1 {
  width: 400px;
  height: 400px;
  background: radial-gradient(circle, rgba(59, 130, 246, 0.2), transparent);
  top: -100px;
  right: -100px;
  animation: kg-pulseGlow 6s ease-in-out infinite;
}

.login-ambient--2 {
  width: 350px;
  height: 350px;
  background: radial-gradient(circle, rgba(6, 182, 212, 0.15), transparent);
  bottom: -80px;
  left: -80px;
  animation: kg-pulseGlow 8s ease-in-out infinite reverse;
}
```

- [ ] **Step 2: Append light theme override at the end of the scoped style block**

Add (after `.login-footer-text` block, the last rule in the scoped style):
```css
/* Light theme: muted orbs (cyan+blue too vivid for light bg) */
:root[data-theme="light"] .login-ambient--1 {
  background: radial-gradient(circle, rgba(21, 128, 61, 0.10), transparent);
  animation-duration: 12s;
}

:root[data-theme="light"] .login-ambient--2 {
  background: radial-gradient(circle, rgba(37, 99, 235, 0.08), transparent);
  animation-duration: 10s;
}
```

- [ ] **Step 3: Manual verify**

Run: `cd "D:/Work/code/kylin-ops-ui-01/frontend" && npm run dev`
- `/login` in dark → cyan+blue soft glow
- DevTools: `document.documentElement.setAttribute('data-theme', 'light')` → green+blue softer, slower
Stop dev server (Ctrl+C).

- [ ] **Step 4: Run existing tests**

Run: `cd "D:/Work/code/kylin-ops-ui-01/frontend" && npm run test:unit -- --run 2>&1 | tail -5`
Expected: 190/190 pass

- [ ] **Step 5: Commit**

```bash
cd "D:/Work/code/kylin-ops-ui-01" && git add frontend/src/pages/Login/index.vue && git -c user.name="KylinOps Dev" -c user.email="dev@kylinops.local" commit -m "fix(frontend): tune Login ambient orbs for light theme

- Was: cyan+blue radial glow (works on dark, jarring on light)
- Now: green+blue, lower opacity, slower animation in light mode
- Dark mode unchanged"
```

---

## Task 6: Add Theme Toggle Button to AppLayout

**Files:**
- Modify: `frontend/src/layouts/AppLayout.vue:1-55` (script setup)
- Modify: `frontend/src/layouts/AppLayout.vue:65-79` (template)
- Modify: `frontend/src/layouts/AppLayout.vue:195-200` (style — add `.app-theme-toggle`)

- [ ] **Step 1: Add imports and useTheme in `<script setup>`**

In `frontend/src/layouts/AppLayout.vue`, find the imports block (line 9-16):
```ts
import {
  ChatDotSquare,
  Monitor,
  Tools,
  WarningFilled,
  Document,
  DataAnalysis,
} from '@element-plus/icons-vue';
```

**Replace** with:
```ts
import { computed } from 'vue';
import {
  ChatDotSquare,
  Monitor,
  Tools,
  WarningFilled,
  Document,
  DataAnalysis,
  Sunny,
  Moon,
} from '@element-plus/icons-vue';
import { useTheme } from '@/composables/useTheme';
```

(Removing the `ref` import — keep the existing `computed` from line 5)

Find (line 41-42):
```ts
const username = computed<string>(() => getSession()?.username ?? '');
```

**Append** after:
```ts
const { theme, toggleTheme } = useTheme();
const themeIcon = computed(() => (theme.value === 'dark' ? Sunny : Moon));
const themeAriaLabel = computed(() =>
  theme.value === 'dark' ? '切换到亮色主题' : '切换到暗色主题',
);
```

- [ ] **Step 2: Add the toggle button to the template**

In the template (line 65-79), find the username + logout block:
```vue
      <span v-if="username" class="app-user" data-testid="app-user">{{ username }}</span>
      <el-button
        size="small"
        type="default"
        plain
        :loading="isLoggingOut"
        class="app-logout"
        data-testid="app-logout"
        @click="handleLogout"
      >
        登出
      </el-button>
```

**Replace** with:
```vue
      <span v-if="username" class="app-user" data-testid="app-user">{{ username }}</span>
      <el-button
        size="small"
        circle
        :icon="themeIcon"
        class="app-theme-toggle"
        data-testid="app-theme-toggle"
        :aria-label="themeAriaLabel"
        @click="toggleTheme"
      />
      <el-button
        size="small"
        type="default"
        plain
        :loading="isLoggingOut"
        class="app-logout"
        data-testid="app-logout"
        @click="handleLogout"
      >
        登出
      </el-button>
```

- [ ] **Step 3: Add a tiny style for the toggle button**

Find the `.app-logout { margin-left: var(--kg-space-2); }` block (~line 196-198) and **append** after it:
```css
.app-theme-toggle {
  margin-left: var(--kg-space-2);
  color: var(--kg-color-text-secondary);
}

.app-theme-toggle:hover {
  color: var(--kg-color-text-primary);
  background-color: var(--kg-color-surface-soft);
}
```

- [ ] **Step 4: Manual verify**

Run: `cd "D:/Work/code/kylin-ops-ui-01/frontend" && npm run dev`
- Log in (any creds) → top bar shows Sun icon button
- Click it → entire app flips light/dark
- Reload page → last choice persists
Stop dev server (Ctrl+C).

- [ ] **Step 5: Run existing tests**

Run: `cd "D:/Work/code/kylin-ops-ui-01/frontend" && npm run test:unit -- --run 2>&1 | tail -5`
Expected: 190/190 pass (AppLayout test if any should still pass — data-testid "app-user" and "app-logout" unchanged; new "app-theme-toggle" added but no test asserts on its absence)

- [ ] **Step 6: Commit**

```bash
cd "D:/Work/code/kylin-ops-ui-01" && git add frontend/src/layouts/AppLayout.vue && git -c user.name="KylinOps Dev" -c user.email="dev@kylinops.local" commit -m "feat(frontend): add theme toggle button to AppLayout

- 32x32 circle button between username and logout
- Sunny icon when dark (action: switch to light)
- Moon icon when light (action: switch to dark)
- aria-label localized zh-CN
- data-testid 'app-theme-toggle' for E2E
- useTheme() composable wired (toggleTheme, themeIcon computed)"
```

---

## Task 7: Move Existing 4 PNGs to dark/ Subdir

**Files:**
- Move: `tests/e2e/screenshots/ui-01-01-applayout-chat.png` → `tests/e2e/screenshots/dark/`
- Move: `tests/e2e/screenshots/ui-01-02-dashboard.png` → `tests/e2e/screenshots/dark/`
- Move: `tests/e2e/screenshots/ui-01-03-security-center.png` → `tests/e2e/screenshots/dark/`
- Move: `tests/e2e/screenshots/ui-01-04-tool-center.png` → `tests/e2e/screenshots/dark/`

- [ ] **Step 1: Create dark/ and light/ subdirs**

Run:
```bash
cd "D:/Work/code/kylin-ops-ui-01" && mkdir -p frontend/tests/e2e/screenshots/dark frontend/tests/e2e/screenshots/light
```

- [ ] **Step 2: Move the 4 existing PNGs**

Run:
```bash
cd "D:/Work/code/kylin-ops-ui-01" && git mv frontend/tests/e2e/screenshots/ui-01-01-applayout-chat.png frontend/tests/e2e/screenshots/dark/ && git mv frontend/tests/e2e/screenshots/ui-01-02-dashboard.png frontend/tests/e2e/screenshots/dark/ && git mv frontend/tests/e2e/screenshots/ui-01-03-security-center.png frontend/tests/e2e/screenshots/dark/ && git mv frontend/tests/e2e/screenshots/ui-01-04-tool-center.png frontend/tests/e2e/screenshots/dark/
```

- [ ] **Step 3: Verify moves**

Run: `cd "D:/Work/code/kylin-ops-ui-01" && ls frontend/tests/e2e/screenshots/dark/ && echo "--- root ---" && ls frontend/tests/e2e/screenshots/ | grep -v dark | grep -v light`
Expected (dark/): 4 PNG files
Expected (root): empty

- [ ] **Step 4: Commit**

```bash
cd "D:/Work/code/kylin-ops-ui-01" && git add -A frontend/tests/e2e/screenshots/ && git -c user.name="KylinOps Dev" -c user.email="dev@kylinops.local" commit -m "chore(frontend): move existing 4 dark-theme PNGs to dark/ subdir

- Pre-req for theme-aware screenshot script (light theme will live in light/)"
```

---

## Task 8: Extend screenshot-ui-01.mjs to Capture Both Themes

**Files:**
- Modify: `frontend/tests/e2e/screenshot-ui-01.mjs` (full rewrite — see Step 1)

- [ ] **Step 1: Replace the file contents**

Replace `frontend/tests/e2e/screenshot-ui-01.mjs` entirely with:

```js
// UI-01 screenshot harness.
//
// Captures Dashboard / SecurityCenter / ToolCenter / ChatConsole (AppLayout)
// at 1280x720 demo resolution with mocked /api/** responses. Writes PNGs to
// `tests/e2e/screenshots/{theme}/ui-01-*.png` for each requested theme.
//
// Run via the npm script:
//   npm run screenshot:ui01            (dark, default, backward-compat)
//   npm run screenshot:ui01:dark
//   npm run screenshot:ui01:light
//   npm run screenshot:ui01:all        (both themes)
//
// Or directly:
//   node tests/e2e/screenshot-ui-01.mjs
//   node tests/e2e/screenshot-ui-01.mjs --theme light

import { chromium } from 'playwright';
import { mkdir, rename } from 'node:fs/promises';
import { dirname, resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const OUT_DIR = resolve(__dirname, 'screenshots');
const BASE_URL = process.env.E2E_BASE_URL ?? 'http://127.0.0.1:5173';

const PAGES = [
  { id: 'app-layout', path: '/chat', file: 'ui-01-01-applayout-chat.png' },
  { id: 'dashboard', path: '/dashboard', file: 'ui-01-02-dashboard.png' },
  { id: 'security', path: '/security', file: 'ui-01-03-security-center.png' },
  { id: 'tools', path: '/tools', file: 'ui-01-04-tool-center.png' },
];

// ---- Theme selection ------------------------------------------------------
// --theme=light / --theme=dark / --theme=all
const themeArg = (process.argv.find((a) => a.startsWith('--theme=')) ?? '--theme=dark')
  .split('=')[1];
const themes = themeArg === 'all' ? ['dark', 'light'] : [themeArg];

// ---- Mock /api/** responses (identical to before) -------------------------
const now = '2026-06-17T08:00:00Z';

await mkdir(OUT_DIR, { recursive: true });
for (const t of themes) {
  await mkdir(join(OUT_DIR, t), { recursive: true });
}

const browser = await chromium.launch({ channel: 'chromium' });
const context = await browser.newContext({ viewport: { width: 1280, height: 720 } });

await context.route(/^https?:\/\/[^/]+\/api(?:\/|$)/, async (route) => {
  const url = route.request().url();
  const method = route.request().method();
  if (url.endsWith('/api/auth/session') && method === 'GET') {
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 200,
        message: 'ok',
        data: {
          username: 'kylinops-admin',
          csrfToken: 'csrf-screenshot-001',
          loginAt: now,
          expiresAt: '2026-06-17T20:00:00Z',
          idleTimeout: 1800,
        },
        timestamp: Date.now(),
      }),
    });
  }
  if (url.endsWith('/api/health')) {
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ status: 'UP' }) });
  }
  if (url.endsWith('/api/dashboard/overview')) {
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 200, message: 'ok', timestamp: Date.now(),
        data: {
          score: 86, successfulMetricCount: 9, totalMetricCount: 10, degraded: true,
          auditId: 'audit-shot-001',
          collectedAt: now,
          metrics: [
            { toolName: 'cpu_status_tool', status: 'success', data: { usagePercent: 42, loadAvg1: 0.85 }, durationMs: 12 },
            { toolName: 'memory_status_tool', status: 'success', data: { totalMB: 8192, usedMB: 5200, usedPercent: 63 }, durationMs: 8 },
            { toolName: 'disk_usage_tool', status: 'success', data: { partitions: [{ mount: '/', usedPercent: 86, size: '100G', used: '86G' }] }, durationMs: 14 },
            { toolName: 'service_status_tool', status: 'success', data: { serviceName: 'nginx', activeState: 'inactive', subState: 'dead' }, durationMs: 9 },
            { toolName: 'network_port_tool', status: 'success', data: { ports: [] }, durationMs: 7 },
            { toolName: 'system_info_tool', status: 'success', data: { hostname: 'kylin-v11', osName: 'Kylin Advanced Server V11', arch: 'loongarch64' }, durationMs: 6 },
            { toolName: 'process_list_tool', status: 'success', data: { processes: [] }, durationMs: 5 },
            { toolName: 'large_file_scan_tool', status: 'success', data: { files: [] }, durationMs: 11 },
            { toolName: 'process_detail_tool', status: 'success', data: null, durationMs: 4 },
            { toolName: 'journal_log_tool', status: 'failed', errorMessage: 'journalctl 不可用', durationMs: 3000 },
          ],
        },
      }),
    });
  }
  if (/\/api\/tools(\?|$)/.test(url)) {
    const tools = [
      'system_info_tool', 'cpu_status_tool', 'memory_status_tool',
      'disk_usage_tool', 'large_file_scan_tool', 'process_list_tool',
      'process_detail_tool', 'network_port_tool', 'service_status_tool',
      'journal_log_tool',
    ].map((toolName, i) => ({
      toolName,
      description: `${toolName} - 注册的 OpsTool`,
      inputSchema: '{"type":"object","properties":{}}',
      outputSchema: '{"type":"object"}',
      riskLevel: ['L0','L0','L1','L1','L1','L0','L1','L0','L1','L1'][i] ?? 'L1',
      permissionType: 'READ',
      toolStatus: 'ENABLED',
      timeoutMs: 3000,
      auditRequired: true,
      callCount: i * 3,
      successRate: i % 3 === 0 ? null : 0.95,
      lastCalledAt: i % 2 === 0 ? now : null,
    }));
    return route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({ code: 200, message: 'ok', timestamp: Date.now(), data: tools }),
    });
  }
  if (url.endsWith('/api/security/risk-levels')) {
    return route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({
        code: 200, message: 'ok', timestamp: Date.now(),
        data: [
          { level: 'L0', decision: 'ALLOW', description: '只读查询', examples: ['df -h', '查看磁盘状态'] },
          { level: 'L1', decision: 'ALLOW', description: '放行并审计', examples: ['查看服务日志'] },
          { level: 'L2', decision: 'CONFIRM', description: '用户确认后执行', examples: ['重启 nginx'] },
          { level: 'L3', decision: 'BLOCK', description: '高风险阻断', examples: ['修改系统配置'] },
          { level: 'L4', decision: 'BLOCK', description: '绝对阻断', examples: ['rm -rf /', 'chmod -R 777 /'] },
        ],
      }),
    });
  }
  if (url.endsWith('/api/security/rules')) {
    const rules = [
      { ruleId: 'rm-rf-root', name: 'rm-rf-root', description: '阻断 rm -rf /', regex: 'rm\\s+-rf\\s+/', riskLevel: 'L4', riskDecision: 'BLOCK', reason: '删除根目录', safeSuggestion: '请明确目标目录', enabled: true, priority: 100 },
      { ruleId: 'chmod-777', name: 'chmod-777', description: '阻断 chmod 777', regex: 'chmod\\s+-R\\s+777', riskLevel: 'L4', riskDecision: 'BLOCK', reason: '开放权限', safeSuggestion: '使用更细粒度权限', enabled: true, priority: 99 },
      { ruleId: 'service-restart', name: 'service-restart', description: '服务重启需确认', regex: 'systemctl\\s+restart', riskLevel: 'L2', riskDecision: 'CONFIRM', reason: '影响服务可用性', safeSuggestion: '在低峰期执行', enabled: true, priority: 50 },
      { ruleId: 'prompt-inject', name: 'prompt-inject', description: '提示词注入检测', regex: '忽略|绕过|关闭安全', riskLevel: 'L4', riskDecision: 'BLOCK', reason: '注入企图', safeSuggestion: '拒绝执行', enabled: true, priority: 200 },
    ];
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'ok', timestamp: Date.now(), data: rules }) });
  }
  if (url.endsWith('/api/security/events')) {
    return route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({
        code: 200, message: 'ok', timestamp: Date.now(),
        data: {
          content: [
            { auditId: 'audit-block-shot-001', riskLevel: 'L4', decision: 'BLOCK', matchedRules: ['rm-rf-root'], reason: '命中绝对阻断规则 rm -rf /', createdAt: now, toolName: 'rm -rf /' },
            { auditId: 'audit-block-shot-002', riskLevel: 'L4', decision: 'BLOCK', matchedRules: ['chmod-777'], reason: '命中绝对阻断规则 chmod 777', createdAt: now, toolName: 'chmod -R 777 /' },
          ],
          totalElements: 2, totalPages: 1, number: 0, size: 20,
        },
      }),
    });
  }
  return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'ok', timestamp: Date.now(), data: null }) });
});

// Pre-seed localStorage so the page loads in the correct theme on first paint
await context.addInitScript((theme) => {
  try { localStorage.setItem('kg-theme', theme); } catch {}
}, themes[0]);

for (const theme of themes) {
  // eslint-disable-next-line no-console
  console.log(`\n=== theme: ${theme} ===`);
  for (const target of PAGES) {
    // Re-seed localStorage per-theme (each iteration overrides if needed)
    await context.addInitScript((t) => {
      try { localStorage.setItem('kg-theme', t); } catch {}
    }, theme);
    await page.goto(`${BASE_URL}${target.path}`, { waitUntil: 'networkidle' });
    await page.waitForTimeout(150);
    const file = resolve(OUT_DIR, theme, target.file);
    await page.screenshot({ path: file, fullPage: false });
    // eslint-disable-next-line no-console
    console.log(`saved ${theme}/${target.file}`);
  }
}

await browser.close();
```

- [ ] **Step 2: Run dark capture to verify the rewrite didn't break the original**

Run:
```bash
cd "D:/Work/code/kylin-ops-ui-01/frontend" && (npm run dev &) && sleep 8 && node tests/e2e/screenshot-ui-01.mjs --theme=dark && pkill -f "vite"
```

Expected: 4 PNGs written to `tests/e2e/screenshots/dark/`, log shows 4 `saved dark/...` lines

Verify: `cd "D:/Work/code/kylin-ops-ui-01" && ls frontend/tests/e2e/screenshots/dark/` → 4 files

- [ ] **Step 3: Run light capture**

Run:
```bash
cd "D:/Work/code/kylin-ops-ui-01/frontend" && (npm run dev &) && sleep 8 && node tests/e2e/screenshot-ui-01.mjs --theme=light && pkill -f "vite"
```

Expected: 4 PNGs written to `tests/e2e/screenshots/light/`, log shows 4 `saved light/...` lines

- [ ] **Step 4: Visual spot-check (manual, 2 min)**

Open `frontend/tests/e2e/screenshots/light/ui-01-02-dashboard.png` in image viewer. Verify:
- Background is light green (not dark)
- Cards are white
- Brand mark "KG" still has blue/cyan gradient
- L4 "数据不可用" tag has deep red (not vivid)

If any is off, adjust token values in `frontend/src/styles/index.css` and re-run step 3.

- [ ] **Step 5: Commit**

```bash
cd "D:/Work/code/kylin-ops-ui-01" && git add frontend/tests/e2e/screenshot-ui-01.mjs frontend/tests/e2e/screenshots/light/ && git -c user.name="KylinOps Dev" -c user.email="dev@kylinops.local" commit -m "feat(frontend): screenshot script supports --theme=dark|light|all

- Pre-seeds localStorage 'kg-theme' via addInitScript so the page
  loads in the correct theme on first paint (no FOUC)
- Default --theme=dark preserves npm run screenshot:ui01 backward compat
- 4 light-theme PNGs committed (dark/ already done in Task 7)"
```

---

## Task 9: Add npm Scripts for Light Screenshots

**Files:**
- Modify: `frontend/package.json:5-12` (scripts block)

- [ ] **Step 1: Add 2 new scripts**

In `frontend/package.json`, find the `scripts` block:
```json
  "scripts": {
    "dev": "vite",
    "build": "vue-tsc --noEmit && vite build",
    "preview": "vite preview --port 5173 --strictPort",
    "test:unit": "vitest",
    "test:e2e": "playwright test"
  },
```

**Replace** with:
```json
  "scripts": {
    "dev": "vite",
    "build": "vue-tsc --noEmit && vite build",
    "preview": "vite preview --port 5173 --strictPort",
    "test:unit": "vitest",
    "test:e2e": "playwright test",
    "screenshot:ui01": "node tests/e2e/screenshot-ui-01.mjs",
    "screenshot:ui01:dark": "node tests/e2e/screenshot-ui-01.mjs --theme=dark",
    "screenshot:ui01:light": "node tests/e2e/screenshot-ui-01.mjs --theme=light",
    "screenshot:ui01:all": "node tests/e2e/screenshot-ui-01.mjs --theme=all"
  },
```

- [ ] **Step 2: Verify scripts work**

Run: `cd "D:/Work/code/kylin-ops-ui-01/frontend" && cat package.json | grep screenshot`
Expected: 4 lines (original `screenshot:ui01` + 3 new)

- [ ] **Step 3: Commit**

```bash
cd "D:/Work/code/kylin-ops-ui-01" && git add frontend/package.json && git -c user.name="KylinOps Dev" -c user.email="dev@kylinops.local" commit -m "chore(frontend): add screenshot npm scripts for both themes

- screenshot:ui01:dark    (backward-compat default)
- screenshot:ui01:light
- screenshot:ui01:all"
```

---

## Task 10: Write theme-toggle.spec.ts E2E

**Files:**
- Create: `frontend/tests/e2e/theme-toggle.spec.ts`

- [ ] **Step 1: Create the E2E file**

Create `frontend/tests/e2e/theme-toggle.spec.ts`:

```ts
import { test, expect } from '@playwright/test';

test.describe('Theme toggle (dark <-> light)', () => {
  test.beforeEach(async ({ context }) => {
    // Bypass the route guard by seeding an in-memory auth session.
    await context.addInitScript(() => {
      try { localStorage.setItem('kg-theme', 'dark'); } catch {}
    });
    await context.route('**/api/auth/session', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 200,
          message: 'ok',
          data: {
            username: 'kylinops-e2e',
            csrfToken: 'csrf-e2e-001',
            loginAt: '2026-06-18T00:00:00Z',
            expiresAt: '2026-06-18T12:00:00Z',
            idleTimeout: 1800,
          },
          timestamp: Date.now(),
        }),
      });
    });
    await context.route('**/api/health', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'UP' }),
      });
    });
  });

  test('default theme is dark and toggle button is visible', async ({ page }) => {
    await page.goto('/chat');
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
    const toggle = page.locator('[data-testid="app-theme-toggle"]');
    await expect(toggle).toBeVisible();
  });

  test('clicking toggle switches to light and persists to localStorage', async ({ page }) => {
    await page.goto('/chat');
    const toggle = page.locator('[data-testid="app-theme-toggle"]');
    await toggle.click();
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'light');
    const stored = await page.evaluate(() => localStorage.getItem('kg-theme'));
    expect(stored).toBe('light');
  });

  test('clicking again switches back to dark', async ({ page }) => {
    await page.goto('/chat');
    const toggle = page.locator('[data-testid="app-theme-toggle"]');
    await toggle.click(); // -> light
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'light');
    await toggle.click(); // -> dark
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
    const stored = await page.evaluate(() => localStorage.getItem('kg-theme'));
    expect(stored).toBe('dark');
  });

  test('theme persists across full page reload', async ({ page }) => {
    await page.goto('/chat');
    const toggle = page.locator('[data-testid="app-theme-toggle"]');
    await toggle.click();
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'light');
    await page.reload();
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'light');
  });

  test('aria-label is localized Chinese', async ({ page }) => {
    await page.goto('/chat');
    const toggle = page.locator('[data-testid="app-theme-toggle"]');
    await expect(toggle).toHaveAttribute('aria-label', '切换到亮色主题');
    await toggle.click();
    await expect(toggle).toHaveAttribute('aria-label', '切换到暗色主题');
  });
});
```

- [ ] **Step 2: Run the new E2E (dev server must be running)**

Run: `cd "D:/Work/code/kylin-ops-ui-01/frontend" && (npm run dev &) && sleep 8 && E2E_LIVE=true npx playwright test tests/e2e/theme-toggle.spec.ts 2>&1 | tail -20 && pkill -f "vite"`
Expected: `5 passed (5)`

If failures, debug individually by adding `--headed` and checking browser.

- [ ] **Step 3: Commit**

```bash
cd "D:/Work/code/kylin-ops-ui-01" && git add frontend/tests/e2e/theme-toggle.spec.ts && git -c user.name="KylinOps Dev" -c user.email="dev@kylinops.local" commit -m "test(e2e): add theme-toggle spec (5 cases)

- Default dark + toggle visible
- Click -> light + localStorage persists
- Click again -> dark
- Reload preserves theme
- aria-label is localized Chinese"
```

---

## Task 11: Full Regression Run

**Files:** none (read-only verification)

- [ ] **Step 1: Full unit test suite**

Run: `cd "D:/Work/code/kylin-ops-ui-01/frontend" && npm run test:unit -- --run 2>&1 | tail -10`
Expected: 198/198 (190 baseline + 8 new useTheme)

- [ ] **Step 2: Full E2E suite (mock mode, no live backend needed)**

Run: `cd "D:/Work/code/kylin-ops-ui-01/frontend" && (npm run dev &) && sleep 8 && npx playwright test 2>&1 | tail -15 && pkill -f "vite"`
Expected: 24/24+skipped (19 baseline + 5 new theme-toggle)

- [ ] **Step 3: Both theme screenshots regenerate clean**

Run:
```bash
cd "D:/Work/code/kylin-ops-ui-01/frontend" && (npm run dev &) && sleep 8 && npm run screenshot:ui01:all && pkill -f "vite"
```

Expected: 8 PNGs total (4 in dark/ + 4 in light/), all from `saved dark/...` and `saved light/...` log lines

- [ ] **Step 4: Build still passes (no TS / compile error)**

Run: `cd "D:/Work/code/kylin-ops-ui-01/frontend" && npm run build 2>&1 | tail -10`
Expected: `✓ built in Xms` (vue-tsc + vite build)

- [ ] **Step 5: No commit needed (regression only)**

If all green → continue. If anything red → fix and add fix commit before moving on.

---

## Task 12: Scan .vue for Hard-Coded Dark Colors

**Files:** none expected; fixes if found

- [ ] **Step 1: Grep for dark hex codes in all .vue files**

Run:
```bash
cd "D:/Work/code/kylin-ops-ui-01/frontend" && grep -rEn "#0b1220|#111c2e|#182236|#1f2a3d|#0a1322|#e2e8f0|#cbd5e1|#94a3b8|#64748b|rgba\(148, ?163, ?184" src/ --include="*.vue" --include="*.css" --include="*.ts" 2>&1 | grep -v "styles/index.css"
```

Expected: only the known `Login/index.vue` match (already fixed) OR empty.

If other matches appear, **decide case-by-case**:
- If it's a decorative gradient that already works on light → leave + add comment
- If it's a "shadow / glow / glass" that looks wrong on light → tokenize

For each match found:
- If fix is trivial (< 3 lines) → make fix in this task
- If fix is non-trivial → file follow-up commit later, note here

- [ ] **Step 2: If fixes made, commit**

```bash
cd "D:/Work/code/kylin-ops-ui-01" && git add -A frontend/src/ && git -c user.name="KylinOps Dev" -c user.email="dev@kylinops.local" commit -m "fix(frontend): tokenize any remaining hard-coded dark colors

Found during G1 light theme audit. Each fix replaces a literal hex
with --kg-* token so the value flips correctly under [data-theme=light]."
```

If no fixes needed → skip this step.

---

## Task 13: Em-Dash Audit + Final Commit

**Files:** none expected; fixes if found

- [ ] **Step 1: Grep for em-dash in all files modified in this plan**

Run:
```bash
cd "D:/Work/code/kylin-ops-ui-01" && git diff 8b344de..HEAD -- "frontend/src/composables/" "frontend/src/layouts/AppLayout.vue" "frontend/src/pages/Login/index.vue" "frontend/src/styles/index.css" "frontend/tests/e2e/theme-toggle.spec.ts" "frontend/tests/e2e/screenshot-ui-01.mjs" "frontend/package.json" | grep -nE "—|–" 2>&1 || echo "no em-dash found"
```

Expected: "no em-dash found"

If found → fix by replacing with regular hyphen `-`, then add commit:
```bash
git -c user.name="KylinOps Dev" -c user.email="dev@kylinops.local" commit -am "fix(frontend): replace em-dash with hyphen (zero-tolerance)"
```

- [ ] **Step 2: DoD checklist verification**

Go through spec §10 Definition of Done, item by item. For each:
- [ ] useTheme composable unit test passes → from Task 1 + 11
- [ ] AppLayout toggle button visible → from Task 6
- [ ] Click toggle switches app instantly → Task 10 E2E test
- [ ] localStorage persists → Task 10 E2E test
- [ ] 7 pages render in both themes → Task 8 + 11 screenshots
- [ ] WCAG AA passes → spec §6.2 table (manual re-verify in browser if uncertain)
- [ ] 190+8 unit + 19+5 E2E all green → Task 11
- [ ] 8 screenshots in dark/ + light/ → Task 7 + 8
- [ ] prefers-reduced-motion not broken → Task 3 (scan-line now opt-in + still 200ms transitions on others)
- [ ] .vue scan clean (besides Login.vue already fixed) → Task 12
- [ ] No em-dash → Task 13 Step 1
- [ ] 1 commit per task (multiple commits, but all on feature branch) → verified

- [ ] **Step 3: Final summary commit (only if needed for any DoD items fixed in this task)**

If Task 12 + 13 produced no fixes → no commit. Otherwise:
```bash
cd "D:/Work/code/kylin-ops-ui-01" && git add -A && git -c user.name="KylinOps Dev" -c user.email="dev@kylinops.local" commit -m "chore(frontend): final DoD pass for light theme G1"
```

- [ ] **Step 4: Report back to user**

Provide:
1. List of commits added (commit hashes via `git log --oneline 8b344de..HEAD`)
2. Test counts: `npm run test:unit -- --run 2>&1 | grep -E "Tests|Test Files" | tail -2` and E2E counts
3. Screenshot count: `ls frontend/tests/e2e/screenshots/dark/ && echo --- && ls frontend/tests/e2e/screenshots/light/`
4. Known limitations (anything from Task 12/13 that was deferred)

---

## Self-Review

### Spec coverage

| Spec section | Plan task |
|---|---|
| §0 目标与原则 (zero component changes, no Tailwind, WCAG AA) | Tasks 1-6 ensure no .vue scoped style changes (only Login + AppLayout have minimal additions) |
| §1 决策 (G1 / dual-mode / scan-line opt-in / Login orb retune / etc.) | Each mapped to a task |
| §2 token 映射表 | Task 2 (the only place tokens are added) |
| §3.1 玻璃态 (0 code change via token) | Task 2 (--kg-glass-* override does the work) |
| §3.2 扫描线 opt-in | Task 3 |
| §3.3 登录光球亮色态 | Task 5 |
| §3.4 登录 input wrapper 硬编码 | Task 4 |
| §3.5 切换按钮 | Task 6 |
| §4 useTheme composable | Task 1 |
| §5 Element Plus 覆写 (0 改动) | Task 2 (verified via cascade) |
| §6 WCAG 验证 | Task 11 Step 1 (run unit) + Task 13 Step 2 (manual verify during DoD) |
| §7.1 单元测试 | Task 1 |
| §7.2 E2E 测试 | Task 10 |
| §7.3 视觉回归 | Tasks 7+8+9 |
| §8 实施步骤 | This plan's task ordering matches |
| §9 风险 (硬编码暗色) | Task 12 |
| §10 DoD | Task 13 |
| §11 不在本 spec | Not addressed (excluded by design) |

### Placeholder scan
- No "TBD" / "TODO" / "implement later" / "add validation" in any task
- All code blocks contain actual content
- No "Similar to Task N" cross-references — each task shows its own code
- No "as needed" vague steps

### Type consistency
- `useTheme()` returns `{ theme, toggleTheme, isDark, isLight }` — used consistently in Task 1 (test), Task 6 (AppLayout), Task 10 (E2E)
- `KgTheme` type = `'dark' | 'light'` — used in composable only
- `STORAGE_KEY = 'kg-theme'` — used in composable, test, and E2E consistently
- `data-testid="app-theme-toggle"` — used in Task 6 (template) and Task 10 (E2E selector)
- File paths exact: `frontend/src/composables/{useTheme.ts,__tests__/useTheme.spec.ts}`

### Gaps identified and fixed during self-review
- None — all spec requirements covered by 13 tasks.

---

**Plan complete. Ready for execution.**
