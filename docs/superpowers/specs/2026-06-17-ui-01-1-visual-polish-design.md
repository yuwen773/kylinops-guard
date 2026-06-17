# UI-01.1 KylinOps Guard 视觉观感修正设计文档

> **状态**：✅ 已批准 — 进入 writing-plans 阶段
> **版本**：v0.1
> **日期**：2026-06-17
> **作者**：UI-01.1 设计（基于 commit 8afc6c4 已落地的 design token + 8 个 common 组件 + 4 页试点 + brainstorming 确认）
> **配套文档**：
> - UI-01 commit：[`8afc6c4`](../../../)（`feature/frontend-ui-01-design-system` 分支）
> - 设计纪律参考：`.claude/skills/design-taste-frontend/SKILL.md`（§0/§4/§9 适用层；§13 OUT OF SCOPE 显式声明）
> - 项目 CLAUDE.md（克线 / 不堆砌 / 安全护栏第一）

---

## 0. 目标与原则

**目标**：在 UI-01 已落地的 design token 和 8 个 common 组件基础上，做一次**轻量视觉观感修正**，让界面从"换了暗色皮肤的后台管理系统"提升为"克制 · 专业 · 有品牌感 · 有安全态势感的麒麟安全智能运维控制台"。本任务**不是大重构**，不新增后端功能，不改变业务逻辑。

**原则**：

1. **不破坏基线**：现有 build + 237/237 unit + 19/19 + 3 skipped E2E 必须继续通过；动态基线，不锁死数字。
2. **不扩展产品边界**：仅做视觉修正 + 轻量信息层级优化；不做 Demo Mode、不做 ChatConsole 三栏重构（属 UI-03）、不做 Dashboard 完整重构、不引入新 UI 库。
3. **不动 backend**：Tool 分类走前端固定映射，不改 `ToolDefinition` / 不改 backend 任何接口 / 不改 `MockMvc` 测试。
4. **不复用 UI-02 / UI-03 范围**：UI-02 留给交互动效与微交互强化；UI-03 留给 ChatConsole 三栏 / 工作台视图。本期仅做静态信息层级。
5. **设计纪律优先**：design-taste-frontend §0 brief 推断 + §4 anti-slop 纪律 + §9 AI Tells 清单；§13 OUT OF SCOPE 显式声明（dashboard 不走 landing aesthetic）。
6. **Em-dash 零容忍**：所有新增文案不出现 `—`，含 welcome card / Hero / 安全态势摘要 / 工具能力概览；现存量 grep 排查。
7. **Brand 一致性**：全站暗色 + `--kg-*` token + Element Plus 暗色覆写；无 light section 翻转、无 color inconsistency、无 radius inconsistency。
8. **复用优先**：所有新增 UI 复用 `AppRiskBadge` / `AppSectionHeader` / `AppEmptyState` / `AppStateBanner` / `AppLoadingState` / `AppErrorState`。
9. **测试覆盖**：新组件 + 新映射 + 新文案必须配单元测试；E2E 不强制加 case 但已有 case 必须不破。

---

## 1. 关键决策记录（来自 brainstorming 阶段）

