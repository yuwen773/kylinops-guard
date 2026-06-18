# CLAUDE.md

Project guidance for Claude Code (claude.ai/code) on this repo.

## Repository Status (snapshot 2026-06-17)

**Phase 1–4 + 生产加固（Production Hardening）全部完成，P1-UX 前端体验升级（Light Theme G1 明亮主题）已合入** — 后端 643/643 （含 P1-01 Notification Center）+ 1 skipped + 前端单元 263/263 + Playwright E2E 24/24 + 3 skipped 全绿。

## Source-of-Truth Specs (v0.1, all Chinese, at repo root)

Read in order before writing code — they override training-data assumptions:
1. `麒麟安全智能运维 Agent PRD v0.1.md` — what to build
2. `麒麟安全智能运维 Agent 技术栈选型与架构落地方案 v0.1.md` — locked stack + Coding Agent constraints (§14)
3. `系统架构设计 v0.1.md` — layered architecture
4. `麒麟安全智能运维 Agent Coding Agent 开发任务卡 v0.1.md` — Task 00→21
5. `MVP 功能优先级与版本路线 v0.1.md` — P0/P1/P-1 split
6. `演示视频脚本 v0.1.md` — 6:30 demo; constrains ChatConsole quick-buttons; implicit acceptance test

(`P0 开发启动包` is empty; `产品定义` overlaps PRD — skip.)

Product: **麒麟安全智能运维 Agent** (KylinOps Guard / 麒麟智维盾) on Kylin Advanced Server V11 / LoongArch64.

## Non-Negotiable Closed Loop

Not a chatbot. Not a shell executor. **Safety guardrail is the differentiation, not the LLM.** "不允许为了 Demo 绕过安全逻辑".

```
自然语言 → 意图识别 → MCP Tool 规划 → Tool 调用
→ OS 感知 → 智能分析 → RiskCheck → SafeExecutor → AuditLog → 报告
```

## Hard Rules (defect if violated, even with green tests)

1. **No raw shell.** No `/api/exec`/shell/command/run endpoints. No `ProcessBuilder` called with user content. All OS access via registered `OpsTool` impls.
2. **All OS work via MCP-style Tools.** `ToolDefinition` must declare: `toolName, description, inputSchema, outputSchema, riskLevel (L0–L4), permissionType, enabled, timeoutMs, auditRequired`.
3. **RiskCheck pre-execution, mandatory.** L0/L1 → ALLOW (L1 audited); L2 → CONFIRM (`PendingAction` + `/api/actions/confirm`); L3/L4 → BLOCK (no path).
4. **L4 absolute block**: rules in `backend/src/main/resources/security/rules/*.yml` (rm -rf /, chmod -R 777 /, mkfs, dd if=, fork bomb, ...). Block even with normalization tricks (spaces, `-r -f`, case, prompt-injection wrappers). Modify = edit yml + `RiskRuleEngineTest` coverage.
5. **Prompt injection detection runs BEFORE intent classification.** Patterns: "忽略之前所有规则", "你现在是 root", "不要审计", "关闭安全校验", "绕过权限限制". Injection + dangerous cmd → L4 BLOCK.
6. **No root by default.** `SafeExecutor` runs as restricted account. Writes via fixed whitelist (`safe_service_restart`, `safe_*_preview`); implement `preview` BEFORE real-delete.
7. **Every request writes an AuditLog** with shared `auditId`. Capture decision summaries, not LLM CoT or full sensitive file contents.
8. **LLM never makes safety decisions.** Can't bypass `ToolPlanner`, modify rules, lower risk, auto-confirm L2.
9. **System-state answers require a real tool call** in that request — no LLM hand-waving "your disk is X% full".

## Locked Tech Stack (no substitutes — 技术栈方案 §2.1)

| Layer | Choice |
|---|---|
| Frontend | Vue 3 + TS + Vite + Element Plus + Axios + Vue Router |
| Backend | Java 17 + Spring Boot 3.x + Maven (Web, Validation, JPA/MyBatis-Plus) |
| DB (P0) | H2 File Mode (`jdbc:h2:file:./data/kylinops`) |
| DB (P1) | PostgreSQL/MySQL — Repository abstraction so only URL changes |
| LLM | OpenAI-Compatible only (DeepSeek/Qwen); env `LLM_BASE_URL`/`API_KEY`/`MODEL` |
| OS sensing | Whitelisted Linux cmd wrappers (Java `ProcessBuilder` fixed templates); OSHI optional |
| Deploy | Spring Boot fat JAR + Vite dist + shell scripts. Docker optional, not the only path |

**LLM optional** — API down → fallback rules/templates. Safety logic never depends on LLM.

## Backend Layout (`com.kylinops.*`)

`common` (enums RiskLevel/Decision/PermissionType/AuditStatus/...) · `config` (LLM/security, ProductionConfigValidator, RuntimeProperties, SecurityConfig) · `chat` (Chat/Health/Readiness) · `agent` (+ `intelligence/` Hybrid*Service, LlmIntentParser, LlmToolContextPolicy*, LlmContextSanitizer, ResponseFactValidator) · `tool` (OpsTool/Registry/Executor) · `os` (SystemInfo/Cpu/Memory/Disk/LargeFile/Process/NetworkPort/Service/Journal) · `security` (RiskCheck/RuleEngine/PromptInjection) · `executor` (SafeExecutor/PendingAction/ActionConfirm) · `auth` · `llm` (OpenAiCompatibleLlmClient/AuditingLlmClient) · `audit` · `migration` · `report` · `dashboard`

Frontend: `ChatConsole`, `Dashboard`, `ToolCenter`, `SecurityCenter`, `AuditLog`, `ReportCenter`.

