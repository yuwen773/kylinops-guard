# 演示交付物总览

> 本目录包含 KylinOps Guard 演示所需的全部硬交付物。
> 录制前请阅读 `video/demo-checklist.md`。

## 目录

- `slides/` — 演示 PPT（10~15 页）
- `video/` — 视频脚本最终版 + 视频文件（≤ 7 分钟）+ 演示前检查清单
- `screenshots/` — 截图素材包（≥ 7 张关键场景截图）

## 录制流程

1. 确认 Fix-01/02/03 已合入 master
2. 跑 `docs/demo/video/demo-checklist.md` 中的所有前置检查
3. 启动 dev server
4. 按 `docs/demo/video/demo-script-final.md` 录制
5. 视频后期 + 字幕
6. 截图同步补全

## PPT 下载

`slides/kylinops-demo.pptx` 因体积较大可能未直接 commit 在 git 中。
请从 [Releases] 或团队共享盘获取最新版本。

**PPT 制作要点（12 页）**：

| 页 | 标题 |
|---|---|
| 1 | 封面（团队名 + 赛题名 + 学校） |
| 2 | 痛点（AI 运维幻觉事故案例） |
| 3 | 方案总览（自然语言 → MCP → RiskCheck → 执行 → 审计 → 报告） |
| 4 | MCP 工具矩阵（11+1 工具表 + 风险等级分布） |
| 5 | 安全护栏（L0–L4 决策树 + L4 绝对拦截列表） |
| 6 | 智能化根因分析（**Fix-01 亮点**：场景 2 RCA 推理链截图） |
| 7 | LLM 增强（混合意图分类图 + DeepSeek/Qwen 降级） |
| 8 | 演示场景（4 个场景缩略图） |
| 9 | 部署架构（单 JAR + LoongArch + Kylin V11） |
| 10 | 测试基线（528+1 / 181 / 19+3） |
| 11 | 未来路线（D-05 ~ D-16 简版） |
| 12 | 团队 & 致谢 |

**详细大纲** 见 `slides/ppt-outline.md`（v0.1 已经在 master 中）。

## 视频与截图

- 视频元数据见 `video/demo-recording.md`（SHA256 / 时长 / 分辨率 / 码率 / 大小 — **强制入 Git**）
- 视频文件 `video/demo-recording.mp4` ≤ 7 分钟；体积 ≥ 50MB 时**不入 Git**，仅在最终提交包中提供
- 截图 `screenshots/*.png` 共 7 张，覆盖 4 个演示场景 + 3 个 Fix 亮点