| 决策 | 选择 | 理由 |
|---|---|---|
| **Header 方案** | 方案 A：中文优先（KG / 麒麟安全智能运维 Agent / KylinOps Guard） | 比赛答辩场景中文主标题优先；副标题英文品牌名做国际化感 |
| **Tool 分类数据源** | 前端固定映射（`TOOL_CATEGORIES` 字典） | 不改 backend 不增 mock 字段；10 个工具分类确定，可枚举穷尽 |
| **ChatConsole 快捷场景形态** | 5 个独立场景卡 + 2×2 + 1 网格（不规则） | 2+2+1 不规则网格比 5 等分按钮更有产品首屏感；§9.C "three-equal cards" 禁令规避 |
| **Dashboard Hero 状态数据** | 复用已有 `/api/dashboard/overview` 字段映射：状态 from `score`+`degraded` / 风险事件占位 `—` / 审计 ID from `auditId` | 不新增后端接口；缺失字段用占位 |
| **Inject 在 Security Center 位置** | 独立说明卡，不挤进 L0-L4 矩阵 | 后端 L0-L4 不含 Inject enum；强行混入破坏矩阵语义；独立卡更清晰 |
| **L4 视觉权重** | 边框 + tone-on-tone 底色强化（不依赖红/警示色重复） | §4.2 color consistency lock 约束下，强化层级用边框 + 底色而非第二色 |
| **"BLOCK BLOCK 阻断"重复** | 拆为 `决策：阻断执行` + `系统动作：写入审计日志` 两行独立 row | 视觉一致；信息密度更合理 |
| **工具表格列** | 删 `border-b` 全开，改 hover 底色；列宽按 1280 重排保留完整 `最近调用` 列 | §4.9 content density 纪律；现列宽 1280 下明显截断 |
| **测试命令** | `npm run build` + `npm run test:unit -- --run` + `npm run test:e2e`（项目现有脚本） | 用户原文 `npx playwright test` 等价于项目 `npm run test:e2e`，无需新加脚本 |
| **Commit 策略** | 新 commit（不 amend 8afc6c4）：`fix(frontend): polish KylinOps Guard visual hierarchy` | 8afc6c4 未推送但已是独立交付节点；新 commit 保留 UI-01 历史可追溯 |
| **设计 discipline 范围** | 仅采用 §0 / §4 / §9 适用层；显式声明 §13 OUT OF SCOPE（不引入 bento / marquee / glassmorphism / scroll hijack） | KylinOps Guard 是 admin panel，不走 landing aesthetic；保持专业克制 |

---

## 2. 范围

### 2.1 必做（UI-01.1 必交付）

#### 任务 1：Header 品牌区方案 A
- AppLayout.vue 重构 logo 区：
  - Logo Mark（KG 字符方块 / 麒麟纹简化形，二选一待 Plan 阶段确认）
  - 主标题：`麒麟安全智能运维 Agent`（中文，font-weight 600）
  - 副标题：`KylinOps Guard`（英文，font-weight 400，token `--kg-text-tertiary`）
  - 登出按钮 ghost 态，对比度验证 WCAG AA
- 现有 sidebar / Header 高度 / 路由契约 / nav 测试不变

#### 任务 2：ChatConsole 首屏重做
- 删白色空状态块 + Element Plus 默认空态
- 新增暗色欢迎卡：
  - 主标：`我是 KylinOps Guard，可以帮你诊断系统异常、识别风险操作并生成审计记录`
  - 副标：`你可以直接输入自然语言运维请求，或选择下方场景开始演示`
  - 5 个独立场景卡（2+2+1 网格）：
    1. `系统健康巡检` / CPU、内存、磁盘全维度扫描
    2. `磁盘空间分析` / 大文件、占用诊断
    3. `服务状态诊断` / nginx、journal 联合诊断
    4. `危险命令拦截` / rm -rf 等 L4 规则演示
    5. `Prompt Inject 测试` / 注入检测 + 安全拦截
  - 每张卡有 `@element-plus/icons-vue` 图标 + 标题 + 一句描述 + hover 反馈
- 输入区强化：
  - 输入框背景 `--kg-bg-overlay` 与页面统一（删白色 textarea）
  - 发送按钮主色 token，禁用态可见
  - placeholder：`请输入自然语言指令，例如：帮我检查 nginx 服务是否正常`

#### 任务 3：Dashboard 顶部 Hero
- 新增 Hero 区域（在 `健康分 / 指标覆盖率 / 采集时间` 三卡之上）：
  - 主标：`麒麟安全智能运维态势台`
  - 副标：`实时汇聚系统健康、风险拦截、工具调用与审计追踪，帮助运维人员快速定位异常并安全处置`（≤20 词）
  - 右侧 3 个轻量状态：
    - 当前状态：从 `score` + `degraded` 派生；>85 绿色 Healthy / 60-85 黄色 Warning / <60 红色 Critical
    - 今日风险事件：占位 `—`（后端无字段，标注待 P1-X 接入）
    - 最近审计 ID：直接展示 `auditId`
