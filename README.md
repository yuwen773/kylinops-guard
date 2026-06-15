# 麒麟安全智能运维 Agent (KylinOps Guard / 麒麟智维盾)

> **面向麒麟 Advanced Server V11 (LoongArch64) 的安全可控智能运维 Agent**

## 项目简介

一款**安全可控**的操作系统智能运维 Agent。它不是普通聊天机器人，也不是任意命令执行器，而是一个面向麒麟操作系统的、**以安全护栏为核心竞争力**的运维智能体。

### 安全闭环

```
自然语言输入 → Agent 意图识别 → MCP-style Tool 规划
    → 已注册 Tool 调用 → OS 实时感知 → 安全风险校验 (RiskCheck)
    → 最小权限执行 (SafeExecutor) → 审计日志 (AuditLog) → 报告生成
```

### 核心原则

- 所有 OS 操作必须通过注册的 OpsTool 封装
- 所有 Tool 必须声明 riskLevel 和 permissionType
- L2（中等风险）操作必须用户确认后才执行
- L3/L4（高风险）操作直接阻断
- Prompt 注入检测优先于任何意图识别
- 不允许为了 Demo 绕过安全逻辑

## 技术栈

| 层 | 技术选型 |
|---|---|
| 前端 | Vue 3 + TypeScript + Vite + Element Plus + Axios + Vue Router |
| 后端 | Java 17 + Spring Boot 3.x + Maven |
| 数据库 | H2 File Mode (P0) / PostgreSQL (P1) |
| Agent | 自研轻量 AgentOrchestrator |
| MCP | 内部 MCP-style Tool Registry |
| OS 感知 | 受控 Linux 命令白名单封装 |
| 安全 | RiskRuleEngine + PromptInjectionDetector |
| 审计 | AuditLogService |
| 报告 | 确定性 Markdown 从 AuditLogDetail 组装（不调 LLM） |

## 项目结构

```
kylin-ops/
├── backend/                    # Spring Boot 后端
│   └── src/main/java/com/kylinops/
│       ├── common/             # ApiResponse, GlobalExceptionHandler, enums
│       ├── config/             # LLM, security
│       ├── chat/               # ChatController + ChatService
│       ├── agent/              # AgentOrchestrator, IntentClassifier, ToolPlanningService, AgentResponseBuilder
│       ├── tool/               # OpsTool, ToolRegistry, ToolExecutor, ToolDefinitionVO (+ call stats)
│       ├── os/                 # 10 只读 L0 工具 (system_info/cpu/memory/disk/large_file_scan/process_list/process_detail/network_port/service_status/journal_log)
│       ├── security/           # PromptInjectionDetector, RiskRuleEngine, RiskCheckService, SecurityCatalogController (GET-only)
│       ├── executor/           # SafeExecutor, PendingAction, ActionConfirmService
│       ├── audit/              # AuditLog, AuditLogService (+ toolCallCount aggregate)
│       ├── report/             # Report entity, ReportService (deterministic Markdown from AuditLogDetail)
│       └── dashboard/          # DashboardService (parallel ToolExecutor collection, shared auditId)
├── frontend/                   # Vue 3 + TS + Vite + Element Plus (Phase 2)
│   └── src/
│       ├── api/                # client + chat/actions/audit/security/dashboard/tools/reports
│       ├── components/         # RiskLevelTag, ToolCallCard, ExecutionConfirmCard, AuditTimeline, StatusMetricCard, ReportPreview
│       ├── layouts/AppLayout.vue
│       ├── pages/              # ChatConsole, Dashboard, ToolCenter, SecurityCenter, AuditLog, ReportCenter
│       ├── router/, types/, styles/
│   └── tests/e2e/              # Playwright navigation + demo-flows + demo-live
├── deploy/
│   └── scripts/                # check-env.sh, start-backend.sh, start-frontend.sh
├── docs/
│   └── test/                   # phase2-acceptance-guide.md, phase2-demo-acceptance.md
├── *.md                        # 产品规格/架构设计文档 (v0.1)
└── README.md
```

## 快速开始

### 环境要求

- Java 17+
- Maven 3.8+
- Node.js 18+ (前端开发)

### 编译与运行

```bash
# 1. 环境检查
bash deploy/scripts/check-env.sh

# 2. 编译后端
cd backend && mvn clean package -DskipTests

# 3. 启动后端
java -jar backend/target/kylin-ops-guard.jar

# 4. 启动前端 (Phase 2)
cd frontend && npm install && npm run dev   # http://127.0.0.1:5173

# 5. 验证
curl http://localhost:8080/api/health
```

### 验证结果示例

