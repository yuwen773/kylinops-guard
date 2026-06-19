# Architecture Reference

Authoritative layout for the KylinOps Guard backend and frontend. Sources: `系统架构设计 v0.1.md`, `麒麟安全智能运维 Agent Coding Agent 开发任务卡 v0.1.md`.

## Table of Contents
- [7-Layer Architecture](#7-layer-architecture)
- [Backend Package Layout](#backend-package-layout)
- [Frontend Layout](#frontend-layout)
- [P0 API Surface](#p0-api-surface)
- [Core Data Model](#core-data-model)
- [Canonical Enums](#canonical-enums)
- [Database](#database)
- [Configuration](#configuration)

## 7-Layer Architecture

Cross-cutting concerns (`security`, `audit`) must be reachable from L3 (Agent) and L6 (Executor). Do not collapse layers.

| L | Layer | Owns |
|---|---|---|
| L1 | Web UI | The six pages, components, API client |
| L2 | API | REST controllers, unified response envelope, `GlobalExceptionHandler` |
| L3 | Agent orchestration | `AgentOrchestrator`, `IntentClassifier`, `ToolPlanningService`, `AgentResponseBuilder` |
| L4 | MCP Tool | `OpsTool` interface, `ToolRegistry`, `ToolExecutor`, `ToolCallRecord` |
| L5 | Safety guardrail | `RiskRuleEngine`, `PromptInjectionDetector`, `CommandRiskChecker`, `PathRiskChecker`, `PermissionPolicy` |
| L6 | Min-priv executor | `SafeExecutor`, `PendingAction`, `ExecutionPolicy`, `ActionConfirmService` |
| L7 | Data & audit | Repositories, `AuditLogService`, report storage |

Required call shape: L3 must invoke L5 *before* any L6 call, and L7 must be written by L3/L4/L5/L6 with the same `auditId`.

## Backend Package Layout

```
com.kylinops
├── KylinOpsApplication.java
├── common      # ApiResponse, GlobalExceptionHandler, enums (RiskLevel, RiskDecision, PermissionType, AuditStatus, ToolCallStatus, IntentType)
├── config      # LLM config, security config, system config
├── chat        # ChatController, ChatService, Session, Message
├── agent       # AgentOrchestrator, IntentClassifier, ToolPlanningService, AgentResponseBuilder
├── tool        # OpsTool interface, ToolDefinition, ToolInput, ToolResult, ToolRegistry, ToolExecutor
├── os          # SystemInfoTool, CpuStatusTool, MemoryStatusTool, DiskUsageTool, ... (OpsTool impls)
├── security    # RiskCheckService, RiskRuleEngine, PromptInjectionDetector, SecurityRule
├── executor    # SafeExecutor, PendingAction, ExecutionResult, ActionConfirmService
├── audit       # AuditLog, AuditLogService, AuditLogController
├── report      # Report, ReportService, ReportController
└── dashboard   # DashboardController, DashboardService
```

Resources: `application.yml`, `db/` (schema/seed), `rules/` (default risk-rule YAML/JSON).

## Frontend Layout

Six pages, mandated by spec:

```
frontend/src/
├── api/         { chat.ts, dashboard.ts, tools.ts, security.ts, audit.ts, reports.ts }
├── pages/
│   ├── ChatConsole/       # 智能运维对话台 — main demo entry
│   ├── Dashboard/         # 系统状态总览
│   ├── ToolCenter/        # MCP 工具中心
│   ├── SecurityCenter/    # 安全护栏中心
│   ├── AuditLog/          # 审计日志中心
│   └── ReportCenter/      # 报告中心
├── components/
│   ├── ToolCallCard/
│   ├── RiskLevelTag/
│   ├── ExecutionConfirmCard/
│   ├── AuditTimeline/
│   ├── ReportPreview/
│   └── StatusMetricCard/
├── router/ store/ utils/
```

Top-bar product name string: `麒麟安全智能运维 Agent`. Left nav lists the six pages in the order above. Style is enterprise ops console — avoid 大屏 effects (技术栈方案 §3.5).

## P0 API Surface

| Method | Path | Notes |
|---|---|---|
| GET | `/api/health` | Liveness: `{ status: "UP", service: "kylin-ops-guard-backend" }` |
| POST | `/api/chat/send` | Main entry. Body `{ sessionId, message }`. Returns `{ sessionId, answer, intentType, toolCalls, riskLevel, decision, needConfirmation, auditId }` |
| GET | `/api/tools` | List all registered tools (drives Tool Center page) |
| GET | `/api/tools/{toolName}` | Single tool definition |
| POST | `/api/security/risk-check` | Standalone check. Body `{ targetType, content }`. Returns `{ riskLevel, decision, matchedRules, reason, safeSuggestion }` |
| POST | `/api/actions/confirm` | Confirm/cancel a `PendingAction`. Body `{ actionId, confirm }` |
| GET | `/api/audit/logs` | Filter: `riskLevel`, `status`, `keyword`, `startTime`, `endTime` |
| GET | `/api/audit/logs/{auditId}` | Full audit detail |
| POST | `/api/reports/generate` | Body `{ sessionId | auditId, reportType }` |
| GET | `/api/reports` | List reports |
| GET | `/api/reports/{reportId}` | Single report (Markdown body) |
| GET | `/api/dashboard/overview` | Aggregated host metrics + health score |
| GET | `/api/security/rules` | Rule catalogue for Security Center |
| GET | `/api/security/events` | Recent BLOCK events |

**Forbidden endpoints (must not exist):** `/api/exec`, `/api/shell`, `/api/command/run`. Their presence is a release blocker.

## Core Data Model

Tables (Task 03):

| Table | Holds |
|---|---|
| `sessions` | Chat sessions |
| `messages` | Per-session messages (role: user/assistant/system; messageType: text/tool_call/risk_warning/execution_result) |
| `tool_definitions` | Registered `OpsTool` metadata (display in Tool Center) |
| `tool_call_records` | One row per `OpsTool.execute(...)` invocation |
| `risk_check_records` | One row per RiskCheck (input/plan/command) |
| `pending_actions` | L2 actions awaiting `/api/actions/confirm` |
| `audit_logs` | One row per user request — primary audit object |
| `reports` | Generated reports (Markdown body + metadata) |
| `security_rules` | Risk-rule catalogue (seeded from `rules/`) |

`AuditLog` fields (architecture §11.3): `auditId`, `sessionId`, `userInput`, `intentType`, `toolCalls`, `toolResultsSummary`, `riskLevel`, `matchedRules`, `decision`, `actionPlan`, `confirmationRequired`, `confirmationStatus`, `executionResult`, `finalAnswer`, `status`, `createdAt`.

`ToolDefinition` fields: `toolName`, `displayName`, `description`, `category`, `inputSchema`, `outputSchema`, `riskLevel`, `permissionType`, `enabled`, `timeoutMs`, `auditRequired`.

`ToolResult` fields: `toolName`, `status`, `data`, `summary`, `errorMessage`, `startedAt`, `finishedAt`, `durationMs`.

## Canonical Enums

Define **once** in `com.kylinops.common.enums`. Don't redefine "risk level" anywhere else.

```
RiskLevel       : L0, L1, L2, L3, L4
RiskDecision    : ALLOW, CONFIRM, BLOCK
PermissionType  : READ_ONLY, LIMITED_EXEC, CONFIRM_EXEC, ADMIN_APPROVAL, FORBIDDEN
AuditStatus     : SUCCESS, FAILED, BLOCKED, WAIT_CONFIRM, CANCELED
ToolCallStatus  : SUCCESS, FAILED, TIMEOUT
ToolStatus      : ENABLED, DISABLED
IntentType      : HEALTH_CHECK, DISK_DIAGNOSIS, PROCESS_DIAGNOSIS,
                  SERVICE_DIAGNOSIS, LOG_ANALYSIS, SECURITY_RISK_TEST,
                  EXECUTION_REQUEST, UNKNOWN
ReportType      : HEALTH_CHECK_REPORT, DISK_DIAGNOSIS_REPORT,
                  SERVICE_DIAGNOSIS_REPORT, SECURITY_BLOCK_REPORT, AUDIT_REPORT
```

Default mapping `RiskLevel → RiskDecision`: L0/L1 → ALLOW, L2 → CONFIRM, L3/L4 → BLOCK.

## Database

P0: H2 File Mode. Required `application.yml`:

```yaml
spring:
  datasource:
    url: jdbc:h2:file:./data/kylinops
    username: sa
    password: ""
```

Keep all data access behind `*Repository` interfaces. The P1 switch to PostgreSQL/MySQL should only change `spring.datasource.url`. Do not use H2-specific SQL features.

## Configuration

```yaml
llm:
  enabled: true              # MUST be runtime-toggleable; demos must survive enabled=false
  provider: openai-compatible
  base-url: ${LLM_BASE_URL}
  api-key: ${LLM_API_KEY}
  model: ${LLM_MODEL}
  timeout-seconds: 30
```

When `llm.enabled=false`: intent classification falls back to keyword rules, reply text falls back to templates, safety logic is unaffected (it never depended on the LLM).