- 复用 `AppSectionHeader`，不写新组件

#### 任务 4：Security Center 轻量修正
- 顶部新增安全态势摘要区（4 个轻量 tile）：
  - 风险等级策略（L0-L4）
  - 阻断事件数（来自 `/api/security/events`）
  - Prompt Inject（独立说明卡）
  - 可审计追踪（`/api/audit/logs` 条数占位）
- 风险等级目录改"策略矩阵"：
  - 5 张卡 L0-L4（**不是 6 张**；Inject 不混入）
  - 每张卡：等级 + 名称 + 决策 + 典型场景
  - L4 边框 + tone-on-tone 底色（`--kg-danger-soft`）强化
- Inject 独立卡（位置：L0-L4 矩阵下方）：
  - 标题：`Prompt 注入检测`
  - 文案：`独立于 L0-L4 矩阵的旁路检测层，对所有用户输入执行语义识别，命中规则后立即升级为 L4 阻断并写入审计日志`
- 修正文案重复：
  - 旧：`BLOCK BLOCK 阻断`
  - 新：`决策：阻断执行` / `系统动作：写入审计日志`（两独立 row）
- 安全事件列表改卡片样式：
  - 每事件 = 一张紧凑卡（事件 ID / 时间 / 风险等级 / 决策 / 命中规则 / 原因）
  - hover 底色强化

#### 任务 5：Tool Center 轻量增强
- 表格上方新增 4 项能力概览：
  - 已注册工具数
  - 只读工具数（L0）
  - 需确认工具数（L2）
  - 高风险 / 阻断工具数（L3+L4）
- 增加分类筛选 chips（5 类）：`全部` / `系统信息` / `资源监控` / `磁盘诊断` / `服务诊断` / `安全治理`
  - 数据源：新增 `frontend/src/tool/categories.ts` 固定映射
  - 当前选中态用 `--kg-accent` 边框 + tone-on-tone 底色
- 表格列宽重排：
  - `工具` / `风险` / `权限` / `状态` / `调用次数` / `成功率` / `最近调用`
  - 1280 宽度下 `最近调用` 列不被截断（最小宽度 + ellipsis 兜底）
  - 行 hover 底色（删 `border-b` 全开）
- 表格行距 / 字体 / 边框 / hover 状态整体优化

#### 任务 6：全局视觉细节
- 删所有 El 默认白色面板（`el-empty` / `el-card` 等无 token 包裹处）
- 修中英文无间距黏连
- 按钮对比度验证（refresh / 发送 / 登出 / 各场景卡）
- 卡片边框 weight 统一（`--kg-border-hairline` 1px）
- 文本层级验证（hero / 段标 / 列表 / 注释）
- 删重复文案（`BLOCK BLOCK 阻断` 已处理；其他 em-dash grep）
- 表格列在 1280 不被截断
- 大面积空白必须有引导（hero 副标题 / 欢迎卡副标 / 分类筛选等）
- 1280×720 截图全局 review

#### 任务 7：测试与交付
- `npm run build`（vue-tsc + vite build）
- `npm run test:unit -- --run`（目标 237+ baseline；新组件 / 新映射加测试）
- `npm run test:e2e`（19/19+3skipped 基线不破）
- 重跑 `node frontend/tests/e2e/screenshot-ui-01.mjs`（已修 mock envelope code 0→200）
- 截图覆盖：AppLayout+ChatConsole 首屏 / Dashboard Hero / Security Center 策略矩阵 + Inject / Tool Center 分类筛选
- 输出：4 张主截图 + 交付报告草稿（路径记录即可，不强制 markdown）

