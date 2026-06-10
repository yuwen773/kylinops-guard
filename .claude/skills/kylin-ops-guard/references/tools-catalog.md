# Tools Catalog Reference

Definition contract, command templates, and risk classification for every required `OpsTool` in P0. Source: `麒麟安全智能运维 Agent PRD v0.1.md` §7.2 / §7.3, `系统架构设计 v0.1.md` §8, `麒麟安全智能运维 Agent Coding Agent 开发任务卡 v0.1.md` Tasks 04–06 / 09.

## Table of Contents
- [OpsTool Contract](#opstool-contract)
- [Tool Implementation Rules](#tool-implementation-rules)
- [P0 Read-Only Tools (≥ 8 required)](#p0-read-only-tools--8-required)
- [Diagnosis Tools (Task 06)](#diagnosis-tools-task-06)
- [Safety Tools](#safety-tools)
- [SafeExecutor Action Whitelist (Task 09)](#safeexecutor-action-whitelist-task-09)
- [LLM Boundary in Tool Planning](#llm-boundary-in-tool-planning)

## OpsTool Contract

```java
public interface OpsTool {
    ToolDefinition definition();
    ToolResult execute(ToolInput input);
}
```

`ToolDefinition` (every field is required, no defaults):

```
toolName        String       snake_case, globally unique, audit-stable
displayName     String       Chinese for Tool Center page
description     String       Chinese, what it does for users
category        Enum         OS_OBSERVE | DISK | PROCESS | NETWORK | SERVICE | LOG | SECURITY | EXEC | REPORT
inputSchema     JsonSchema   parameters spec
outputSchema    JsonSchema   ToolResult.data spec
riskLevel       RiskLevel    default risk if no further context
permissionType  PermissionType
enabled         boolean      can be toggled from Tool Center
timeoutMs       int          per-call hard timeout
auditRequired   boolean      true for everything in P0
```

`ToolResult` (returned by `execute`):

```
toolName        String
status          ToolCallStatus    SUCCESS | FAILED | TIMEOUT
data            Object            structured payload conforming to outputSchema
summary         String            Chinese one-liner for Agent to weave into reply
errorMessage    String            present iff status != SUCCESS
startedAt       Instant
finishedAt      Instant
durationMs      long
```

`ToolRegistry` keeps the canonical map of `toolName → OpsTool`. `ToolExecutor` is the only thing allowed to call `OpsTool.execute`; it writes a `ToolCallRecord` for every invocation and enforces `timeoutMs`. Agent code MUST go through `ToolExecutor`, never call `OpsTool.execute` directly.

## Tool Implementation Rules

Every tool — even L0 reads — must do all of the following:

1. **Fixed command template.** Build `ProcessBuilder` from a hard-coded array. User input only ever flows in as parameters after whitelist validation. Never `bash -c "<concatenated string>"`.
2. **Parameter whitelist / regex.** Service names match `^[a-zA-Z0-9._@-]+$`. Paths are canonicalized and matched against an allow-list (never start with sensitive prefixes). Numeric inputs are range-checked.
3. **Hard timeout.** `Process#waitFor(timeoutMs, MILLISECONDS)`. On timeout return `ToolCallStatus.TIMEOUT` and `Process#destroyForcibly()`.
4. **Output truncation.** Cap stdout at e.g. 64 KB or 500 lines, whichever first. Truncated results include `truncated: true` in `data`.
5. **Graceful degradation on dev hosts.** If the command binary is missing (Windows dev box), return `status: FAILED` with `errorMessage: "command 'df' unavailable on this host"` — never throw to the caller.
6. **Structured output, never raw text in `data`.** Parse the command output into typed fields. Raw text only allowed inside an explicit `rawSnippet` sub-field.
7. **Every call writes `ToolCallRecord`.** Done by `ToolExecutor`, but the tool must surface `durationMs` and accurate `status`.

## P0 Read-Only Tools (≥ 8 required)

All L0, all `READ_ONLY`. Source: Task 05.

| toolName | category | Command (template) | data shape (key fields) | Notes |
|---|---|---|---|---|
| `system_info_tool` | OS_OBSERVE | `uname -m`, `cat /etc/os-release`, `uptime` | `hostname`, `osVersion`, `kernel`, `arch`, `uptimeSeconds` | Combine three reads into one tool |
| `cpu_status_tool` | OS_OBSERVE | `/proc/stat` + `top -bn1` (or parse `/proc/loadavg`) | `usagePercent`, `loadAvg1/5/15`, `topProcesses[]` | Top-N capped at 10 |
| `memory_status_tool` | OS_OBSERVE | `free -m` (+ `/proc/meminfo`) | `totalMB`, `usedMB`, `freeMB`, `swapUsedMB`, `usedPercent` | |
| `disk_usage_tool` | DISK | `df -h` | `partitions[]: { mount, sizeGB, usedGB, usedPercent, fsType }` | Skip pseudo-fs (tmpfs, devtmpfs) |
| `large_file_scan_tool` | DISK | `du -ah --max-depth=N <allowed-root>` + sort -hr | `files[]: { path, sizeMB, mtime }` capped at top 50 | Allowed roots: `/var/log`, `/tmp`, `/home/<user>` whitelist; depth ≤ 4; total runtime ≤ 5s |
| `process_list_tool` | PROCESS | `ps aux --no-headers` (sorted by %CPU desc) | `processes[]: { pid, user, cpuPct, memPct, command }` capped at top 50 | |
| `process_detail_tool` | PROCESS | `ps -p <pid> -o ...` + `/proc/<pid>/status` | `pid`, `ppid`, `user`, `command`, `state`, `startedAt`, `cpuTime`, `memMB` | pid must be a positive int |
| `network_port_tool` | NETWORK | `ss -tulnp` | `listeners[]: { proto, localAddr, port, pid, command }` | Drop hostnames, keep IPs |
| `service_status_tool` | SERVICE | `systemctl status <name>` (or `is-active` + `is-enabled` combo) | `service`, `loadState`, `activeState`, `subState`, `mainPid`, `recentExitCode` | service name whitelist regex |
| `journal_log_tool` | LOG | `journalctl -n <N> --no-pager [-u <unit>]` | `lines[]: { ts, unit, priority, message }` | `N` ≤ 200; never dump full file; redact known sensitive patterns |
| `command_risk_check_tool` | SECURITY | none — calls `RiskCheckService` internally | RiskCheck output | Exposes RiskCheck as a callable tool so Agent can preview risk of a planned action |

## Diagnosis Tools (Task 06)

Add when service-diagnosis demo is wired. All L0/L1, READ_ONLY.

| toolName | Notes |
|---|---|
| `service_log_tool` | `journalctl -u <name> -n <N> --no-pager` — like `journal_log_tool` but unit-scoped |
| `zombie_process_scan_tool` | `ps -eo pid,ppid,state,comm | awk '$3=="Z"'` |
| `port_conflict_check_tool` | takes a port number, returns listener (re-uses `ss` output) |

## Safety Tools

`PromptInjectionDetector` and `RiskRuleEngine` are not tools — they're services consulted by `RiskCheckService`. Only `command_risk_check_tool` is exposed in `ToolRegistry`, mostly so the Tool Center page can show RiskCheck as a first-class capability.

## SafeExecutor Action Whitelist (Task 09)

Writes are confined to this list. Every action:
- Goes through `RiskCheckService` first (Agent must not call `SafeExecutor` without a check)
- Generates a `PendingAction` if `decision == CONFIRM`
- Records `ExecutionResult` with same `auditId`
- Runs under the restricted account (never root)
- Has a hard timeout
- Implements the `_preview` variant **before** the real-effect variant; ship the preview first

| Action | Risk | Permission | Effect |
|---|---|---|---|
| `safe_service_restart` | L2 | CONFIRM_EXEC | `systemctl restart <name>` (service name whitelist; only restart, no enable/disable/mask) |
| `safe_temp_clean_preview` | L1 | LIMITED_EXEC | List candidate files under `/tmp/<allow-listed-subdir>` — no deletion |
| `safe_temp_clean` | L2 | CONFIRM_EXEC | Delete files identified by the preceding preview's `actionId` — never accepts free-form paths |
| `safe_log_truncate_preview` | L1 | LIMITED_EXEC | Show what would be truncated (file, current size, target size) |
| `safe_log_truncate` | L2 | CONFIRM_EXEC | `truncate -s <size> <path>` on a single whitelisted log file |
| `safe_file_clean_preview` | L1 | LIMITED_EXEC | Like `safe_temp_clean_preview` but for explicitly allow-listed cleanup roots |

**Explicitly not in scope for P0** (architecture §10.4): delete system dirs, modify `/etc`, format disk, modify system accounts, arbitrary `chmod`/`chown`, arbitrary `kill`, arbitrary shell.

## LLM Boundary in Tool Planning

`ToolPlanningService` may consult the LLM to *suggest* which tools to call for an intent, but:

1. The set of callable tools is fixed by `ToolRegistry`. LLM-suggested tools that don't exist are silently dropped.
2. LLM cannot add a tool, change a tool's `riskLevel`, lower a `decision`, or skip a RiskCheck step.
3. `AgentResponseBuilder` may use the LLM to phrase the final Chinese reply, but the reply must be derived from actual `ToolResult`s collected this turn — never invent system state the tools didn't return.
4. When LLM is disabled (`llm.enabled=false`), `ToolPlanningService` falls back to a keyword→intent→tool-list rule table. This must keep all four demo scenarios working.
