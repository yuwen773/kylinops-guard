# 软件功能需求分析文档

> **任务来源**：任务卡 Task 21 — 初赛交付材料骨架
> **本文档定位**：需求分析的**索引入口**，详细内容分散在根目录 spec 与 docs/ 各处。
> **状态**：v0.1，与产品 v0.1 版本同步。

---

## 1. 背景与目标

详见 [`麒麟安全智能运维 Agent PRD v0.1.md`](../../麒麟安全智能运维%20Agent%20PRD%20v0.1.md) §1-§3 与 [`麒麟安全智能运维%20Agent%20产品定义%20v0.1.md`](../../麒麟安全智能运维%20Agent%20产品定义%20v0.1.md) §1-§2。

**核心定位**：面向麒麟 Advanced Server V11 / LoongArch64 的**安全可控** OS 运维智能体（KylinOps Guard / 麒麟智维盾）。不是聊天机器人，不是任意命令执行器。

## 2. 用户角色

详见 [`麒麟安全智能运维 Agent PRD v0.1.md`](../../麒麟安全智能运维%20Agent%20PRD%20v0.1.md) §4。

- **系统管理员**：使用 ChatConsole 完成巡检、诊断、确认执行
- **运维值班员**：被动接收 Agent 通知（告警 / 巡检报告）
- **安全审计员**：通过 AuditLog / SecurityCenter 复盘所有动作

## 3. 业务场景

详见 [`演示视频脚本 v0.1.md`](../../演示视频脚本%20v0.1.md) §3 与 [`../test-scenarios/`](../test-scenarios/) 全部 5 份场景文档。

4 个 P0 演示场景：
1. 系统健康巡检（多工具 fan-out）
2. 磁盘异常分析（根因 + 安全清理建议）
3. 服务诊断 + L2 确认执行
4. 危险命令 + Prompt Injection 拦截

## 4. 功能需求

详见 [`麒麟安全智能运维 Agent PRD v0.1.md`](../../麒麟安全智能运维%20Agent%20PRD%20v0.1.md) §5 + [`MVP 功能优先级与版本路线 v0.1.md`](../../MVP%20功能优先级与版本路线%20v0.1.md) §3-§4。

P0（必做 / 已交付）：13 项 API + 10 个 L0 工具 + L0–L4 风险分级 + L2 确认 + 审计 + 5 类报告 + 6 个前端页面。

## 5. 非功能需求

详见 [`麒麟安全智能运维 Agent PRD v0.1.md`](../../麒麟安全智能运维%20Agent%20PRD%20v0.1.md) §12。

性能预算（PRD §12.3）：
- 单工具调用 ≤ 3s
- RiskCheck ≤ 1s
- 健康巡检 ≤ 30s
- 普通 chat ≤ 10s
- 报告生成 ≤ 5s

实测数据：见 [`../test/performance-test-plan.md`](../test/performance-test-plan.md) §2.3。

## 6. 安全需求（核心）

**安全需求是本产品的核心竞争力，不是可选项。**

详见：
- 任务卡 §2（9 条硬规则）—— [`../../麒麟安全智能运维%20Agent%20Coding%20Agent%20开发任务卡%20v0.1.md`](../../麒麟安全智能运维%20Agent%20Coding%20Agent%20开发任务卡%20v0.1.md)
- 技术栈方案 §14 —— [`../../麒麟安全智能运维%20Agent%20技术栈选型与架构落地方案%20v0.1.md`](../../麒麟安全智能运维%20Agent%20技术栈选型与架构落地方案%20v0.1.md)
- [`../phase3-audit.md`](../phase3-audit.md) —— 豁免决策与边界

### 6.1 9 条硬规则（任务卡 §2）

1. **No raw shell**：零 `/api/exec`、零 `ProcessBuilder` + 用户输入拼接
2. **所有 OS 工作通过 MCP-style OpsTool**，必须声明 ToolDefinition 全字段
3. **RiskCheck 必须前置**：L0/L1 → ALLOW，L2 → CONFIRM，L3/L4 → BLOCK
4. **L4 绝对阻断清单**：`rm -rf /`、`chmod -R 777 /`、`mkfs`、`dd if=` 等 9+ 命令
5. **Prompt Injection 检测先于意图识别**
6. **默认非 root**：SafeExecutor 在受限账户下，白名单动作
7. **每次请求写 AuditLog**：同一 auditId 串起 userInput → 工具 → 风险 → 执行/拦截
8. **LLM 不决定安全**：LLM 仅作意图识别与措辞
9. **系统状态回答必须有工具调用**

### 6.2 性能 / 可用性 / 合规

- LoongArch 兼容：纯 Java / JS 项目，零 native 依赖
- 国产化合规：JDK 17 龙芯版 / DeepSeek 或 Qwen LLM / Kylin V11 OS
- 数据本地化：H2 File Mode，无外部数据库依赖

---

## 7. 验收对照

| 验收维度 | 文档 |
| --- | --- |
| 需求覆盖 | PRD v0.1 §5 |
| 任务卡完成度 | 任务卡 §22 + [`../phase3-audit.md`](../phase3-audit.md) |
| 安全闭环 | [`../../系统架构设计%20v0.1.md`](../../系统架构设计%20v0.1.md) §22 |
| 测试结果 | [`../test/functional-test-report-draft.md`](../test/functional-test-report-draft.md) |
| 部署验证 | [`../deploy/environment-checklist.md`](../deploy/environment-checklist.md) |

---

**维护责任**：Phase 4 / Task 21；需求变更触发版本升级到 v0.2。