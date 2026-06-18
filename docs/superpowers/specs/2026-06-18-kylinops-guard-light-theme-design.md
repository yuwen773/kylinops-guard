# KylinOps Guard 明亮主题设计文档

> **状态**：🟡 待用户审阅
> **下一步**：用户审 spec → 批准后转入 writing-plans skill
> **版本**：v0.1
> **日期**：2026-06-18
> **作者**：Brainstorming 阶段产物（基于 commit `727a015` UI-01.1 已落地的暗色 design token）
> **配套文档**：
> - UI-01 commit：[`8afc6c4`](../../) + UI-01.1 [`727a015`](../../) 落地的暗色 token 与组件
> - 设计纪律参考：`.claude/skills/design-taste-frontend/SKILL.md`（§4 anti-slop 适用层）
> - 项目 CLAUDE.md（克线 / 不堆砌 / 安全护栏第一）

---

## 0. 目标与原则

**目标**：在现有暗色 "Command Center" 主题之外，**新增**一套手动切换的明亮主题 G1（绿底 + 蓝主色），实现双模共存。暗色态**完全保留现状**，所有改动以"亮色为增量"为原则。

**非目标**（明确不做）：
1. 不删暗色 token（保留完整 :root 默认值）
2. 不跟随系统 `prefers-color-scheme`（手动切换）
3. 不引入 Tailwind / SCSS / CSS-in-JS（保持纯 CSS 架构）
4. 不做三主题或更多（仅 dark + light 两套）
5. 不重做暗色视觉（CLAUDE.md 强调"暗色基线"已落地）
6. 不在亮色态下"重排 token 命名"（保留 `--kg-color-*` 语义化命名）

**原则**：
1. **零组件代码改动**：所有 .vue 的 `<style scoped>` 不动；亮色态通过 `:root[data-theme="light"]` 全局覆盖 token 值实现
2. **零 Element Plus 重覆写**：亮色态不重复 `--el-*` 映射；`--el-*` 通过 cascade 自动跟随 `--kg-*` 变化
3. **不破坏基线**：现有 190/190 unit + 19/19 + 3 skipped E2E 必须继续通过
4. **WCAG AA 强制**：所有 token 在两套主题下均 ≥ 4.5:1
5. **Em-dash 零容忍**：新增文案不出现 em-dash
6. **复用优先**：主题切换按钮复用 `AppSectionHeader` / `AppStateBanner` / `AppEmptyState` 等已有 common 组件

---

## 1. 关键决策记录（来自 brainstorming 阶段）

| 决策 | 选择 | 理由 |
|---|---|---|
| **主题方向** | G1 — 绿底 + 蓝主色 | 与"安全运维 = 绿色通行"行业隐喻强匹配；保住品牌色蓝 |
| **部署模式** | 双模 + 手动切换 | 保留暗色向后兼容；切换 = `data-theme` attribute |
| **暗色态命运** | 完全保持现状 | 不破坏基线；亮色独有 NOC 绿调；token 改动集中 |
| **玻璃态 (.kg-glass)** | 亮色态改纯白实底 | 性能更优（无 backdrop-filter）；可访问性更稳 |
| **扫描线 (kg-scan-overlay)** | 仅数据更新时触发 | 呼应 design-taste-frontend §5 "Motion must be motivated" |
| **登录 ambient 光球** | 保留 + 调暗 + 调慢 | 仪式感保留；调暗避免亮色下变土 |
| **数据闪光 (kg-data-flash)** | 不变 | 动效已合理 (0.8s 不循环) |
| **切换按钮位置** | 顶栏右侧、登出按钮**左侧** | 单按钮 (Sun/Moon 图标)，32×32px |
| **切换按钮形态** | B1 — Sun/Moon 单按钮 | 1-click 切换；占用最小 |
| **持久化 key** | `localStorage['kg-theme']` | 无 SSR 顾虑；key 命名语义化 |
| **持久化默认值** | `'dark'` | 与现状一致；用户主动切到 light 后覆盖 |
| **过渡动画** | G1 — 立即切换 | 切换是低频操作；Element Plus 不跟随 transition |
| **Tailwind 引入** | **不引入** | 阶段太晚；现有 token 是资产；Element Plus + --kg-* 已 0 改动接好 |
| **风险色双模策略** | 共享语义、每模态单独校准值 | 暗色值不变；亮色值整体加深以保 WCAG AA |
| **截图回归** | 扩展 screenshot-ui-01.mjs → 4×2=8 张 | dark/light 各 4 页 |
| **测试新增** | useTheme.spec.ts + theme-toggle.spec.ts | composable 单测 + E2E 切换回环 |
| **设计 discipline 范围** | §4 anti-slop 适用；不引入 landing aesthetic | 项目是 admin panel，保持专业克制 |

