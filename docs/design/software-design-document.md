# 软件功能设计文档

> **任务来源**：任务卡 Task 21 — 初赛交付材料骨架
> **本文档定位**：架构与模块设计的**索引入口**，详细内容分散在根目录 spec 与 docs/ 各处。
> **状态**：v0.1，与产品 v0.1 版本同步。

---

## 1. 系统架构

详见 [`系统架构设计 v0.1.md`](../../系统架构设计%20v0.1.md) 全文，特别是：
- §3 总览图（自然语言 → Agent → MCP Tool → OS → RiskCheck → SafeExecutor → AuditLog → 报告）
- §22 包结构（`com.kylinops.*` 13 个模块）
- §6 安全闭环（RiskCheck、PromptInjection、SafeExecutor 的交互）

## 2. 模块设计

### 2.1 后端模块（13 个）

详见 [`系统架构设计 v0.1.md`](../../系统架构设计%20v0.1.md) §22.1 + 任务卡 §3。

| 模块 | 角色 | 关键类 |
| --- | --- | --- |
| `common` | 枚举 / DTO / 异常 | ApiResponse / RiskLevel / RiskDecision |
| `config` | 配置 | SecurityConfig / LlmConfig |
| `chat` | 对话入口 | ChatController / ChatService |
| `agent` | 编排 | AgentOrchestrator / IntentClassifier / ToolPlanningService |
| `tool` | MCP 工具抽象 | OpsTool / ToolRegistry / ToolExecutor |
| `os` | 10 个 L0 工具实现 | CpuStatusTool / DiskUsageTool / ... |
| `security` | 风险引擎 | RiskCheckService / RiskRuleEngine / PromptInjectionDetector |
| `executor` | 白名单执行 | SafeExecutor / PendingAction / ActionConfirmService |
| `audit` | 审计 | AuditLog / AuditLogService |
| `report` | 报告 | ReportService |
| `dashboard` | 仪表盘聚合 | DashboardService |
| `entity` | JPA 实体 | AuditLog / PendingAction / Report |
| `KylinOpsApplication` | 入口 | — |

### 2.2 前端模块（6 页面 + 6 通用组件 + 8 API 客户端）

详见 [`../test/phase2-acceptance-guide.md`](../test/phase2-acceptance-guide.md) §2-§4。

- 页面：ChatConsole / Dashboard / ToolCenter / SecurityCenter / AuditLog / ReportCenter
- 通用组件：RiskLevelTag / ToolCallCard / AuditTimeline / ExecutionConfirmCard / ReportPreview / StatusMetricCard
- API 客户端：chat / tools / audit / reports / security / actions / dashboard / client

## 3. MCP Tool 设计

详见 [`../../系统架构设计%20v0.1.md`](../../系统架构设计%20v0.1.md) §10 + 任务卡 Task 04 / 05 / 06。

10 个 L0 工具一览（详见 [`../phase3-audit.md`](../phase3-audit.md) §Task 06）：

| toolName | 用途 | 演示场景 |
| --- | --- | --- |
| system_info_tool | 主机信息 | 健康巡检 |
| cpu_status_tool | CPU | 健康巡检 |
| memory_status_tool | 内存 | 健康巡检 |
| disk_usage_tool | 磁盘使用 | 演示 2 |
| large_file_scan_tool | 大文件扫描 | 演示 2 |
| process_list_tool | 进程列表 | 健康巡检 |
| process_detail_tool | 单进程钻取 | 健康巡检 |
| network_port_tool | 端口监听 | 演示 3 |
| service_status_tool | systemd 服务状态 | 演示 3 |
| journal_log_tool | 系统日志 | 演示 3 / 健康巡检 |

每个工具必须声明 `ToolDefinition` 全字段：`toolName / description / inputSchema / outputSchema / riskLevel / permissionType / enabled / timeoutMs / auditRequired`。

## 4. 安全护栏设计

详见 [`../../系统架构设计%20v0.1.md`](../../系统架构设计%20v0.1.md) §6-§8 + [`../test/security-test-cases.md`](../test/security-test-cases.md)。

### 4.1 RiskRuleEngine

- 配置化 YAML 规则：`backend/src/main/resources/rules/security-rules.yml`
- L0–L4 5 级风险 + ALLOW/CONFIRM/BLOCK 3 种决策
- L4 绝对阻断清单 + 13 条绕过变体（大小写/空格/拆 flag/引号）全部命中

### 4.2 PromptInjectionDetector

- 10+ 模式匹配（中文："忽略规则" / "不要审计" / "直接执行" 等）
- 讨论语境豁免（"什么是..."、"为什么不能..." 不误拦）
- 注入 + 危险命令组合 → L4 BLOCK（双重防御）

### 4.3 SafeExecutor

- 白名单动作：`safe_service_restart`、`safe_temp_clean_preview`、`safe_log_truncate_preview`、`safe_file_clean_preview`
- L2 生成 PendingAction → 等待 `/api/actions/confirm` → 用户确认后才执行
- 默认非 root

## 5. 审计日志设计

详见 [`../../系统架构设计%20v0.1.md`](../../系统架构设计%20v0.1.md) §11 + [`../test/security-test-cases.md`](../test/security-test-cases.md) §3。

### 5.1 字段

`auditId / sessionId / userInput / intentType / toolCalls / riskLevel / matchedRules / decision / actionPlan / confirmationRequired / confirmationStatus / executionResult / finalAnswer / status / createdAt`

### 5.2 生命周期

每次请求：
1. 创建 AuditLog（status = PENDING）
2. 注入检测 → 风险校验 → 更新 matchedRules
3. 工具调用 → 关联 toolCalls
4. 执行/拦截 → 更新 status + executionResult
5. 返回前端 + 持久化

### 5.3 查询接口

- `GET /api/audit/logs` —— 筛选（riskLevel / status / keyword / 时间）
- `GET /api/audit/logs/{auditId}` —— 完整详情

### 5.4 脱敏

`AuditSanitizer` —— 不记录 LLM 思维链、不记录敏感文件全量内容。

## 6. 数据库设计

P0：H2 File Mode。表：
- `audit_log` —— 审计主表
- `tool_call_record` —— 工具调用明细
- `risk_check_record` —— 风险校验记录
- `pending_action` —— 待确认动作
- `report` —— 报告
- `session` / `message` —— 会话与消息

P1：URL 唯一变更点即可切换到 PostgreSQL / MySQL。

## 7. 性能预算设计

详见 [`../test/performance-test-plan.md`](../test/performance-test-plan.md)。

---

**维护责任**：Phase 4 / Task 21；架构变更触发版本升级到 v0.2。