### 2.2 不做（UI-02 / UI-03 范围或显式约束）

- 不新增后端接口；不改 backend 任何文件
- 不引入新 UI 库 / 动画库
- 不做 ChatConsole 三栏重构（属 UI-03）
- 不做 Dashboard 完整重构（仅顶部 Hero）
- 不做 Demo Mode
- 不改路由结构 / 路由守卫 / 认证契约
- 不删 UI-01 已新增的 8 个 common 组件（即使本期未使用）
- 不破坏现有 nav / auth / chat / dashboard / security / tool 契约测试

---

## 3. 设计纪律（design-taste-frontend §0/§4/§9 适用层）

### 3.1 Design Read（一行声明）
> **Reading this as: 麒麟 OAM 安全运维控制台 / 答辩评委 + 国资央企运维人员, with a 克制 · 稳定 · 可信 language, leaning toward 已有的 --kg-* token + Element Plus 暗色覆写 + Element Plus 默认组件形态微调.**

### 3.2 三轴旋钮

| Dial | 值 | 理由 |
|---|---|---|
| `DESIGN_VARIANCE` | 4 | 受监管/安全控制台不能用 arts chaos；保守层级稳定 |
| `MOTION_INTENSITY` | 3 | 静态优先，仅 hover/active 微反馈；`prefers-reduced-motion` 默认即正确态 |
| `VISUAL_DENSITY` | 5 | 控制台需信息但不拥挤 |

### 3.3 适用纪律

- §4.5 Button Contrast Check：所有 CTA / 按钮对比度验证 WCAG AA 4.5:1
- §4.5 CTA 不换行：桌面宽度下文案不强制换行
- §4.5 No Duplicate CTA Intent：Dashboard / SecurityCenter / ToolCenter 不重复「刷新 + 重新加载 + Reload」
- §4.5 Tactile Feedback：按钮 `:active` `-translate-y-[1px]` 或 `scale-[0.98]`
- §4.11 Page Theme Lock：全站暗色，无 light 段落插入
- §4.4 Shape Consistency Lock：仅用 `--kg-radius-{sm,md,lg}`，不混用 6/10/14
- §4.2 Color Consistency Lock：全站统一 risk 红 / 警告黄 / 成功绿
- §9.G Em-dash 零容忍：所有新增文案 0 个 `—`；现存量 grep
- §9.F No version labels in hero / No section-numbering eyebrows / No scroll cues / No micro-meta sentences
- §9.D No fake-precise numbers：mock 数据去 `95.00%` 精确度
- §9.C No three-equal feature cards：5 场景卡 2+2+1 不规则网格
- §4.9 Content density：Tool Center 表格行间距加大，删 `border-b` 全开
- §4.9 Copy self-audit：每段新文案复检语法 / 不空泛 / 不 AI 卖弄
- §4.8 Real images / icons：icon 走 `@element-plus/icons-vue` 已有图标，不手画 SVG

### 3.4 OUT OF SCOPE 词汇（landing-only）

明确**不引入**：
- Bento / Marquee / Glassmorphism / Liquid Glass / Sticky-stack / Horizontal hijack
- Kinetic typography / Mesh gradient / Parallax tilt / Spotlight border / Aurora
- Magnet hover / Liquid Pull-to-Refresh / Spotlight Border Card

KylinOps Guard 是 admin panel，按 skill §13 走 dashboard 路径；本任务仅借用 §0/§4/§9 适用层。

---

## 4. 文件改动清单

### 4.1 修改文件

