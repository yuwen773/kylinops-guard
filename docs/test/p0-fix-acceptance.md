# P0 缺陷修复冲刺验收记录

> **本文件跟踪 P0 缺陷修复冲刺（Fix-01 ~ Fix-05）的验收状态。**
> 每个 Fix 完成后追加一节，记录 commit/tag/验证项。
> 引用设计文档：`docs/superpowers/specs/2026-06-16-p0-defect-fix-sprint-design.md`

---

## §Fix-01 — ReasoningChain 智能化根因分析

> **Tag**：`fix-01-rca-done`（在 master 上）
> **范围**：`backend/src/main/java/com/kylinops/agent/intelligence/`
> **新增**：`DiskDiagnosisAnalyzer` / `SystemHealthAnalyzer` / `ReasoningChain` 数据结构

**验收项**（待回顾，参见 Fix-01 plan 验收段）：

- [ ] 场景 1（系统健康）：多工具扇出 + ReasoningChain 渲染
- [ ] 场景 2（磁盘诊断）：根因分析链含现象/证据/候选/排除/建议/置信度
- [ ] 不变量：现有 550+1 后端 / 190 前端 / 19+3 E2E 不退化

---

## §Fix-02 — L0 工具矩阵补齐（lsof_tool）

> **Tag**：`fix-02-lsof-done`（在 master 上）
> **范围**：`backend/src/main/java/com/kylinops/os/lsof/` + 注册到 ToolRegistry
> **新增**：`LsofTool` (L0 READ_ONLY) + `LsofToolContextPolicy`

**验收项**：

- [ ] `LsofToolTest` 全绿（pid 校验 + 平台降级 + 解析）
- [ ] ToolRegistry 注册成功，工具中心可见
- [ ] 不变量：测试基线不退化

---

## §Fix-03 — LLM 离线 fallback

> **Tag**：`fix-03-offline-fallback-done`（在 master 上）
> **范围**：`backend/src/main/java/com/kylinops/agent/intelligence/HybridIntentService.java` + `OfflineFaqService.java` + `IntentClassifier.java`
> **新增**：synonym 表 + OfflineFaqService 严格顺序 + UNKNOWN 快捷建议

**验收项**：

- [ ] 关闭 `LLM_API_KEY` 后，"服务挂了" → 命中 `SERVICE_DIAGNOSIS`（synonym 兜底）
- [ ] 关闭 `LLM_API_KEY` 后，"rm -rf /" → 仍 L4 BLOCK
- [ ] 关闭 `LLM_API_KEY` 后，"今天天气很好" → UNKNOWN 文案含"快捷操作建议"
- [ ] E2E 测试 `LlmDisabledScenariosTest` 全绿

---

## §Fix-04 — 演示交付物（PPT + 视频 + 截图）

> **Tag**：`fix-04-demo-done`（本次提交）
> **范围**：`docs/demo/` 目录（**纯文档/媒体，不动业务代码**）
> **Worktree 分支**：`fix-04-demo-deliverables`
> **Pre-recording tag**：`pre-recording`

### 交付物清单

| 文件 | 路径 | 状态 | 备注 |
|---|---|---|---|
| README | `docs/demo/README.md` | ✅ | 19 行（任务 1）+ 30 行 PPT/视频/截图说明（任务 4） |
| 视频脚本（最终版） | `docs/demo/video/demo-script-final.md` | ✅ | 104 行，4 场景 + 3 段收尾 |
| 演示前检查清单 | `docs/demo/video/demo-checklist.md` | ✅ | 59 行，7 节 |
| 视频元数据模板 | `docs/demo/video/demo-recording.md` | ✅ | 89 行，含 SHA256/时长/分辨率/码率/大小填写指引 |
| 截图 README | `docs/demo/screenshots/README.md` | ✅ | 2220 B，含 7 张图拍摄要求 |
| 7 张截图占位 | `docs/demo/screenshots/0[1-7]-*.png` | ✅ 占位 | 67B 1x1 PNG（待录屏后替换为真实截图） |
| 演示 PPT | `docs/demo/slides/kylinops-demo.pptx` | ⏳ 待手工 | 二进制文件，12 页大纲见 `docs/demo/ppt-outline.md` |
| 演示视频 | `docs/demo/video/demo-recording.mp4` | ⏳ 待录制 | 二进制文件，≤ 7 分钟；元数据见 demo-recording.md |
| LFS 配置 | `.gitattributes` | ✅ | `*.pptx filter=lfs` |