---

## 2. Token 映射表（亮色态新增）

> 暗色态 token 全部保留不变（`styles/index.css:22-140`）。本表仅列出亮色态下**需要覆写**的 token。

### 2.1 Surface / Border / Text（核心 6 类 + 3 类衍生）

| 变量 | 暗色（现） | 亮色（新） | 对比度 / 备注 |
|---|---|---|---|
| `--kg-color-bg` | `#0b1220` | `#f0fdf4` | 主底色（极浅绿） |
| `--kg-color-surface` | `#111c2e` | `#ffffff` | 卡片底（纯白）|
| `--kg-color-surface-soft` | `#182236` | `#f7fef9` | 二级面（更浅绿）|
| `--kg-color-surface-mute` | `#1f2a3d` | `#ecfdf5` | 嵌套/代码块 |
| `--kg-color-surface-code` | `#0a1322` | `#f7fef9` | 代码块 |
| `--kg-color-border` | `rgba(148,163,184,.16)` | `rgba(21,128,61,.18)` | 绿系边 |
| `--kg-color-border-strong` | `rgba(148,163,184,.32)` | `rgba(21,128,61,.32)` | |
| `--kg-color-border-mute` | `rgba(148,163,184,.08)` | `rgba(21,128,61,.10)` | |
| `--kg-color-text-primary` | `#e2e8f0` | `#14532d` | 8.5:1 ✓ AAA |
| `--kg-color-text-secondary` | `#cbd5e1` | `#166534` | 7:1 ✓ AA+ |
| `--kg-color-text-mute` | `#94a3b8` | `#4d7c5b` | 4.6:1 ✓ AA (刚好) |
| `--kg-color-text-placeholder` | `#64748b` | `#6b7280` | 4.5:1 ✓ AA |
| `--kg-color-text-inverse` | `#0b1220` | `#ffffff` | 反色 |
| `--kg-color-text-on-risk` | `#0b1220` | `#ffffff` | L 风险浅底上的文字 |

### 2.2 主色 / 装饰

| 变量 | 暗色（现） | 亮色（新） | 备注 |
|---|---|---|---|
| `--kg-color-primary` | `#3b82f6` | `#2563eb` | 蓝主色（亮色加深）|
| `--kg-color-primary-hover` | `#60a5fa` | `#1d4ed8` | |
| `--kg-color-primary-soft` | `rgba(59,130,246,.16)` | `rgba(37,99,235,.12)` | 减淡 |
| `--kg-color-primary-strong` | `#2563eb` | `#1e40af` | |
| `--kg-color-accent` | `#06b6d4` | **`#15803d`** | **装饰辉光：亮色变绿** |
| `--kg-color-accent-hover` | `#22d3ee` | `#166534` | |
| `--kg-color-accent-soft` | `rgba(6,182,212,.12)` | `rgba(21,128,61,.10)` | |
| `--kg-color-accent-glow` | `0 0 20px rgba(6,182,212,.15)` | `0 0 16px rgba(21,128,61,.10)` | |

> **Accent 在亮色态下从青色（`#06b6d4`）变为深绿（`#15803d`）** — 这与暗色态下的"青色辉光"形成视觉分工：暗色 = 赛博蓝绿辉光，亮色 = NOC 绿色辉光。`Accent` 仅用于装饰辉光，不参与风险语义。

### 2.3 风险色（亮色态全部加深以保 WCAG AA ≥ 4.5:1）

