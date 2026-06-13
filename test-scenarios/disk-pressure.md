# 场景 2：磁盘空间异常分析

## 概述

- **演示视频时段**：02:00 – 03:25
- **目标能力**：证明 Agent 能根因分析磁盘问题、给出**安全清理建议**（而非自动删除）
- **依赖 seed-demo.sh**：是

## 前置准备

```bash
sudo bash deploy/scripts/seed-demo.sh
```

seed 后应存在：
- `/var/log/app.log`：~200MB 随机字节文件（磁盘异常的根因）
- `/tmp/cache-demo/`：若干可清理缓存文件
- `/var/lib/mysql/`：仅目录标记（用于演示 `large_file_scan_tool` 标记为敏感路径）
- 主分区磁盘使用率 ≈ 86%

## 演示步骤

1. 在 ChatConsole 输入框粘贴：
   ```
   帮我看看磁盘为什么快满了，并给我安全清理建议
   ```
2. 点击发送
3. 观察：
   - 工具调用卡片：先 disk_usage_tool → large_file_scan_tool
   - 回复区域显示：分区使用率、根因文件 `/var/log/app.log`、建议清理 `/tmp/cache-demo/`
   - 风险等级标签为绿色（L0）
   - **重要**：回复中**不应**包含"已删除"或"已清理"等执行性语言

## 预期工具调用

| 顺序 | 工具名 | 输入 | 预期输出（摘要） |
| --- | --- | --- | --- |
| 1 | disk_usage_tool | `{}` | 各分区使用率，含 use% > 85% 的主分区 |
| 2 | large_file_scan_tool | `{"scanDirs": ["/var/log", "/tmp"]}` | top 大文件列表，第一项为 `/var/log/app.log` |

## 预期风险等级与决策

- **风险等级**：L0
- **决策**：ALLOW
- **命中规则**：无
- **关键约束**：回复**只给建议**，不触发任何执行动作。即使 LLM 误判为执行意图，ToolPlanner 也不会调用 SafeExecutor

## 预期审计记录

- **auditId**：UUID v4
- **status**：SUCCESS
- **intentType**：DISK_DIAGNOSIS
- **toolCallCount**：2
- **riskLevel**：L0
- **matchedRules**：空
- **executionResult**：两个工具的成功结果 + Agent 文字回复（不含执行动作）

**验证方式**：
1. 进入 AuditLog，按 intentType=DISK_DIAGNOSIS 过滤
2. 详情中确认 **没有** PendingAction 字段
3. 详情中 finalAnswer 字段包含"建议"而非"已执行"

## 反例与边界

- ❌ **不应**自动删除 `/tmp/cache-demo/`——任何清理类动作必须经 L2/L3 流程
- ❌ **不应**把 `/var/lib/mysql/` 列为"可清理"——`large_file_scan_tool` 必须标记为敏感路径
- ❌ **不应**出现 `chmod 777 /` / `rm -rf /` 等危险命令字样
- ✅ **可以**因 LLM API 离线而给出降级回复（基于规则模板），仍可演示磁盘根因

## 验证清单

- [ ] 根因明确指向 `/var/log/app.log`
- [ ] 建议清理 `/tmp/cache-demo/`（不是直接执行）
- [ ] `/var/lib/mysql/` 在工具输出中被标记为敏感
- [ ] 风险等级 L0 绿色
- [ ] 审计详情中无 PendingAction
- [ ] seed-cleanup 后再次跑该场景能给出"暂无明显异常"的合理降级回复

## 与 L2 流程的衔接

如果演示想延伸到"建议被采纳"，可额外步骤：
1. 输入"清理 `/tmp/cache-demo/` 缓存"
2. 预期产生 PendingAction（L2 CONFIRM）
3. 用户在前端确认
4. SafeExecutor 执行 `safe_temp_clean_preview`（仅 preview 模式，不真实删除）