| 文件 | 改动范围 |
|---|---|
| `frontend/src/layouts/AppLayout.vue` | Header 品牌区改方案 A（Logo Mark + 中文主标 + 英文副标） |
| `frontend/src/pages/ChatConsole/index.vue` | 删白空状态 + 欢迎卡 + 5 场景卡 + 输入区强化 |
| `frontend/src/pages/Dashboard/index.vue` | 顶部 Hero 区域 |
| `frontend/src/pages/SecurityCenter/index.vue` | 安全态势摘要 + 策略矩阵（L0-L4 + Inject 独立）+ 事件卡片 + 文案修正 |
| `frontend/src/pages/ToolCenter/index.vue` | 4 项能力概览 + 分类筛选 chips + 表格列宽 + hover 底色 |
| `frontend/src/styles/index.css` | 微调：白底空状态 token 覆盖、Hero 渐变 token、场景卡 hover、表格 hover 底色 |

### 4.2 新增文件

| 文件 | 用途 |
|---|---|
| `frontend/src/tool/categories.ts` | `TOOL_CATEGORIES` + `CATEGORY_META`（label / icon / order） |
| `frontend/src/tool/categories.spec.ts` | 单元测试：映射完整 / 无重复 / icon 存在 |

### 4.3 测试覆盖

- `frontend/src/tool/categories.spec.ts`（新）
- 各 page `index.spec.ts`（如存在）补充新组件渲染测试
- 现有 237/237 unit + 19/19 + 3 skipped E2E 基线不破

---

## 5. 数据契约（前端固定映射）

### 5.1 Tool 分类映射（`frontend/src/tool/categories.ts`）

```ts
export type ToolCategory =
  | '系统信息'
  | '资源监控'
  | '磁盘诊断'
  | '服务诊断'
  | '安全治理';

export const TOOL_CATEGORIES: Record<string, ToolCategory> = {
  system_info_tool: '系统信息',
  cpu_status_tool: '资源监控',
  memory_status_tool: '资源监控',
  disk_usage_tool: '磁盘诊断',
  large_file_scan_tool: '磁盘诊断',
  service_status_tool: '服务诊断',
  network_port_tool: '服务诊断',
  process_list_tool: '服务诊断',
  process_detail_tool: '服务诊断',
  journal_log_tool: '安全治理',
};

export interface CategoryMeta {
  label: ToolCategory;
  icon: string;          // @element-plus/icons-vue 已存在
  order: number;
}

export const CATEGORY_META: CategoryMeta[] = [
  { label: '系统信息', icon: 'Monitor',     order: 1 },
  { label: '资源监控', icon: 'DataLine',    order: 2 },
  { label: '磁盘诊断', icon: 'Files',       order: 3 },
  { label: '服务诊断', icon: 'Tools',       order: 4 },
  { label: '安全治理', icon: 'Lock',        order: 5 },
];

export const ALL_CATEGORIES_FILTER: Array<ToolCategory | '全部'> = [
  '全部', '系统信息', '资源监控', '磁盘诊断', '服务诊断', '安全治理',
];
```

### 5.2 Dashboard Hero 状态派生

| 来源字段 | 派生逻辑 |
|---|---|
| `score` + `degraded` | `score >= 85 && !degraded` → Healthy（绿）/ `60 <= score < 85` 或 `degraded` → Warning（黄）/ `score < 60` → Critical（红） |
| 今日风险事件 | 后端无字段 → 显示 `—` 占位 |
| 最近审计 ID | 直接展示 `auditId` |

### 5.3 Security Center 阻断文案统一表

| 决策 | 文案 | 系统动作 |
|---|---|---|
| ALLOW | `决策：允许执行` | `系统动作：直接返回` |
| CONFIRM | `决策：确认后执行` | `系统动作：待用户确认` |
| BLOCK | `决策：阻断执行` | `系统动作：写入审计日志` |

---

## 6. 验收标准

### 6.1 视觉验收（1280×720 截图）

- [ ] Header 品牌区不再拥挤（方案 A 三层结构清晰）
- [ ] ChatConsole 首屏有"AI 运维工作台"感（暗色欢迎卡 + 5 场景卡 2+2+1）
- [ ] Dashboard 顶部有"安全态势首页"感（Hero + 3 状态）
- [ ] Security Center 有清晰策略矩阵（L0-L4 5 卡 + Inject 独立 + 事件卡片）
- [ ] Tool Center 不再是普通表格后台（4 概览 + 5 分类筛选 + 列宽合理）
- [ ] 无突兀白色大块
- [ ] 无 em-dash 字符（grep 验证）
- [ ] 1280×720 下 4 主页面不拥挤不截断

