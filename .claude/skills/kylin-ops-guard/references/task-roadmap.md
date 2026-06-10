# Task Roadmap Reference

Spec-mandated task sequencing for the v0.1 → v1.0 path. Use this when deciding what to build next or classifying a feature request as P0 vs P1 vs cut. Source: `麒麟安全智能运维 Agent Coding Agent 开发任务卡 v0.1.md` §4 / §6, `MVP 功能优先级与版本路线 v0.1.md`.

## Table of Contents
- [The 22 Task Cards](#the-22-task-cards)
- [Four Development Phases](#four-development-phases)
- [Version Roadmap (v0.1 → v1.0)](#version-roadmap-v01--v10)
- [Priority Classification (P0 / P1 / P2 / P-1)](#priority-classification-p0--p1--p2--p-1)
- [What to Cut Under Time Pressure](#what-to-cut-under-time-pressure)
- [Common Anti-Patterns to Reject](#common-anti-patterns-to-reject)

## The 22 Task Cards

Execute in this order. Earlier tasks set up registries/abstractions later ones depend on — do not reorder.

| # | Task | Output | Phase |
|---|---|---|---|
| 00 | Project baseline & dev rules | README + `docs/development-guidelines.md` + `docs/security-principles.md` + `docs/coding-agent-rules.md` + `.gitignore` + skeleton dirs | 1 |
| 01 | Backend skeleton | Spring Boot project, `/api/health`, unified response, global exception handler | 1 |
| 02 | Frontend skeleton | Vite + Vue3 + Element Plus, six empty pages, API client | 2 |
| 03 | Core data model & DB | All enums in `common`, JPA/MyBatis entities for sessions/messages/tool_definitions/tool_call_records/risk_check_records/audit_logs/pending_actions/reports/security_rules | 1 |
| 04 | MCP Tool abstraction & registry | `OpsTool` interface, `ToolDefinition`/`ToolInput`/`ToolResult`, `ToolRegistry`, `ToolExecutor` (timeout-bounded), `/api/tools` + `/api/tools/{name}`, ≥ 1 MockTool passing tests | 1 |
| 05 | OS read-only tools | ≥ 8 L0 `OpsTool` impls: `system_info`, `cpu_status`, `memory_status`, `disk_usage`, `large_file_scan`, `process_list`, `process_detail`, `network_port_tool` | 1 |
| 06 | Service/network/log diagnosis tools | `service_status_tool`, `journal_log_tool`, `service_log_tool`, `zombie_process_scan_tool`, `port_conflict_check_tool` | 3 |
| 07 | Risk-rule engine | `RiskCheckService`, `RiskRuleEngine`, `RiskRule`, default `rules/*` resource, `/api/security/risk-check`, all entries in [safety-rules acceptance table](safety-rules.md#acceptance-test-inputs) pass | 1 |
| 08 | PromptInjectionDetector | Plug into `RiskCheckService`, all pattern matches from [safety-rules](safety-rules.md#prompt-injection-patterns) catch, combine with dangerous commands → L4 | 3 |
| 09 | SafeExecutor | `SafeExecutor`, `PendingAction`, `ExecutionPolicy`, `ActionConfirmService`, `/api/actions/confirm`; whitelisted actions from [tools-catalog](tools-catalog.md#safeexecutor-action-whitelist-task-09) | 3 |
| 10 | Agent orchestration | `ChatController`, `ChatService`, `AgentOrchestrator`, `IntentClassifier`, `ToolPlanningService`, `AgentResponseBuilder`, `/api/chat/send` returning `{ answer, intentType, toolCalls, riskLevel, decision, needConfirmation, auditId }` | 1 |
| 11 | Audit log closed loop | `AuditLog`, `AuditLogService`, `/api/audit/logs` + `/api/audit/logs/{auditId}`, every request writes; filter by `riskLevel`/`status`/`keyword`/time range | 1 |
| 12 | Report generation | `Report`, `ReportService`, `/api/reports/generate` + list + detail, ≥ 4 report types (health, disk, service-diagnosis, security-block, audit), Markdown body sourced from `AuditLog` + tool results (no fabrication) | 3 |
| 13 | ChatConsole page | Input box, Agent reply, tool-call cards, risk-level tag, ExecutionConfirmCard for L2, BLOCK reason for L4, auditId link, "generate report" button, five [quick-action buttons](demo-and-tests.md#chatconsole-quick-action-buttons) | 2 |
| 14 | Dashboard page | `/api/dashboard/overview` + UI: hostname/OS/arch/cpu/mem/disk/network/services/abnormal services/recent errors/health score | 2 |
| 15 | Tool Center page | Table view of `/api/tools` with risk + permission tags, status, call count, success rate, expandable detail | 2 |
| 16 | Security Center page | `/api/security/rules`, `/api/security/events`, `/api/security/risk-levels`; show L0-L4 explainer, danger command list, sensitive paths, prompt-injection rules, recent BLOCK events with detail | 2 |
| 17 | AuditLog + ReportCenter pages | Audit list with filters, detail with full chain replay; report list, Markdown render, source-audit link | 2 |
| 18 | Demo scenarios & seed data | `test-scenarios/*.md` for the four demos + prompt-injection variant; `deploy/scripts/seed-demo.sh`; `docs/demo/demo-script-v0.1.md` | 4 |
| 19 | Automated + safety tests | JUnit coverage of RiskRuleEngine, PromptInjectionDetector, ToolRegistry, Agent flow; required test corpus from [acceptance test matrix](demo-and-tests.md#acceptance-test-matrix); `docs/test/security-test-cases.md` | 4 |
| 20 | Kylin / LoongArch deployment | `docs/deploy/kylin-loongarch-deploy-guide.md`, `docs/deploy/environment-checklist.md`, `deploy/scripts/check-env.sh`/`start-backend.sh`/`start-frontend.sh`/`stop-*.sh`; honestly distinguish 已验证 vs 待验证 | 4 |
| 21 | Competition deliverables skeleton | `docs/product/software-requirements-analysis.md`, `docs/design/software-design-document.md`, `docs/product/product-manual.md`, `docs/test/functional-test-report.md` + `performance-test-report.md`, `docs/deploy/install-and-deploy-guide.md`, `docs/demo/demo-video-script.md`, `docs/demo/ppt-outline.md` | 4 |

## Four Development Phases

| Phase | Tasks | Why this grouping |
|---|---|---|
| **1 — Backend safety closed-loop** | 00, 01, 03, 04, 05, 07, 10, 11 | The irreducible minimum. After Phase 1 the system can take a request, dispatch tools, run RiskCheck, and write audit — without any UI. If you only ship Phase 1, the project is still graded as having the safety guarantee. |
| **2 — Frontend demo loop** | 02, 13, 14, 15, 16, 17 | What evaluators see. Phase 2 turns the backend into a recordable demo. |
| **3 — Exec + reports** | 06, 08, 09, 12 | The "this is a real product" tier — service diagnosis, prompt injection, L2 confirm flow, reports. Required for full demo but not the safety floor. |
| **4 — Delivery materials** | 18, 19, 20, 21 | Acceptance tests, deploy doc, competition submission package. Must exist for v1.0 submission. |

## Version Roadmap (v0.1 → v1.0)

| Version | Goal | Done when |
|---|---|---|
| v0.1 | Product + arch defined (current state) | All eight spec md files at repo root exist |
| v0.2 | Backend closed-loop (Phase 1) | `POST /api/chat/send` works end-to-end for the four demo inputs, returns `auditId`, ≥ 8 tools registered, all `rm -rf /` variants BLOCK |
| v0.3 | Frontend demo loop (Phase 2) | All six pages render, ChatConsole runs all four scenarios from quick-action buttons |
| v0.4 | Safe exec + reports (Phase 3) | L2 CONFIRM works, prompt-injection catches all listed patterns, ≥ 4 report types generate |
| v0.5 | Demo & test stable (Phase 4 partial) | Seed-data script works, JUnit `mvn test` passes, deploy doc honest |
| v1.0 | Competition submission | All deliverables in `docs/`, demo video ≤ 7 min recorded, install package + source zip ready |

## Priority Classification (P0 / P1 / P2 / P-1)

Apply the four-question test before deciding (MVP 路线 §1.2):
1. Does it hit the competition rubric directly?
2. Can it be clearly shown in the demo video?
3. Does it prove the project is more than a chatbot?
4. Does it sharpen the "safe controllable ops Agent" differentiation?

If a flashy feature can't answer those four, it is not P0.

### P0 — must ship for v1.0
- ChatConsole, Agent orchestration, ToolRegistry, ≥ 8 OS tools, RiskRuleEngine, dangerous-command interception, L2 confirm, AuditLog, report generation, Kylin/LoongArch deploy doc, disk-diagnosis, service-diagnosis (12 items, MVP 路线 §3.1)

### P1 — strong add-ons after P0 stable
- PromptInjectionDetector enhancement, Security Center visualization, root-cause confidence scoring, tool-call timeline, Markdown/PDF report export, demo seed-data preset, performance metrics, security-rule view (8 items, MVP 路线 §4)

### P2 — future-work only, do not implement
- Multi-node ops, enterprise approval flow, alerting integration, Feishu/WeChat notifications, plugin marketplace, RAG ops knowledge base, model fine-tuning, full Kubernetes mgmt, multi-tenant RBAC, topology visualization (MVP 路线 §5)
- Mention these only in the PPT "Future Roadmap" slide.

### P-1 — actively forbidden, reject if a Coding Agent proposes
- Arbitrary shell input box
- Agent defaulting to root
- Auto-delete system files
- Auto-modify `/etc`
- Self-trained LLM
- Large monitoring platform
- Full Kubernetes lifecycle
- Full CMDB
- Catch-all ops platform
- Complex RBAC
- 大屏 effects
- Heavy fake-data screens (undermines the "real OS sensing" claim)

Red-line endpoints that, if they appear in code, must be removed immediately:
```
/api/shell
/api/exec
/api/command/run
any path that runs user-provided shell
any ProcessBuilder fed user content
any delete/restart without RiskCheck
any execution without AuditLog
any high-risk command that only warns instead of blocking
```

## What to Cut Under Time Pressure

In order of acceptable to least-acceptable to drop:

1. **First cut: all of P2.** (Already not in scope.)
2. **Then cut from P1:** PDF report export, complex perf metrics, rule-edit UI, tool-call charts, root-cause confidence numbers, extra service types.
3. **Floor — cannot cut:** ChatConsole, Agent orchestration, ToolRegistry, OS sensing tools, RiskCheck, dangerous-command BLOCK, AuditLog, the core demo scenarios, the deploy doc. Cutting any of these means the project no longer hits the competition rubric.

## Common Anti-Patterns to Reject

If you see code drifting toward any of these, stop and refer back to the hard rules in SKILL.md:

| Symptom | What's actually happening | Fix |
|---|---|---|
| ChatConsole answers system-state questions instantly with no `toolCalls` in the response | Model is hallucinating OS state | Force `IntentClassifier` to route system-state intents to required tool sets; reject empty `toolCalls` for those intents |
| `POST /api/chat/send` returns success but no `auditId` field | AuditLog write was skipped | Make `auditId` required in the response DTO; fail the request if write fails |
| Security rules display in Security Center, but `rm -rf /etc/old-config` slips through | Rule check is on user message text only, not on planned action | Run RiskCheck on the planned `ExecutionPlan` before SafeExecutor, not only on raw input |
| Service restart executes without confirm | L2 path bypassed | All write actions must go through `SafeExecutor.submit(...)` which creates `PendingAction` for L2; no direct execution path |
| Tool registered but never appears in `/api/tools` | Static registration missed | Use Spring `@PostConstruct` autowiring of all `OpsTool` beans into `ToolRegistry` |
| Deploy doc claims "已在 LoongArch 完成验证" | Untrue claim | Distinguish 已验证 / 待验证 columns explicitly |