```json
{
  "code": 200,
  "message": "服务运行正常",
  "data": {
    "status": "UP",
    "service": "kylin-ops-guard",
    "version": "0.1.0",
    "timestamp": "2026-06-10T12:00:00Z",
    "jvm": { "...": "..." }
  },
  "timestamp": 1750000000000
}
```

## 开发顺序

根据规格文档，按以下顺序开发（Phase 1 → 4）：

### Phase 1 — 后端安全闭环 (Task 00→11) — 已完成
后端核心安全闭环已通过全量测试、打包和 Git 检查验收。包括 Spring Boot 骨架、JPA 实体、OS 工具、安全风险引擎、Agent 编排层、确认执行、审计 API 和 Chat API。`POST /api/chat/send` 已可用。

### Phase 2 — 前端演示闭环 (Task 02→17) — 已完成
七页面前端 (Login, ChatConsole, Dashboard, ToolCenter, SecurityCenter, AuditLog, ReportCenter) 全部落地，含管理员登录与会话安全。单元/集成测试 + Playwright E2E 覆盖四演示场景与认证流程。

### Phase 3 — 执行器与报告 (Task 06→12) — 已实现
SafeExecutor、PendingAction 确认流程、报告生成（其中 `safe_*_preview` 已实现，真实删除仍 deferred）。

### Phase 4 — 交付材料 (Task 18→21) — 已完成 (2026-06-13)

- **Task 18**：5 个演示场景 markdown（[`test-scenarios/`](test-scenarios/)）+ `deploy/scripts/seed-demo.sh` + `seed-demo-cleanup.sh` + `docs/demo/demo-script-v0.1.md`
- **Task 19**：3 份测试文档（[`security-test-cases.md`](docs/test/security-test-cases.md)、[`functional-test-report.md`](docs/test/functional-test-report.md)、[`performance-test-plan.md`](docs/test/performance-test-plan.md)）
- **Task 20**：[`docs/deploy/kylin-loongarch-deploy-guide.md`](docs/deploy/kylin-loongarch-deploy-guide.md) + [`environment-checklist.md`](docs/deploy/environment-checklist.md)
- **Task 21**：8 份标准化提交通道（[`docs/product/`](docs/product/)、[`docs/design/`](docs/design/)、[`docs/test/functional-test-report.md`](docs/test/functional-test-report.md)、[`docs/test/performance-test-report.md`](docs/test/performance-test-report.md)、[`docs/deploy/install-and-deploy-guide.md`](docs/deploy/install-and-deploy-guide.md)、[`docs/demo/demo-video-script.md`](docs/demo/demo-video-script.md)、[`docs/demo/ppt-outline.md`](docs/demo/ppt-outline.md)）

### Production Hardening — 生产加固 + LLM 增强 (P1-T1..P4-T2) — 已完成 (2026-06-15)

- **P1 运行时加固**：Flyway 数据库迁移、PostgreSQL+H2 双模式、dev/test/prod 配置拆分、OsCommandExecutor 硬超时与并发有界、动作白名单、HTTP 健康三端点
- **P2 认证安全**：Spring Security 边界、管理员登录与会话、CSRF、限流、PendingAction 会话绑定、执行前审计失败闭锁、前端登录页+路由守卫+E2E
- **P3 LLM 增强**：OpenAI 兼容客户端、混合意图分类（规则+LLM）、工具上下文策略、接地回复验证、LLM 调用审计 V3、DeepSeek/Qwen 双模型降级
- **P4 部署工件**：systemd unit、Nginx TLS 站点、ci.yml 打包、备份/恢复/迁移脚本、验收烟雾测试
- **P4-T3/T4/T5**（目标矩阵、并发烟雾、最终发布）→ **BLOCKED_EXTERNAL**（需真实 LoongArch 主机）

- **Task 18**：5 个演示场景 markdown（[`test-scenarios/`](test-scenarios/)）+ `deploy/scripts/seed-demo.sh` + `seed-demo-cleanup.sh` + `docs/demo/demo-script-v0.1.md`
- **Task 19**：3 份测试文档（[`security-test-cases.md`](docs/test/security-test-cases.md)、[`functional-test-report.md`](docs/test/functional-test-report.md)、[`performance-test-plan.md`](docs/test/performance-test-plan.md)）
- **Task 20**：[`docs/deploy/kylin-loongarch-deploy-guide.md`](docs/deploy/kylin-loongarch-deploy-guide.md) + [`environment-checklist.md`](docs/deploy/environment-checklist.md)
- **Task 21**：8 份标准化提交通道（[`docs/product/`](docs/product/)、[`docs/design/`](docs/design/)、[`docs/test/functional-test-report.md`](docs/test/functional-test-report.md)、[`docs/test/performance-test-report.md`](docs/test/performance-test-report.md)、[`docs/deploy/install-and-deploy-guide.md`](docs/deploy/install-and-deploy-guide.md)、[`docs/demo/demo-video-script.md`](docs/demo/demo-video-script.md)、[`docs/demo/ppt-outline.md`](docs/demo/ppt-outline.md)）