### 6.2 功能验收

- [ ] 不破坏 237/237 unit + 19/19 + 3 skipped E2E 基线
- [ ] `npm run build` exit 0（vue-tsc + vite build）
- [ ] 不修改 backend 任何文件
- [ ] 不引入新 UI 库
- [ ] 不删除 UI-01 已新增的 8 个 common 组件

### 6.3 设计纪律验收

- [ ] 全站暗色（Page Theme Lock）
- [ ] Color consistency lock（risk 红 / 警告黄 / 成功绿 不跳色）
- [ ] Shape consistency lock（仅 `--kg-radius-*`）
- [ ] Button contrast check（WCAG AA）
- [ ] No duplicate CTA intent
- [ ] Em-dash 零容忍（grep 验证）
- [ ] No three-equal feature cards
- [ ] No version labels / section-numbering eyebrows / scroll cues

### 6.4 交付物

- [ ] 修改文件列表
- [ ] 主要视觉修正说明
- [ ] 4 张主截图路径（`frontend/tests/e2e/screenshots/ui-01-0{1..4}-*.png`）
- [ ] 测试结果（build / unit / e2e）
- [ ] git status（feature/frontend-ui-01-design-system 分支）
- [ ] 是否建议 commit（建议：`fix(frontend): polish KylinOps Guard visual hierarchy`）

---

## 7. 风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| Em-dash 在存量代码残留 | 视觉一致性破坏 | Plan 阶段先 grep + 集中清理 |
| `el-empty` / `el-card` 等未 token 包裹导致白底 | 视觉跳脱 | 优先复用 `AppEmptyState`；若有遗漏则 `styles/index.css` 加全局 override |
| Tool Center 列宽重排破坏现有表格测试 | E2E 基线破坏 | 列宽调整前先跑基线；调整后回归；若冲突则记录到测试更新 |
| Welcome 卡 / Hero 文案有 AI 卖弄痕迹 | §9 禁令 | Plan 阶段文案 self-audit；em-dash + 假精确数字 + 模板化比喻逐项检查 |
| Logo Mark 设计争议 | 设计语言不一致 | Plan 阶段先确认 Logo Mark 形态（KG 字符方块 vs 麒麟纹简化），避免阻塞实现 |
| 新增 unit 测试与现有 AppRiskBadge / AppEmptyState 等冲突 | 测试基线破坏 | 新测试只覆盖新组件 / 新映射，不触碰已有测试 |

---

## 8. 后续路径（UI-02 / UI-03 入口，不在本期做）

- **UI-02**：交互动效与微交互强化（hover 物理 / scroll-reveal / 状态过渡）；`MOTION_INTENSITY` 升到 5-6；新增 GSAP / Motion 引入评估
- **UI-03**：ChatConsole 三栏重构（左历史 / 中会话 / 右上下文）+ 工作台视图
- **P1-X**：Dashboard Hero「今日风险事件」字段接入 / 安全态势摘要区接真实接口
- **P1-X**：Security Center 安全事件卡片接 `/api/security/events` 真实分页

---

## 9. 元信息

- **分支**：`feature/frontend-ui-01-design-system`
- **基于 commit**：`8afc6c4 feat(frontend): add KylinOps Guard design tokens and shared UI states`
- **目标 commit message**：`fix(frontend): polish KylinOps Guard visual hierarchy`
- **预期影响范围**：仅 `feature/frontend-ui-01-design-system` 分支；不推远端；等用户确认后再 merge main
- **依赖项**：无新增 npm 依赖；纯 `frontend/src/**` 文件改动 + styles 微调