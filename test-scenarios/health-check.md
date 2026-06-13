# 场景 1：系统健康巡检

## 概述

- **演示视频时段**：00:55 – 02:00
- **目标能力**：证明 Agent 能感知操作系统、按需 fan-out 调用多个只读工具、综合生成健康评分与报告
- **不依赖 seed-demo.sh**：自然系统状态即可（即便无任何 seed，也能跑通）

## 前置准备

- 后端 + 前端已启动
- ChatConsole 页面打开
- 无特殊依赖（健康巡检调用 7 个 L0 工具，覆盖 CPU / 内存 / 磁盘 / 进程 / 服务 / 端口 / 日志）

## 演示步骤

1. 在 ChatConsole 输入框粘贴：
   ```
   帮我检查当前系统健康状态
   ```
2. 点击发送按钮（或按 Enter）
3. 等待响应（性能预算：≤ 30s 完整流程，单元调用 ≤ 3s）
4. 观察：
   - 回复区域显示健康评分（0–100）和分项指标
   - 工具调用卡片按顺序展开（system_info → cpu → memory → disk → process → service → port → log）
   - 风险等级标签为绿色（L0）
   - 审计编号显示在卡片底部

## 预期工具调用

| 顺序 | 工具名 | 输入 | 预期输出（摘要） |
| --- | --- | --- | --- |
| 1 | system_info_tool | `{}` | 主机名、内核、架构、运行时间 |
| 2 | cpu_status_tool | `{}` | usagePercent / loadAvg |
| 3 | memory_status_tool | `{}` | totalMB / usedMB / usagePercent |
| 4 | disk_usage_tool | `{}` | 各分区 use% / mount |
| 5 | process_list_tool | `{}` | top N 进程 |
| 6 | service_status_tool | `{}` | systemd 单元状态（inactive / active / failed 各若干） |
| 7 | network_port_tool | `{}` | 监听端口清单 |
| 8 | journal_log_tool | `{"lines": 50}` | 最近错误日志摘要 |

**实施注**：6 个工具并行批次 order=0（已知修复），不串行阻塞。

## 预期风险等级与决策

- **风险等级**：L0（全部为只读工具，无 prompt 注入）
- **决策**：ALLOW
- **命中规则**：无
- **LLM 决策路径**：规则化回复（健康评分 ≤ 90 即视为存在异常，> 90 健康）

## 预期审计记录

- **auditId**：UUID v4，例 `a1b2c3d4-...`
- **status**：SUCCESS
- **sessionId**：当前会话 ID
- **userInput**：`帮我检查当前系统健康状态`
- **intentType**：HEALTH_CHECK
- **toolCallCount**：8
- **riskLevel**：L0
- **matchedRules**：空数组
- **executionResult**：每个工具的成功摘要

**验证方式**：进入 AuditLog 页面，用 auditId 过滤或时间窗口过滤，验证详情能完整回放 8 个工具的输入输出。

## 反例与边界

- ❌ **不应**在没有调用任何工具的情况下返回健康评分——禁止模型"幻觉"系统状态（任务卡 §10 #4）
- ❌ **不应**出现 L1/L2/L3/L4 标签
- ❌ **不应**在工具调用全部失败时仍返回成功状态——若 6 个 L0 工具全部 `failed`，健康评分必须低于 50 并标注"系统感知受限"
- ✅ **可以**在 Windows 主机上跑通——10 个 L0 工具均有 Windows / Linux / 降级（/proc）三档处理

## 验证清单

- [ ] 健康评分字段显示
- [ ] 8 个工具调用卡片按顺序展示
- [ ] 风险等级为 L0 绿色
- [ ] 审计编号非空
- [ ] 详情页可看到 8 个工具的完整输入输出
- [ ] 整体响应 ≤ 30s（性能预算）