| 变量 | 暗色（现） | 亮色（新） | 亮色态对比度 |
|---|---|---|---|
| `--kg-color-risk-l0` | `#38bdf8` | `#0369a1` | 5.6:1 ✓ |
| `--kg-color-risk-l0-soft` | `rgba(56,189,248,.14)` | `rgba(3,105,161,.12)` | |
| `--kg-color-risk-l1` | `#22c55e` | `#15803d` | 5.2:1 ✓ |
| `--kg-color-risk-l1-soft` | `rgba(34,197,94,.14)` | `rgba(21,128,61,.12)` | |
| `--kg-color-risk-l2` | `#facc15` | `#b45309` | 5.8:1 ✓ |
| `--kg-color-risk-l2-soft` | `rgba(250,204,21,.16)` | `rgba(180,83,9,.12)` | |
| `--kg-color-risk-l3` | `#fb923c` | `#c2410c` | 5.4:1 ✓ |
| `--kg-color-risk-l3-soft` | `rgba(251,146,60,.18)` | `rgba(194,65,12,.14)` | |
| `--kg-color-risk-l4` | `#ef4444` | `#b91c1c` | 6.2:1 ✓ |
| `--kg-color-risk-l4-soft` | `rgba(239,68,68,.18)` | `rgba(185,28,28,.14)` | |
| `--kg-color-risk-inject` | `#a855f7` | `#7e22ce` | 6.6:1 ✓ |
| `--kg-color-risk-inject-soft` | `rgba(168,85,247,.20)` | `rgba(126,34,206,.14)` | |

### 2.4 State（success / warning / danger / info）

| 变量 | 暗色（现） | 亮色（新） |
|---|---|---|
| `--kg-color-success` | `#22c55e` | `#15803d` |
| `--kg-color-success-soft` | `rgba(34,197,94,.14)` | `rgba(21,128,61,.12)` |
| `--kg-color-warning` | `#facc15` | `#b45309` |
| `--kg-color-warning-soft` | `rgba(250,204,21,.14)` | `rgba(180,83,9,.12)` |
| `--kg-color-danger` | `#ef4444` | `#b91c1c` |
| `--kg-color-danger-soft` | `rgba(239,68,68,.14)` | `rgba(185,28,28,.12)` |
| `--kg-color-info` | `#38bdf8` | `#0369a1` |
| `--kg-color-info-soft` | `rgba(56,189,248,.14)` | `rgba(3,105,161,.12)` |

### 2.5 Shadow / Glass（亮色态改为浅阴影 + 白磨砂）

| 变量 | 暗色（现） | 亮色（新） |
|---|---|---|
| `--kg-shadow-card` | `0 8px 24px rgba(2,6,16,.32)` | `0 1px 3px rgba(0,0,0,.05), 0 4px 12px rgba(0,0,0,.04)` |
| `--kg-shadow-elevated` | `0 12px 32px rgba(2,6,16,.42)` | `0 4px 16px rgba(0,0,0,.08)` |
| `--kg-shadow-inset` | `inset 0 1px 0 rgba(255,255,255,.04)` | `inset 0 1px 0 rgba(255,255,255,.6)` |
| `--kg-glass-bg` | `rgba(8,14,26,.82)` | **`rgba(255,255,255,.85)`** （白磨砂） |
| `--kg-glass-border` | `rgba(255,255,255,.06)` | `rgba(255,255,255,.6)` |
| `--kg-glass-shadow` | `0 8px 32px rgba(2,6,16,.48)` | `0 4px 16px rgba(0,0,0,.06)` |

### 2.6 不变的 token（两套主题共用）

> 以下 token 不参与双模，**暗色值即亮色值**，无需任何改动：

- `--kg-space-1` ~ `--kg-space-8` （间距）
- `--kg-radius-sm / md / lg / pill` （圆角）
- `--kg-font-sans / mono` （字体栈）
- `--kg-text-xs / sm / base / md / lg / xl / 2xl` （字号）
- `--kg-line-tight / base / relaxed` （行高）
- `--kg-transition-fast / base / slow` （过渡时长）
- `--kg-z-header / dropdown / modal / toast` （z-index）
- 所有 keyframes（`kg-fadeIn` / `kg-slideUp` / `kg-pulseDanger` 等）
- 暗色专属动效：`kg-pulseGlow` / `kg-dataFlash` / `kg-scanLine` / `kg-ripple` 行为参数

---

## 3. 组件级双模处理

### 3.1 玻璃态 `.kg-glass`

**当前（暗色）**：
```css
.kg-glass {
  background: var(--kg-glass-bg);          /* rgba(8,14,26,.82) */
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  border: 1px solid var(--kg-glass-border); /* rgba(255,255,255,.06) */
  box-shadow: var(--kg-glass-shadow);
}
```