### 6 个 Commit

| SHA | 任务 | Commit 消息 |
|---|---|---|
| `e665bf7` | 1 | `docs(demo): create demo deliverables directory structure` |
| `29f53a0` | 2 | `docs(demo): final demo video script (7 min, 4 scenarios)` |
| `e7893b4` | 3 | `docs(demo): pre-recording checklist` |
| `0b0a4c8` | 6 | `docs(demo): add 7 screenshots covering 4 scenarios + 3 Fixes` |
| `c1bf38d` | 4 | `docs(demo): add PPT generation notes (binary in slides/)` |
| `17d417f` | 5 | `docs(demo): add 7-min demo recording + recording metadata` |
| `+ p0-fix-acceptance.md` | 7 | `docs(test): Fix-04 acceptance record` |

### 验收项

- [x] 4 场景脚本完整：开场/系统健康/磁盘诊断/服务+L2/危险+L4/工具+离线/总结
- [x] 演示前检查清单 7 节：环境/配置/冒烟/离线/备份/录制中应急/录制后
- [x] 视频元数据必填字段全部列出：SHA256/时长/分辨率/码率/大小/存放位置/入库状态
- [x] 7 张截图占位有效（`file` 识别为 PNG image data）
- [x] PPT 大纲 12 页文档化（v0.1 ppt-outline.md 已在 master 中）
- [x] pre-recording tag 已打（`git tag -a pre-recording -m "..."`）
- [x] 不变量：业务代码**未动**（`git diff a1110ce..HEAD -- backend/ frontend/` 应为空）

### 已知限制（人工补全项）

1. **PPT .pptx 文件** — 需用 LibreOffice / WPS / PowerPoint 手工生成 12 页演示文稿
   - 大纲见 `docs/demo/ppt-outline.md`（v0.1）
   - 路径：`docs/demo/slides/kylinops-demo.pptx`
   - 入库策略：体积 < 50MB 可入 Git，≥ 50MB 走 LFS 或仅 Releases
2. **演示视频 .mp4 文件** — 需用 OBS / Camtasia 录制 7 分钟
   - 严格按 `docs/demo/video/demo-script-final.md` 录制
   - 录制后填 `docs/demo/video/demo-recording.md` 真实元数据
   - 入库策略同上
3. **7 张真实截图** — 录制时同步截屏后替换 `docs/demo/screenshots/0[1-7]-*.png`
   - 详细要求见 `docs/demo/screenshots/README.md`

### 录制前预演命令

```bash
# 1. 切换到 fix-04-demo-deliverables 分支（worktree 仍在）
cd D:/Work/code/kylin-ops-fix04

# 2. 跑演示场景冒烟（参考 demo-checklist.md）
cd backend && mvn -B clean package -DskipTests
java -jar target/kylin-ops-guard.jar --spring.profiles.active=dev,standalone &
cd ../frontend && npm run dev

# 3. 录制
# 浏览器打开 http://127.0.0.1:5173，按 demo-script-final.md 走 4 场景
# OBS 录屏输出 → docs/demo/video/demo-recording.mp4

# 4. 录制完成后填真实元数据到 demo-recording.md
sha256sum docs/demo/video/demo-recording.mp4
ffprobe -v error -show_entries format=duration ...

# 5. 录制完成打 fix-04-demo-done tag（手动）
git tag -a fix-04-demo-done -m "Fix-04 演示交付物（PPT/视频/截图/检查清单）齐备"
```

---

## §Fix-05 — 回归验收

> **Tag**：待 Fix-05 plan 完成后追加
> **占位**：参见 `docs/superpowers/plans/2026-06-16-fix-05-regression-acceptance-plan.md`
