# 演示场景目录（test-scenarios/）

> **任务来源**：任务卡 Task 18 — 演示场景与测试数据
> **目的**：把 4 个 P0 演示场景 + 1 个 Prompt Injection 防护场景固化为可复现的验收用例，配合 `deploy/scripts/seed-demo.sh` 跑出确定性的演示录像。

## 场景清单

| # | 场景 | 文件 | 演示视频时段 | seed-demo.sh 是否必需 |
| --- | --- | --- | --- | --- |
| 1 | 系统健康巡检 | [`health-check.md`](./health-check.md) | 00:55 – 02:00 | 否（自然状态即可） |
| 2 | 磁盘空间异常分析 | [`disk-pressure.md`](./disk-pressure.md) | 02:00 – 03:25 | **是** |
| 3 | 服务状态诊断 + L2 确认执行 | [`service-diagnosis.md`](./service-diagnosis.md) | 03:25 – 04:35 | 推荐（需 nginx 服务存在） |
| 4 | 危险命令拦截 | [`dangerous-command.md`](./dangerous-command.md) | 04:35 – 05:00 | 否 |
| 5 | Prompt Injection 防护 | [`prompt-injection.md`](./prompt-injection.md) | 05:00 – 05:45 | 否 |

## 每个场景文件结构

每份 markdown 严格按以下 7 段组织，方便测试人员对照执行：

1. **概述**：对应演示时段、目标能力点
2. **前置准备**：seed 脚本是否需要、目标工具是否在线
3. **演示步骤**：按顺序给出用户输入文本与点击动作
4. **预期工具调用**：工具名 + 输入 + 预期输出
5. **预期风险等级与决策**：L0–L4、ALLOW/CONFIRM/BLOCK、命中规则
6. **预期审计记录**：auditId 模式、状态、关键字段
7. **反例与边界**：明确**不应**发生的情况

## 如何回归运行

```bash
# 1. 准备演示数据（仅 Linux 主机）
sudo bash deploy/scripts/seed-demo.sh

# 2. 启动系统
bash deploy/scripts/start-backend.sh
bash deploy/scripts/start-frontend.sh

# 3. 在前端 ChatConsole 按场景文档逐个执行
# 4. 在 SecurityCenter / AuditLog 验证拦截事件和审计链路

# 5. 跑完清理（务必执行）
sudo bash deploy/scripts/seed-demo-cleanup.sh
```

## 与演示视频脚本的关系

演示视频脚本（`演示视频脚本 v0.1.md`）定义了**叙事结构**——讲什么、什么顺序、配什么画面。
本目录的 5 份 markdown 是**操作手册**——具体每条消息发什么、点哪个按钮、看到什么算通过。
两者一一对应，不冲突；演示视频脚本 v0.1 是 v0.1 阶段的产品叙事，本目录是当前 v0.1 阶段实现的具体场景固化。

未来若演示视频脚本升级到 v0.2，本目录的 markdown 应当跟着同步刷新，并保留 v0.1 版本快照。