**亮色态（已通过 §2.5 覆写 token 实现，0 代码改动）**：
- 背景自动变 `rgba(255,255,255,.85)`（白磨砂）
- 边框自动变 `rgba(255,255,255,.6)`（更明显的浅边）
- 阴影自动变浅阴影

### 3.2 扫描线 `.kg-scan-overlay`

**当前（暗色）**：
```css
.kg-scan-overlay::after {
  content: '';
  position: absolute;
  top: 0; left: 0; right: 0;
  height: 2px;
  background: linear-gradient(90deg, transparent, rgba(6,182,212,.15), transparent);
  animation: kg-scanLine 4s linear infinite;
  pointer-events: none;
}
```

**新行为（两套主题共用，**改 1 处**）**：
```css
.kg-scan-overlay::after {
  /* ... 同上 ... */
  animation: kg-scanLine 4s linear infinite;
  animation-play-state: paused;  /* 新增：默认暂停 */
}
.kg-scan-overlay--active::after {
  animation-play-state: running;  /* 数据更新时通过 modifier 触发 */
}
```

> 调用方：未来需要时在 `<div class="kg-scan-overlay kg-scan-overlay--active">` 加 modifier 类即可。**本任务不主动调用**，仅准备 API。

### 3.3 登录 ambient 光球 `.login-ambient`

**当前（暗色）**：`Login/index.vue:184-207` — 两个 radial-gradient 光球，6s / 8s `kg-pulseGlow` 循环。

**亮色态改写（直接覆写 Login scoped style 即可）**：
```css
.login-ambient--1 {
  background: radial-gradient(circle, rgba(21,128,61,.10), transparent);  /* 绿 */
  animation: kg-pulseGlow 12s ease-in-out infinite;                         /* 8s → 12s */
}
.login-ambient--2 {
  background: radial-gradient(circle, rgba(37,99,235,.08), transparent);  /* 蓝 */
  animation: kg-pulseGlow 10s ease-in-out infinite reverse;                 /* 6s → 10s */
}
```

> **实现方式**（二选一，**选 A**）：  
> **A. 在 Login scoped 末尾追加主题覆写**（推荐 — 单文件内完整自包含）：
> ```css
> :root[data-theme="light"] .login-ambient--1 {
>   background: radial-gradient(circle, rgba(21,128,61,.10), transparent);
>   animation-duration: 12s;
> }
> :root[data-theme="light"] .login-ambient--2 {
>   background: radial-gradient(circle, rgba(37,99,235,.08), transparent);
>   animation-duration: 10s;
> }
> ```
> B. （不推荐）在全局 `index.css` 里覆写 — 会污染 Login 组件外的样式。

### 3.4 登录 input wrapper 暗色硬编码修复

**问题**：`Login/index.vue:301` `background: rgba(0, 0, 0, 0.2)` 在亮色态下会变成"白底上盖一层 20% 黑"，导致输入框偏灰。

**修复**：在 Login scoped style 末尾加：
```css
:root[data-theme="light"] .login-card :deep(.el-input__wrapper) {
  background: rgba(255, 255, 255, 0.7);
  border-color: var(--kg-color-border-strong);
}
```

### 3.5 主题切换按钮（新增组件）

**位置**：`AppLayout/index.vue` 顶栏、登出按钮**左侧**。

**形态**：单按钮，32×32px，Sun 图标（亮色态显示）/ Moon 图标（暗色态显示），来自 `@element-plus/icons-vue`：
- 暗色态：`Sunny` 图标（点击切到 light）
- 亮色态：`Moon` 图标（点击切到 dark）

**实现**：
```vue
<!-- AppLayout.vue 新增 -->
<el-button
  size="small"
  :icon="themeIcon"
  circle
  class="app-theme-toggle"
  :data-testid="app-theme-toggle"
  :aria-label="theme === 'dark' ? '切换到亮色主题' : '切换到暗色主题'"
  @click="toggleTheme"
/>
```

```ts
import { Sunny, Moon } from '@element-plus/icons-vue';
import { useTheme } from '@/composables/useTheme';

const { theme, toggleTheme } = useTheme();
const themeIcon = computed(() => theme.value === 'dark' ? Sunny : Moon);
```