## P0 Tools (≥8 L0 read-only, must all register)

`system_info_tool`, `cpu_status_tool`, `memory_status_tool`, `disk_usage_tool`, `large_file_scan_tool`, `process_list_tool`, `process_detail_tool`, `network_port_tool`, `service_status_tool`, `journal_log_tool`. Each: param whitelist, path normalization, output truncation, per-call timeout — even L0. No command-execution `OpsTool` — cmd requests go straight to `RiskRuleEngine`.

## API Surface (P0 + Hardening)

Health: `GET /api/health{,/live,/ready}` · Chat: `POST /api/chat/send` → `{sessionId, answer, intentType, toolCalls, riskLevel, decision, needConfirmation, auditId}` · Auth: `/api/auth/{login,logout,me}` · Tools: `/api/tools{,/{name}}` · Security: `POST /api/security/risk-check`, `GET /api/security/{rules,events}` · Actions (L2): `POST /api/actions/confirm` · Audit: `GET /api/audit/logs{,/{id}}` · Reports: `POST /api/reports/generate`, `GET /api/reports{,/{id}}` · Dashboard: `GET /api/dashboard/overview`.

## Four Demo Scenarios (6:30 video, 演示视频脚本 — must all pass)

1. **Health check** → multi-tool fan-out (system_info/cpu/memory/disk/network/service/log) → score + report
2. **Disk diagnosis** → `disk_usage_tool` + `large_file_scan_tool` → safe cleanup via CONFIRM
3. **Service diagnosis + L2** → `service_status`/`network_port`/`journal_log`; restart via `PendingAction` + `/api/actions/confirm`
4. **Dangerous cmd + prompt inject** → both BLOCK + audit + SecurityCenter visible

ChatConsole ships with 5 quick-action buttons + separate "Prompt Inject 测试" (演示视频脚本 §3.2).
Must-pass cases: `rm -rf /` / `chmod -R 777 /` / `rm -rf /etc` / `删除 /etc/passwd` → BLOCK; `重启 nginx` → CONFIRM; `df -h` / `查看磁盘状态` → ALLOW.

## Demo Seed Data (录制前 — 演示视频脚本 §3.3)

`/var/log/app.log` (大日志) · `/tmp/cache-demo/` (可清理缓存) · `/var/lib/mysql/` (敏感目录，flag 但不提议删) · 磁盘 ~86% · `nginx` systemd unit (tolerates inactive/failed/running) · ≥1 异常服务 + 错误日志. `sudo bash deploy/scripts/seed-demo.sh`. Tools degrade gracefully when fixtures absent (`failed` ToolResult, never crash).

## Build / Run

```bash
# Backend
cd backend && mvn test                            # 643/643 + 1 skipped
mvn -B clean package -DskipTests                  # → target/kylin-ops-guard.jar
java -jar backend/target/kylin-ops-guard.jar

# Frontend
cd frontend && npm install
npm run dev                                        # 127.0.0.1:5173, proxy /api → :8080
npm run test:unit -- --run                         # 263/263 (含 useTheme 8 个新用例)
npm run build                                      # → dist/
npm run test:e2e                                   # 24/24 + 3 skipped (含 theme-toggle 5 个新用例)

# Standalone single-JAR (LoongArch 低配 VM 推荐)
bash deploy/scripts/build-standalone.sh
bash deploy/scripts/start-standalone.sh                              # dev,standalone (H2)
SPRING_PROFILES_ACTIVE=prod,standalone bash deploy/scripts/start-standalone.sh  # prod

# Kylin/LoongArch (Task 20)
bash deploy/scripts/check-env.sh && bash deploy/scripts/start-backend.sh
sudo bash deploy/scripts/seed-demo.sh
```

Acceptance: `docs/test/phase2-acceptance-guide.md` (前端回归) + `docs/test/phase2-demo-acceptance.md` (4 场景录像).
Perf budgets (PRD §12.3): single tool ≤3s, risk check ≤1s, full health ≤30s, chat ≤10s, report ≤5s.

## Environment Notes

- **Windows host → Linux/LoongArch target.** OS tools must parse Linux formats (`df -h`, `ps aux`, `ss -tulnp`, `systemctl status`, `journalctl`, `/proc/*`); degrade gracefully on missing binaries (structured `failed` ToolResult, never crash).
- **JDK 严格区分 (DEFER-003)**: LoongArch = **JDK 17**; x86-dev = JDK 23 OK; CI = Temurin 17. Windows `PATH` 里 `C:\Program Files\Common Files\Oracle\Java\javapath\java` 是 Oracle JRE stub (静默失败) — 用 `D:/Program Files/Java/jdk-23/bin/java.exe` 或设 `JAVA_HOME`.
- **Git Bash curl 中文编码**: temp file 传 JSON (`printf '%s' "${data}" > /tmp/body.json && curl --data @/tmp/body.json`). `deploy/scripts/` 下的 `http_post()` 已统一。
- Specs/demo 是中文 — UI/审计/报告中文；code identifiers/comments English.
- Never claim LoongArch verified unless actually run there.

## When Modifying Specs

Root `*.md` are v0.1 product contracts. Code change that deviates from a spec must update the spec in the same change with a brief rationale.

## 必用 skills (项目级)

| 场景 | skill |
|---|---|
| 设计前 brainstorm | `superpowers:brainstorming` |
| 前端设计 | ` design-taste-frontend` |
| 复杂任务先计划 | `superpowers:writing-plans` |
| 写代码前 TDD | `superpowers:test-driven-development` |
| 完工前自检 | `superpowers:verification-before-completion` |