- **P1-T1..P4-T2 生产加固**：部署包详见 [`deploy/README.md`](deploy/README.md)

**Phase 3 缺口核对**（[`docs/phase3-audit.md`](docs/phase3-audit.md)）：Task 06 三个扩展工具（service_log_tool / zombie_process_scan_tool / port_conflict_check_tool）正式豁免，不影响 4 个 P0 演示场景。

## 四演示场景

四个 P0 场景的输入/预期/验收点（系统健康检查、磁盘诊断、服务诊断+L2、危险命令拦截）见 [`CLAUDE.md` §Four Demo Scenarios](CLAUDE.md#four-demo-scenarios-the-architecture-must-support-these)。验收记录与 Windows / LoongArch 区分见 [`docs/test/phase2-demo-acceptance.md`](docs/test/phase2-demo-acceptance.md)。

## 七页面前端

| 页面 | 路径 | 职责 |
|---|---|---|
| Login | `/login` | 管理员登录（CSRF + Session），无 session 时所有页面重定向至此 |
| ChatConsole | `/chat` | 自然语言入口 + quick-action 按钮 + 风险标签 + L2 确认 |
| Dashboard | `/dashboard` | 工具调用统计 + 风险等级分布 + 服务状态总览（经 ToolExecutor 采集） |
| ToolCenter | `/tools` | 已注册 OpsTool 元数据 + 调用次数与成功率 |
| SecurityCenter | `/security` | 风险规则目录 + 拦截事件流（GET-only） |
| AuditLog | `/audit` | 审计筛选 + 详情回放（关联 `auditId`） |
| ReportCenter | `/reports` | 报告列表 + `sourceAuditId` 反查回审计 |

## 安全红线

- ❌ 无 `/api/shell`、`/api/exec`、`/api/command/run` 端点
- ❌ 不将用户输入直接拼接为 Shell 命令
- ❌ 不以 `/bin/sh -c` 执行用户原始输入
- ❌ 前端不降低后端返回的风险等级，不自动确认 L2
- ✅ 所有 OS 操作通过 OpsTool 封装
- ✅ 所有 Tool 注册到 ToolRegistry
- ✅ 所有执行动作经过 RiskCheckService
- ✅ L2 操作必须确认，L3/L4 必须阻断
- ✅ 全量审计日志
- ✅ Markdown 渲染 `html:false`，禁止 raw HTML

## 交付索引（初赛提交清单）

| 类别 | 文档 | 状态 |
| --- | --- | --- |
| 需求分析 | [`docs/product/software-requirements-analysis.md`](docs/product/software-requirements-analysis.md) | ✅ |
| 架构设计 | [`docs/design/software-design-document.md`](docs/design/software-design-document.md) | ✅ |
| 产品手册 | [`docs/product/product-manual.md`](docs/product/product-manual.md) | ✅ |
| 功能测试报告 | [`docs/test/functional-test-report.md`](docs/test/functional-test-report.md) | ✅ |
| 性能测试报告 | [`docs/test/performance-test-report.md`](docs/test/performance-test-report.md) | ✅ |
| 部署与安装 | [`docs/deploy/install-and-deploy-guide.md`](docs/deploy/install-and-deploy-guide.md) | ✅ |
| 演示视频脚本 | [`docs/demo/demo-video-script.md`](docs/demo/demo-video-script.md) | ✅ |
| PPT 大纲 | [`docs/demo/ppt-outline.md`](docs/demo/ppt-outline.md) | ✅ |
| 5 个演示场景 | [`test-scenarios/`](test-scenarios/) | ✅ |
| Kylin/LoongArch 部署 | [`docs/deploy/kylin-loongarch-deploy-guide.md`](docs/deploy/kylin-loongarch-deploy-guide.md) | ✅ |
| 环境验证清单 | [`docs/deploy/environment-checklist.md`](docs/deploy/environment-checklist.md) | ✅ |
| Phase 3 豁免决策 | [`docs/phase3-audit.md`](docs/phase3-audit.md) | ✅ |

**测试基线**：后端 280/280 + 前端 163/163 + E2E ≥ 16 = **≥ 459 测试全绿**。

## License

本项目为竞赛参赛项目。