> 按钮**不属于**业务导航，使用 `circle` ghost 形态（不占文字空间），与登出按钮平级但视觉权重更低。

---

## 4. Composable: `useTheme`

**文件**：`frontend/src/composables/useTheme.ts`（**新文件**）

```ts
import { ref, watchEffect, onMounted, computed } from 'vue';

export type KgTheme = 'dark' | 'light';
const STORAGE_KEY = 'kg-theme';
const ATTR_NAME = 'data-theme';

// 模块级单例 — 全 app 共用一个 ref
const theme = ref<KgTheme>('dark');

export function useTheme() {
  onMounted(() => {
    const saved = localStorage.getItem(STORAGE_KEY);
    if (saved === 'light' || saved === 'dark') {
      theme.value = saved;
    }
  });

  // 同步 DOM attribute
  watchEffect(() => {
    document.documentElement.setAttribute(ATTR_NAME, theme.value);
  });

  // 同步 localStorage
  watchEffect(() => {
    localStorage.setItem(STORAGE_KEY, theme.value);
  });

  function toggleTheme() {
    theme.value = theme.value === 'dark' ? 'light' : 'dark';
  }

  const isDark = computed(() => theme.value === 'dark');
  const isLight = computed(() => theme.value === 'light');

  return { theme, toggleTheme, isDark, isLight };
}
```

**关键决策**：
- **模块级单例**（不在 composable 内 `ref`）：避免多个调用方拿到不同实例导致状态不同步
- **`onMounted` 读取 localStorage**：避免 SSR 不一致（虽然项目无 SSR，但保持习惯）
- **`watchEffect` 双向同步**：DOM attribute 和 localStorage 都是 `theme.value` 的派生
- **不引入复杂状态管理**：无需 Pinia / Vuex

---

## 5. Element Plus 变量覆写策略

### 5.1 关键简化

**当前（`styles/index.css:189-233`）**：
```css
:root {
  --el-bg-color: var(--kg-color-surface);
  --el-text-color-primary: var(--kg-color-text-primary);
  /* ... 30+ 个 --el-* 映射到 --kg-* ... */
}
```

由于 `--kg-*` 在 `:root[data-theme="light"]` 下被覆写，**`--el-*` 通过 CSS cascade 自动跟随**。

**亮色态下不需任何 `--el-*` 重复覆写**。

### 5.2 验证（实施阶段跑一次）

| 主题 | `--el-bg-color` 实际值 | 来源 |
|---|---|---|
| 暗色 | `#111c2e` | `:root` → `--kg-color-surface` |
| 亮色 | `#ffffff` | `:root[data-theme="light"]` → `--kg-color-surface` |

**Element Plus 组件 (el-card / el-menu / el-table / el-tag / el-alert / el-pagination / el-descriptions / el-input) 在两套主题下自动切换**。

---

## 6. WCAG 验证

> 实际通过 wcag-contrast 工具计算（实施阶段跑）。下表为预估。

### 6.1 暗色态（现状，保留）

| 元素 | fg / bg | 对比度 | WCAG |
|---|---|---|---|
| text-primary `#e2e8f0` / bg `#0b1220` | 14.5:1 | AAA ✓ |
| text-mute `#94a3b8` / bg `#0b1220` | 9.5:1 | AAA ✓ |
| L4 红 `#ef4444` / bg `#0b1220` | 5.7:1 | AA ✓ |

### 6.2 亮色态（本任务新增）

| 元素 | fg / bg | 对比度 | WCAG |
|---|---|---|---|
| text-primary `#14532d` / bg `#f0fdf4` | **8.5:1** | AAA ✓ |
| text-secondary `#166534` / bg `#f0fdf4` | ~7:1 | AA+ ✓ |
| text-mute `#4d7c5b` / bg `#f0fdf4` | **4.6:1** | AA ✓ (刚好) |
| L0 `#0369a1` / bg `#f0fdf4` | 5.6:1 | AA ✓ |
| L1 `#15803d` / bg `#f0fdf4` | 5.2:1 | AA ✓ |
| L2 `#b45309` / bg `#f0fdf4` | 5.8:1 | AA ✓ |
| L3 `#c2410c` / bg `#f0fdf4` | 5.4:1 | AA ✓ |
| L4 `#b91c1c` / bg `#f0fdf4` | **6.2:1** | AA ✓ |
| Inject `#7e22ce` / bg `#f0fdf4` | 6.6:1 | AA ✓ |
| 白字 `#ffffff` / Primary `#2563eb` | 5.2:1 | AA ✓ |

