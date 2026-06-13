# PPT 大纲

> **任务来源**：任务卡 Task 21 — 初赛交付材料骨架
> **状态**：v0.1，与产品 v0.1 版本同步。
> **用途**：现场答辩 + 演示开场（25 秒内）。建议 10–12 页。

---

## 1. 标题页（1 页）

```
麒麟安全智能运维 Agent
KylinOps Guard / 麒麟智维盾

面向麒麟 Advanced Server V11 (LoongArch64) 的安全可控智能运维平台

中国软件杯 A2 赛题 · 初赛 · 2026
```

## 2. 一句话定位（1 页）

**我们不是做了一个能执行命令的 AI，而是做了一套面向麒麟操作系统的安全可控智能运维 Agent。**

- 不是聊天机器人
- 不是任意命令执行器
- 是**安全闭环**：自然语言 → 意图 → MCP 工具 → OS 感知 → 风险校验 → 最小权限执行 → 审计 → 报告

## 3. 核心闭环（1 页）

```
自然语言输入
  ↓
Agent 意图识别（IntentClassifier）
  ↓
MCP Tool 规划（ToolPlanningService）
  ↓
已注册 Tool 调用（OpsTool × 10 L0 只读）
  ↓
OS 实时感知（df / ps / ss / systemctl / journalctl）
  ↓
智能分析（AgentResponseBuilder）
  ↓
安全风险校验（RiskCheck：L0–L4）
  ↓
最小权限执行（SafeExecutor，L2 需用户确认）
  ↓
审计日志（AuditLog，同 auditId 串起全程）
  ↓
报告生成（Report，5 类 Markdown）
```

## 4. 9 条硬规则（1 页）

任务卡 §2 + 技术栈方案 §14：

1. **No raw shell**：零 `/api/exec`、零 `ProcessBuilder` + 用户输入拼接
2. **MCP-style Tool 抽象**：必须声明 ToolDefinition 全字段
3. **RiskCheck 必须前置**：L0/L1 → ALLOW，L2 → CONFIRM，L3/L4 → BLOCK
4. **L4 绝对阻断**：rm -rf / / chmod 777 / mkfs / dd if= 等 9+ 命令
5. **Prompt Injection 检测先于意图识别**
6. **默认非 root**：SafeExecutor 在受限账户下，白名单动作
7. **每次请求写 AuditLog**：同一 auditId
8. **LLM 不决定安全**
9. **系统状态回答必须有工具调用**

## 5. 技术栈（1 页）

| 层 | 选型 | 为什么 |
| --- | --- | --- |
| 前端 | Vue 3 + TypeScript + Vite + Element Plus | 跨平台，演示稳定 |
| 后端 | Java 17 + Spring Boot 3 + Maven | LoongArch 兼容 |
| 数据库 | H2 File Mode | 嵌入式，零依赖 |
| LLM | DeepSeek / Qwen（可选） | 国产开源，规则兜底 |
| OS 感知 | Linux 命令白名单 + ProcessBuilder 固定模板 | 受控、审计、跨架构 |

**国产化适配**：CPU LoongArch64 + OS Kylin V11 + JDK 龙芯版 + LLM 国产开源 = **零 x86 假设**

## 6. 系统架构图（1 页）

```
┌──────────────────────────────────────────────┐
│            Frontend (Vue 3 + Element Plus)     │
│  ChatConsole / Dashboard / ToolCenter / ...   │
└──────────────────────────────────────────────┘
                  ↓ HTTP /api/*
┌──────────────────────────────────────────────┐
│              Backend (Spring Boot 3)          │
│                                              │
│  ChatController → AgentOrchestrator           │
│       ↓                                       │
│  IntentClassifier → ToolPlanningService       │
│       ↓                                       │
│  PromptInjectionDetector → RiskCheckService   │
│       ↓                                       │
│  ToolExecutor → 10 × OpsTool (L0)             │
│       ↓                                       │
│  AuditLogService → H2 File Mode               │
│       ↓                                       │
│  SafeExecutor → PendingAction → ActionConfirm │
└──────────────────────────────────────────────┘
                  ↓
┌──────────────────────────────────────────────┐
│           OS (Kylin V11 / LoongArch64)        │
│   df / ps / ss / systemctl / journalctl       │
└──────────────────────────────────────────────┘
```

## 7. 4 个 P0 演示场景（1 页）

| 场景 | 工具 fan-out | 风险 | 验证能力 |
| --- | --- | --- | --- |
| 1. 健康巡检 | 8 工具并行 | L0 ALLOW | OS 感知 + 多工具编排 |
| 2. 磁盘诊断 | disk_usage + large_file_scan | L0 ALLOW | 根因 + 安全建议 |
| 3. 服务诊断 + L2 | service + port + log | L0→L2 CONFIRM | 中风险操作确认 |
| 4. 危险命令 + 注入 | （无工具调用） | L4 BLOCK | 双重防御 + 审计 |

## 8. 测试结果（1 页）

| 类别 | 数量 | 通过率 |
| --- | --- | --- |
| 后端单元 + 集成 | 280 | 100% |
| 前端单元 | 163 | 100% |
| E2E (mock + live) | ≥ 16 | 100% |
| **合计** | **≥ 459** | **100%** |

性能预算：5 项全满足，最小缓冲 80%。

## 9. L4 阻断清单 + 变体测试（1 页）

**绝对阻断**：`rm -rf /`、`rm -rf /*`、`chmod -R 777 /`、`chown -R`、`mkfs`、`fdisk`、`dd if=`、`:(){ :|:& };:` 等

**绕过变体全部命中**（13 条）：
- 大小写：`RM -RF /`
- 多空格：`rm  -rf  /`
- 拆 flag：`rm -r -f /`
- 引号包裹：`"rm -rf /"`
- 具体敏感路径：`rm -rf /etc`、`/usr`、`/bin`、`/boot`

**Prompt Injection 模式**：10+ 模式全部命中；**讨论语境豁免**防止误伤（"什么是..."、"为什么不能..."）

## 10. 部署与可移植性（1 页）

- **P0**：H2 File Mode（嵌入式，跨架构）
- **JDK**：龙芯版 / OpenJDK 17（无 native 依赖）
- **数据库迁移**：Repository 抽象就位，URL 是唯一变更点

```
后端: mvn -B clean package → java -jar
前端: npm ci && npm run build → nginx / dist/
完整流程: bash deploy/scripts/{check-env,seed-demo,start-backend,start-frontend}.sh
```

## 11. 总结（1 页）

**交付完成度**：Phase 1+2+3+4 全部交付
- ✅ 端到端安全闭环（自然语言 → 审计）
- ✅ 10 个 L0 工具 + L0–L4 风险分级 + L2 确认
- ✅ 459+ 测试全绿
- ✅ 6 个前端页面 + 完整文档 + 部署脚本 + 演示脚本

**差异化**：安全护栏（不是 LLM 本身）
- 不允许为了 Demo 绕过安全逻辑
- LLM 离线仍可演示（规则兜底）
- 所有执行有审计、可追溯

**下一步**：复赛 / Kylin V11 LoongArch64 实测回填 / 安全能力向更多场景扩展

## 12. Q&A / 致谢（1 页）

```
Q & A

致谢：
- 麒麟软件 Kylin Advanced Server V11 适配支持
- LoongArch 龙芯 JDK 17 工具链
- DeepSeek / Qwen 开源 LLM

代码仓库：[GitHub URL]
演示视频：[视频链接]
提交邮箱：[contact]
```