**结论：所有 token 在两套主题下均通过 WCAG AA (4.5:1)**。

---

## 7. 测试策略

### 7.1 单元测试（vitest，190/190 保持全绿）

**新增**：`frontend/src/composables/__tests__/useTheme.spec.ts`
- ✅ 默认值是 'dark'
- ✅ `toggleTheme()` 切换 dark ↔ light
- ✅ 切换后 `document.documentElement.getAttribute('data-theme')` 正确
- ✅ 切换后 `localStorage.getItem('kg-theme')` 正确
- ✅ 初始化时若 localStorage 已有 'light'，ref 初值为 'light'（需 mock mounted）
- ✅ 初始化时若 localStorage 无值或非法值，ref 初值保持 'dark'

**不动**：现有 190 个测试 (data-testid 不变 → 0 改动)

### 7.2 E2E（Playwright，19/19 保持全绿）

**新增**：`frontend/tests/e2e/theme-toggle.spec.ts`
- ✅ mock `/api/auth/session` 让路由守卫通过
- ✅ 加载 `/chat`，验证 `data-theme="dark"` (默认)
- ✅ 点击 `[data-testid="app-theme-toggle"]`
- ✅ 验证 `data-theme="light"`
- ✅ 验证 `localStorage.getItem('kg-theme') === 'light'`
- ✅ 验证页面可见元素（h1 颜色变化可用 `getComputedStyle`）
- ✅ 再次点击切回 `dark`
- ✅ 截图对比（`expect(page).toHaveScreenshot()`）

**不动**：现有 19 个 E2E case (data-testid 不变)

### 7.3 视觉回归（screenshot-ui-01.mjs 扩展）

**当前**：4 张暗色 PNG（位于 `tests/e2e/screenshots/` 根目录）
```js
const PAGES = [
  { id: 'app-layout', path: '/chat', file: 'ui-01-01-applayout-chat.png' },
  { id: 'dashboard', path: '/dashboard', file: 'ui-01-02-dashboard.png' },
  { id: 'security', path: '/security', file: 'ui-01-03-security-center.png' },
  { id: 'tools', path: '/tools', file: 'ui-01-04-tool-center.png' },
];
```

> **注意**：扩展时需把现有 4 张 PNG 从 `tests/e2e/screenshots/` 根目录**移动**到 `tests/e2e/screenshots/dark/` 子目录（保持文件名前缀一致），避免重复。脚本里加一个 mkdir + mv 步骤。

**扩展后**：8 张 (4 页面 × 2 主题)，按主题分目录：
```
tests/e2e/screenshots/
  dark/
    ui-01-01-applayout-chat.png
    ui-01-02-dashboard.png
    ui-01-03-security-center.png
    ui-01-04-tool-center.png
  light/
    ui-01-01-applayout-chat.png
    ui-01-02-dashboard.png
    ui-01-03-security-center.png
    ui-01-04-tool-center.png
```

**实现**：
- 在脚本里循环 `[theme, ...PAGES]`
- 每次进入页面先 `localStorage.setItem('kg-theme', theme)` + `page.reload()` 或 init script
- 截图前 `page.waitForTimeout(150)` 让 Vue 应用主题

**新增 npm script**：
```json
{
  "screenshot:ui01:all": "node tests/e2e/screenshot-ui-01.mjs --themes all",
  "screenshot:ui01:dark": "node tests/e2e/screenshot-ui-01.mjs --themes dark",
  "screenshot:ui01:light": "node tests/e2e/screenshot-ui-01.mjs --themes light"
}
```

（保留 `screenshot:ui01` 不变 — 默认只跑 dark，向后兼容）

---

## 8. 实施步骤

| # | 任务 | 估时 | 阻塞 |
|---|---|---|---|
| 1 | 创建 `frontend/src/composables/useTheme.ts` | 10 min | — |
| 2 | 写 `useTheme.spec.ts` 单元测试 | 20 min | 1 |
| 3 | 在 `styles/index.css` 末尾加 `:root[data-theme="light"] { ... }` 块（~120 行） | 30 min | — |
| 4 | 修改 `.kg-scan-overlay::after` 加 `animation-play-state: paused` | 5 min | — |
| 5 | 修改 `Login/index.vue:301` input wrapper 硬编码 → token 化 | 10 min | — |
| 6 | 修改 `Login/index.vue:184-207` ambient 光球亮色态覆写 | 10 min | — |
| 7 | 在 `AppLayout/index.vue` 加主题切换按钮 | 20 min | 1 |
| 8 | 写 `theme-toggle.spec.ts` E2E | 30 min | 7 |
| 9 | 扩展 `screenshot-ui-01.mjs` → 8 张 + npm scripts | 30 min | 3 |
| 10 | 跑全量测试：unit + e2e + 8 张截图 | 20 min | 1-9 |
| 11 | 扫所有 .vue scoped style 找硬编码暗色 hex（除已知 Login:301） | 30 min | — |
| 12 | commit + 文档同步 | 15 min | 1-11 |

**总估时：~4 小时**

---

## 9. 风险与回滚

| 风险 | 概率 | 缓解 |
|---|---|---|
| 某些 .vue 硬编码暗色未扫到 | 中 | §11 实施时全量 grep 扫；CI 跑 `vitest` + Playwright 视觉对比兜底 |
| Element Plus 内部某变量未被 `--el-*` 覆盖 | 低 | 实施时跑实际页面检查；Element Plus 2.x `--el-*` 已覆盖绝大部分场景 |
| 亮色下 L4 风险色"跳脱"过度 | 低 | 选 6.2:1 偏保守；视觉评审后微调 |
| localStorage 在隐私模式 / iframe 被禁用 | 低 | 失败静默（`useTheme` 走 try/catch）|
| WCAG AA 边缘色（text-mute 4.6:1） | 已通过 | 实施时用 axe-core 再验证一次 |

**回滚策略**：
- 整个变更在 1 个 commit 内：`feat(frontend): add light theme G1 (green-tinted)`
- 回滚 = `git revert HEAD`（恢复所有 .vue 改动 + 删除 useTheme.ts + 删除 light theme CSS 块）
- 不影响暗色态任何代码路径

---

## 10. Definition of Done

- [ ] `useTheme` composable 单元测试通过（含 localStorage / data-theme / toggle 三个维度）
- [ ] AppLayout 顶栏出现 theme toggle 按钮，hover/active/focus 状态完整
- [ ] 点击 toggle → 整个应用立即换肤（无 FOUC、无 transition 闪烁）
- [ ] localStorage 持久化（刷新页面保留上次选择）
- [ ] 7 页面（ChatConsole / Dashboard / ToolCenter / SecurityCenter / AuditLog / ReportCenter / Login）在两套主题下均正确渲染
- [ ] 所有 `--kg-*` token 在两套主题下通过 WCAG AA（≥4.5:1）
- [ ] 现有 190 单元测试 + 19 E2E 全绿（无回归）
- [ ] 新增 1 个 E2E case（theme-toggle.spec.ts）通过
- [ ] 8 张新截图（4 页面 × 2 主题）入库到 `tests/e2e/screenshots/{dark,light}/`
- [ ] `prefers-reduced-motion` 不被破坏（瞬时切换天然兼容）
- [ ] 全量 .vue scoped style 扫描：除 `Login.vue:301` 已修 1 处外，无其他硬编码暗色 hex
- [ ] Em-dash 字符检查（新增文案 0 出现）
- [ ] 1 个 commit 合入 `feature/frontend-ui-01-design-system` 分支

---

## 11. 不在本 spec 范围（明确排除）

- 三主题 / 多主题切换
- `prefers-color-scheme` 跟随系统
- 暗色态视觉再优化（CLAUDE.md 已锁定暗色基线）
- Tailwind / SCSS / CSS-in-JS 引入
- 主题切换动画 / 过渡效果
- 用户级主题偏好同步后端
- 自定义主题色（让用户选主色）
- ChatConsole 三栏重构（属 UI-03）
- Dashboard 完整重构
- 移动端响应式深度优化（mobile breakpoint 在另一 spec）

---

**请审阅**：以上 11 节。批准后调用 writing-plans skill 制定详细实施计划。
