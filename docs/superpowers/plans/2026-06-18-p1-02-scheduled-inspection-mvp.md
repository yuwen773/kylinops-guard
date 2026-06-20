# P1-02 Scheduled Inspection MVP Implementation Plan

> ## ✅ Status: COMPLETED — 2026-06-20
>
> - **完成时间**：2026-06-20
> - **Worktree 分支**：`worktree-p1-02-inspection-mvp`（harness 管理,保留）
> - **Worktree 路径**：`D:/Work/code/kylin-ops/.claude/worktrees/p1-02-inspection-mvp`
> - **Merge commit**：`2602ac9`（--no-ff 合入 master）
> - **Tag**：`v0.4-inspection-mvp`（annotated）
> - **验收基线**：
>   - backend `mvn test`：**879 / 879 + 2 skipped**（exit 0,3:07 min）
>   - frontend vitest：**335 / 335**（exit 0,32 files）
>   - Playwright e2e：**38 passed + 3 skipped**（exit 0,26.6s）
>   - 合并后 smoke：`mvn test-compile` + `vue-tsc --noEmit` 两者 exit 0
> - **验收指南**：[docs/test/p1-02-inspection-acceptance-guide.md](../../test/p1-02-inspection-acceptance-guide.md)
> - **9 commits**：T1-T8 实施 + 1 验收指南（de6b091），全部在 master 落地
>
> 下方原始 TDD 计划保留供后续回溯。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a single-host, read-only scheduled inspection capability with HEALTH, DISK, and SERVICE templates, persisted plans and executions, audit/report/notification closure, and a dedicated Vue management page.

**Architecture:** Introduce a focused `com.kylinops.inspection` domain. Plans are persisted and scheduled through Spring `TaskScheduler`; executions reuse registered `OpsTool`, `RiskCheckService`, RCA, audit, report, and notification services without passing through chat or LLM intent classification. The frontend adds one authenticated page that manages plans and reads execution history while reusing existing report and audit detail routes.

**Tech Stack:** Java 17, Spring Boot 3.3, Spring Data JPA, Flyway, H2/PostgreSQL-compatible SQL, Vue 3, TypeScript, Vite, Element Plus, Vitest, Playwright.

**Design spec:** `docs/superpowers/specs/2026-06-18-p1-02-scheduled-inspection-mvp-design.md`

**Required implementation skills:** `@superpowers:test-driven-development`, `@kylin-ops-guard`, `@design-taste-frontend` for Task 8–9, and `@superpowers:verification-before-completion` before the final claim.

---

## Execution Prerequisite

Execute this plan in an isolated worktree created with `@superpowers:using-git-worktrees` from commit `57b9819` or a later clean base. The current main workspace contains unrelated user edits, including `AppLayout.vue` and `functional-defect-and-roadmap.md`; do not implement or stage this feature in that dirty workspace.

Before Task 1 run:

```powershell
git status --short
```

Expected in the implementation worktree: clean output. If it is not clean, stop and resolve ownership before continuing.

---

## File Structure

### Backend domain

Create under `backend/src/main/java/com/kylinops/inspection/`:

- `InspectionPlan.java` — persisted schedule and template configuration.
- `InspectionExecution.java` — immutable plan snapshot plus execution state.
- `InspectionPlanRepository.java` / `InspectionExecutionRepository.java` — persistence and atomic execution-lock queries.
- `InspectionEnums.java` is **not** allowed; create one enum per file under `inspection/model/` to match project conventions.
- `InspectionProperties.java` — `inspection.allowed-services` configuration.
- `InspectionScheduleCalculator.java` — daily/weekly/monthly and DST behavior.
- `InspectionTemplateRegistry.java` — fixed template definitions only.
- `InspectionPlanValidator.java` — schedule, threshold, path, service allowlist validation.
- `InspectionRiskContextFactory.java` — canonical sorted-JSON RiskCheck content.
- `InspectionLogErrorClassifier.java` — deterministic journal error adapter.
- `InspectionResultEvaluator.java` — status/abnormal calculation.
- `InspectionExecutionService.java` — read-only execution closed loop.
- `InspectionScheduler.java` — due-plan dispatch and startup recovery.
- `InspectionPlanService.java` — CRUD, enable/disable, manual run.
- `InspectionController.java` and DTOs under `inspection/api/`.

Modify existing focused integration points:

- `backend/src/main/java/com/kylinops/audit/AuditLog.java`
- `backend/src/main/java/com/kylinops/audit/AuditLogService.java`
- `backend/src/main/java/com/kylinops/audit/AuditLogDetail.java`
- `backend/src/main/java/com/kylinops/report/ReportService.java`
- `backend/src/main/java/com/kylinops/notification/NotificationEventType.java`
- `backend/src/main/java/com/kylinops/notification/NotificationEventFactory.java`
- `backend/src/main/resources/application.yml`
- `backend/src/main/resources/db/migration/V6__scheduled_inspection.sql`

Do not add a raw-shell endpoint, generic workflow engine, cron expression parser, or SafeExecutor path.

### Frontend

Create:

- `frontend/src/types/inspection.ts`
- `frontend/src/api/inspections.ts`
- `frontend/src/api/inspections.spec.ts`
- `frontend/src/pages/ScheduledInspection/index.vue`
- `frontend/src/pages/ScheduledInspection/index.spec.ts`
- `frontend/tests/e2e/scheduled-inspection.spec.ts`

Modify:

- `frontend/src/router/index.ts`
- `frontend/src/layouts/AppLayout.vue`
- `frontend/src/layouts/AppLayout.spec.ts`
- `frontend/tests/e2e/fixtures.ts`

### Product documentation

Modify only documentation directly affected by the shipped feature:

- `docs/product/functional-defect-and-roadmap.md`
- `docs/product/software-requirements-analysis.md`
- `docs/design/software-design-document.md`
- `docs/product/product-manual.md`
- `docs/test/functional-test-report.md`
- `docs/deploy/install-and-deploy-guide.md`

---

### Task 1: Define inspection enums, configuration, and schedule calculation

**Files:**
- Create: `backend/src/main/java/com/kylinops/inspection/model/InspectionTemplateType.java`
- Create: `backend/src/main/java/com/kylinops/inspection/model/InspectionScheduleType.java`
- Create: `backend/src/main/java/com/kylinops/inspection/model/InspectionNotificationPolicy.java`
- Create: `backend/src/main/java/com/kylinops/inspection/model/InspectionTriggerType.java`
- Create: `backend/src/main/java/com/kylinops/inspection/model/InspectionExecutionStatus.java`
- Create: `backend/src/main/java/com/kylinops/inspection/InspectionProperties.java`
- Create: `backend/src/main/java/com/kylinops/inspection/InspectionScheduleConfig.java`
- Create: `backend/src/main/java/com/kylinops/inspection/InspectionScheduleCalculator.java`
- Test: `backend/src/test/java/com/kylinops/inspection/InspectionScheduleCalculatorTest.java`
- Modify: `backend/src/main/resources/application.yml`

- [ ] **Step 1: Write failing schedule tests**

Cover:

```java
@Test void dailyUsesPlanTimezone()
@Test void weeklyUsesJavaDayOfWeek()
@Test void monthlyRejectsDay29()
@Test void springForwardMovesToFirstValidInstant()
@Test void fallBackUsesEarlierOffsetOnlyOnce()
@Test void missedRunCalculatesNextFutureOccurrence()
```

Use `Clock.fixed(...)`, `ZoneId.of("Asia/Shanghai")`, and one DST zone such as `America/New_York`.

- [ ] **Step 2: Run the focused test and verify failure**

Run:

```powershell
cd backend
mvn -Dtest=InspectionScheduleCalculatorTest test
```

Expected: compilation failure because inspection schedule classes do not exist.

- [ ] **Step 3: Implement minimal enums and schedule DTO**

Use explicit values:

```java
public enum InspectionScheduleType { DAILY, WEEKLY, MONTHLY }
public enum InspectionExecutionStatus { RUNNING, SUCCESS, PARTIAL_SUCCESS, FAILED, SKIPPED }
```

`InspectionScheduleConfig` contains `LocalTime localTime`, optional `DayOfWeek dayOfWeek`, and optional `Integer dayOfMonth`. Validate the shape according to `scheduleType`.

- [ ] **Step 4: Implement `InspectionScheduleCalculator`**

Provide:

```java
Instant nextRun(
    InspectionScheduleType type,
    InspectionScheduleConfig config,
    ZoneId zone,
    Instant afterExclusive)
```

Resolve DST gaps with the zone transition’s first valid instant and overlaps with the earlier offset.

- [ ] **Step 5: Add service allowlist configuration**

Bind:

```yaml
inspection:
  allowed-services:
    - nginx
```

Use `@ConfigurationProperties(prefix = "inspection")`; do not reuse SafeExecutor’s write-action whitelist because the concerns differ.

- [ ] **Step 6: Run tests**

Run:

```powershell
mvn -Dtest=InspectionScheduleCalculatorTest test
```

Expected: all schedule tests PASS.

- [ ] **Step 7: Commit**

```powershell
git add backend/src/main/java/com/kylinops/inspection backend/src/test/java/com/kylinops/inspection/InspectionScheduleCalculatorTest.java backend/src/main/resources/application.yml
git commit -m "feat(inspection): 添加巡检周期计算"
```

---

### Task 2: Add Flyway schema, entities, and repositories

**Files:**
- Create: `backend/src/main/resources/db/migration/V6__scheduled_inspection.sql`
- Create: `backend/src/main/java/com/kylinops/inspection/InspectionPlan.java`
- Create: `backend/src/main/java/com/kylinops/inspection/InspectionExecution.java`
- Create: `backend/src/main/java/com/kylinops/inspection/InspectionPlanRepository.java`
- Create: `backend/src/main/java/com/kylinops/inspection/InspectionExecutionRepository.java`
- Test: `backend/src/test/java/com/kylinops/inspection/InspectionRepositoryTest.java`
- Modify: `backend/src/test/java/com/kylinops/migration/SchemaFingerprint.java`
- Test: `backend/src/test/java/com/kylinops/migration/FlywayH2MigrationTest.java`
- Test: `backend/src/test/java/com/kylinops/migration/FlywayPostgresMigrationTest.java`

- [ ] **Step 1: Extend migration fingerprint tests first**

Assert both tables and indexes exist:

```text
inspection_plans
inspection_executions
uk_inspection_plan_name
idx_inspection_plan_due
idx_inspection_execution_plan_started
idx_inspection_execution_status
```

Assert `inspection_executions.plan_id` has no foreign key to `inspection_plans`. Also assert V6 adds nullable `trigger_type` and `operator` columns to `audit_logs`.

- [ ] **Step 2: Run migration tests and verify failure**

```powershell
cd backend
mvn -Dtest=FlywayH2MigrationTest,FlywayPostgresMigrationTest test
```

Expected: FAIL because V6 tables are absent.

- [ ] **Step 3: Add portable V6 migration**

Use `VARCHAR`, `BOOLEAN`, `TIMESTAMP`, `BIGINT`, and text/CLOB placeholders already supported by the migration test infrastructure. Add:

```sql
version BIGINT NOT NULL DEFAULT 0
```

Store timestamps in UTC. Do not use H2-only generated columns or PostgreSQL-only partial indexes.

In the same V6 migration, add nullable `trigger_type VARCHAR(32)` and `operator VARCHAR(128)` to `audit_logs`. These columns must exist before Task 4 maps them; V6 is never edited after Task 2 is committed.

- [ ] **Step 4: Write repository tests**

Test:

- save and reload plan JSON snapshots;
- optimistic-lock conflict;
- deleting a plan preserves execution;
- query enabled plans with `nextRunAt <= now`;
- a transaction holding a pessimistic write lock on the plan refuses a second active execution;
- startup query finds abandoned `RUNNING` rows.

- [ ] **Step 5: Implement entities and repositories**

Use `@Version` on `InspectionPlan`. Keep `planId` on execution as a plain string field.

Use one exact cross-database claim strategy:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select p from InspectionPlan p where p.planId = :planId")
Optional<InspectionPlan> findByPlanIdForUpdate(String planId);
```

Inside one transaction:

1. lock the plan row;
2. query `existsByPlanIdAndStatus(planId, RUNNING)`;
3. if true, insert `SKIPPED`;
4. otherwise insert `RUNNING` and commit.

This serializes claims in H2 and PostgreSQL without partial indexes or JVM-only locks.

- [ ] **Step 6: Run persistence tests**

```powershell
mvn -Dtest=InspectionRepositoryTest,FlywayH2MigrationTest test
```

Expected: PASS. PostgreSQL test may report its existing explicit external-environment skip when Docker/embedded PostgreSQL is unavailable; do not claim PostgreSQL runtime verification in that case.

- [ ] **Step 7: Commit**

```powershell
git add backend/src/main/resources/db/migration/V6__scheduled_inspection.sql backend/src/main/java/com/kylinops/inspection backend/src/test/java/com/kylinops/inspection/InspectionRepositoryTest.java backend/src/test/java/com/kylinops/migration
git commit -m "feat(inspection): 添加巡检计划持久化"
```

---

### Task 3: Implement template validation and deterministic adapters

**Files:**
- Create: `backend/src/main/java/com/kylinops/inspection/InspectionTemplateDefinition.java`
- Create: `backend/src/main/java/com/kylinops/inspection/InspectionTemplateRegistry.java`
- Create: `backend/src/main/java/com/kylinops/inspection/InspectionPlanValidator.java`
- Create: `backend/src/main/java/com/kylinops/inspection/InspectionRiskContextFactory.java`
- Create: `backend/src/main/java/com/kylinops/inspection/InspectionLogErrorClassifier.java`
- Create: `backend/src/main/java/com/kylinops/inspection/InspectionToolResultAdapter.java`
- Test: `backend/src/test/java/com/kylinops/inspection/InspectionTemplateRegistryTest.java`
- Test: `backend/src/test/java/com/kylinops/inspection/InspectionPlanValidatorTest.java`
- Test: `backend/src/test/java/com/kylinops/inspection/InspectionRiskContextFactoryTest.java`
- Test: `backend/src/test/java/com/kylinops/inspection/InspectionLogErrorClassifierTest.java`

- [ ] **Step 1: Write failing template contract tests**

Assert exact plans:

```text
HEALTH: system/cpu/memory/disk/process/network/service in stage 0; journal in stage 1
DISK: disk stage 0; large-file stage 1; optional journal stage 2
SERVICE: service/network stage 0; journal stage 1
```

Assert no template contains an action or non-read-only tool.

- [ ] **Step 2: Write validation boundary tests**

Cover percentage `50..100`, large file `100..1048576`, port `1..65535`, scan roots under `/var/log`, `/tmp`, `/home`, service regex plus configured allowlist, and invalid schedule shapes.

- [ ] **Step 3: Write canonical RiskCheck serialization tests**

Example expected content:

```text
journal_log_tool {"lines":50,"serviceName":"nginx"}
```

Assert key order is stable and secret-like keys are rejected rather than serialized.

- [ ] **Step 4: Write log classifier tests**

Positive corpus: `ERROR`, `failed`, `failure`, `fatal`, `panic`, `exception`, `segfault`, `OOM`, `out of memory`, `permission denied`.

Negative corpus: `0 errors`, `failed login count: 0` only if the line lacks a real failure marker is ambiguous; keep MVP deterministic and document the accepted false-positive tradeoff instead of adding NLP.

- [ ] **Step 5: Run tests and verify failure**

```powershell
mvn -Dtest=InspectionTemplateRegistryTest,InspectionPlanValidatorTest,InspectionRiskContextFactoryTest,InspectionLogErrorClassifierTest test
```

- [ ] **Step 6: Implement minimal registry, validator, and adapters**

Use fixed Java definitions, not YAML workflows. `InspectionToolResultAdapter` converts:

- `large_file_scan_tool.data.files` into the shape consumed by disk RCA;
- classified journal entries into `data.errors`;
- expected-port comparison into deterministic service evidence.

- [ ] **Step 7: Run tests**

Expected: all focused tests PASS.

- [ ] **Step 8: Commit**

```powershell
git add backend/src/main/java/com/kylinops/inspection backend/src/test/java/com/kylinops/inspection
git commit -m "feat(inspection): 添加内置巡检模板"
```

---

### Task 4: Extend audit, report, and notification contracts

**Files:**
- Modify: `backend/src/main/java/com/kylinops/audit/AuditLog.java`
- Modify: `backend/src/main/java/com/kylinops/audit/AuditLogService.java`
- Modify: `backend/src/main/java/com/kylinops/audit/AuditLogDetail.java`
- Modify: `backend/src/main/java/com/kylinops/audit/AuditLogSummary.java`
- Modify: `backend/src/main/java/com/kylinops/report/ReportService.java`
- Modify: `backend/src/main/java/com/kylinops/notification/NotificationEventType.java`
- Modify: `backend/src/main/java/com/kylinops/notification/NotificationEventFactory.java`
- Test: `backend/src/test/java/com/kylinops/inspection/InspectionAuditIntegrationTest.java`
- Test: `backend/src/test/java/com/kylinops/inspection/InspectionReportIntegrationTest.java`
- Test: `backend/src/test/java/com/kylinops/inspection/InspectionNotificationFactoryTest.java`

- [ ] **Step 1: Write failing audit tests**

Require:

```java
triggerType == "SCHEDULED"
operator == "SYSTEM_SCHEDULER"
sessionId == null
confirmationRequired == false
pendingAction == null
```

Manual execution records the authenticated administrator.

- [ ] **Step 2: Write failing report and notification tests**

Verify report generation works from an inspection audit with no session. Add minimal event types:

```java
INSPECTION_COMPLETED
INSPECTION_ABNORMAL
INSPECTION_FAILED
```

Assert event detail contains plan name, template, status, summary, auditId, and nullable reportId.

- [ ] **Step 3: Run focused tests**

```powershell
mvn -Dtest=InspectionAuditIntegrationTest,InspectionReportIntegrationTest,InspectionNotificationFactoryTest test
```

Expected: FAIL because inspection metadata and event types are missing.

- [ ] **Step 4: Implement minimal audit extension**

Use the nullable `trigger_type` and `operator` columns already created in Task 2’s V6 migration, and add corresponding entity/DTO fields. Do not edit an already-applied Flyway migration after Task 2. Add a dedicated service method:

```java
AuditLog createInspectionAudit(
    String auditId,
    IntentType intentType,
    String triggerType,
    String operator,
    String summary)
```

Do not fake `userInput` or create Session/Message rows.

- [ ] **Step 5: Implement report and notification extensions**

Keep `ReportService.generate(...)` based on `auditId`. Add inspection-specific factory methods without adding inspection branching to channel implementations.

- [ ] **Step 6: Run focused and existing regression tests**

```powershell
mvn -Dtest=InspectionAuditIntegrationTest,InspectionReportIntegrationTest,InspectionNotificationFactoryTest,AuditLogControllerTest,ReportServiceTest,NotificationEventFactoryTest test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add backend/src/main/java/com/kylinops/audit backend/src/main/java/com/kylinops/report backend/src/main/java/com/kylinops/notification backend/src/test/java/com/kylinops/inspection
git commit -m "feat(inspection): 打通巡检审计报告通知"
```

---

### Task 5: Implement result evaluation and read-only execution closure

**Files:**
- Create: `backend/src/main/java/com/kylinops/inspection/InspectionResultEvaluator.java`
- Create: `backend/src/main/java/com/kylinops/inspection/InspectionStageExecutor.java`
- Create: `backend/src/main/java/com/kylinops/inspection/InspectionExecutionService.java`
- Test: `backend/src/test/java/com/kylinops/inspection/InspectionResultEvaluatorTest.java`
- Test: `backend/src/test/java/com/kylinops/inspection/InspectionStageExecutorTest.java`
- Test: `backend/src/test/java/com/kylinops/inspection/InspectionExecutionServiceTest.java`
- Test: `backend/src/test/java/com/kylinops/inspection/InspectionReadOnlySafetyTest.java`

- [ ] **Step 1: Write status/abnormal tests**

Cover:

- all tools pass and below thresholds → `SUCCESS`, `abnormal=false`;
- all tools pass but threshold exceeded → `SUCCESS`, `abnormal=true`;
- noncritical tool fails → `PARTIAL_SUCCESS`, `abnormal=true`;
- critical tool fails → `FAILED`, `abnormal=true`;
- report failure downgrades `SUCCESS` to `PARTIAL_SUCCESS`;
- report failure keeps an existing `FAILED`;
- notification failure does not alter execution status.

- [ ] **Step 2: Write fail-closed safety tests**

Verify full-plan preflight rejects:

- unregistered tool;
- disabled tool;
- L2+ tool;
- `PermissionType` other than `READ`;
- RiskCheck decision other than `ALLOW`.

Verify no `ToolExecutor.execute(...)` call occurs and no `PendingAction` is created.

- [ ] **Step 3: Write stage-parallelism tests**

Use latches or controlled delays to prove:

- HEALTH stage 0 starts system/CPU/memory/disk/process/network/service concurrently;
- SERVICE stage 0 starts service and network concurrently;
- later journal stages start only after every future in the preceding stage completes;
- a failure is captured as a `ToolResult` and does not leak an unhandled future exception;
- the executor is bounded and shut down with the application context.

- [ ] **Step 4: Write mid-flight recheck test**

Simulate a tool becoming disabled after the first read-only step. Assert completed calls remain audited, current and remaining tools do not run, and execution finishes `FAILED`.

- [ ] **Step 5: Write audit failure compensation tests**

Cover:

- audit creation throws before tools: execution becomes `FAILED`, no tool runs, no report is attempted, failure notification may be emitted with `auditId=null`, and no row remains `RUNNING`;
- audit update throws after tools: execution becomes `FAILED`, existing `auditId` is retained, report generation is attempted and may fail independently, failure notification is emitted, and no success status is persisted;
- every tool call receives the already-created shared `auditId`.

- [ ] **Step 6: Run focused tests and verify failure**

```powershell
mvn -Dtest=InspectionResultEvaluatorTest,InspectionStageExecutorTest,InspectionExecutionServiceTest,InspectionReadOnlySafetyTest test
```

- [ ] **Step 7: Implement stage-parallel execution**

`InspectionStageExecutor` groups steps by order. For each order it submits one `CompletableFuture` per step to a dedicated bounded executor, waits for all futures in that order, then proceeds to the next order. Do not change `ToolExecutor` internals or create one thread pool per execution.

- [ ] **Step 8: Implement `InspectionExecutionService`**

Required order:

```text
claim/create execution
→ create audit
→ full-plan preflight
→ per-step RiskCheck and metadata recheck
→ ToolExecutor
→ adapters + RCA
→ evaluator
→ finalize audit
→ attempt report
→ emit notification
→ persist terminal execution
```

Do not call `AgentOrchestrator`, `HybridIntentService`, `SafeExecutor`, or `ActionConfirmService`.

- [ ] **Step 9: Run focused tests**

Expected: PASS.

- [ ] **Step 10: Run existing safety regression**

```powershell
mvn -Dtest=AgentOrchestratorSecurityTest,RiskRuleEngineTest,ExecutionAuditFailClosedTest test
```

Expected: PASS.

- [ ] **Step 11: Commit**

```powershell
git add backend/src/main/java/com/kylinops/inspection backend/src/test/java/com/kylinops/inspection
git commit -m "feat(inspection): 实现只读巡检执行闭环"
```

---

### Task 6: Implement plan service, scheduler, and startup recovery

**Files:**
- Create: `backend/src/main/java/com/kylinops/inspection/InspectionPlanService.java`
- Create: `backend/src/main/java/com/kylinops/inspection/InspectionScheduler.java`
- Create: `backend/src/main/java/com/kylinops/inspection/InspectionSchedulingConfig.java`
- Test: `backend/src/test/java/com/kylinops/inspection/InspectionPlanServiceTest.java`
- Test: `backend/src/test/java/com/kylinops/inspection/InspectionSchedulerTest.java`
- Test: `backend/src/test/java/com/kylinops/inspection/InspectionRecoveryTest.java`

- [ ] **Step 1: Write plan lifecycle tests**

Cover create-disabled, edit with version, conflict, enable computes next run, disable clears/cancels future scheduling, delete preserves history, and delete-running rejection.

- [ ] **Step 2: Write scheduler tests**

With a fixed clock verify:

- due enabled plan triggers once;
- disabled plan never triggers;
- manual run does not change `nextRunAt`;
- overlap writes `SKIPPED` with conflict ID;
- skipped execution produces no report or notification;
- missed schedule is not replayed.

- [ ] **Step 3: Write startup recovery tests**

Assert abandoned `RUNNING` rows become `FAILED`; existing audit is finalized; report and notification are attempted; missing auditId produces no fabricated audit; next future run is calculated.

- [ ] **Step 4: Run focused tests and verify failure**

```powershell
mvn -Dtest=InspectionPlanServiceTest,InspectionSchedulerTest,InspectionRecoveryTest test
```

- [ ] **Step 5: Implement services**

Use a bounded `ThreadPoolTaskScheduler`. Keep scheduler responsibilities limited to due-plan discovery, trigger submission, rescheduling, and recovery. All business execution stays in `InspectionExecutionService`.

- [ ] **Step 6: Run tests**

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add backend/src/main/java/com/kylinops/inspection backend/src/test/java/com/kylinops/inspection
git commit -m "feat(inspection): 添加巡检调度与恢复"
```

---

### Task 7: Add authenticated REST API and DTO validation

**Files:**
- Create: `backend/src/main/java/com/kylinops/inspection/api/InspectionController.java`
- Create: `backend/src/main/java/com/kylinops/inspection/api/InspectionPlanRequest.java`
- Create: `backend/src/main/java/com/kylinops/inspection/api/InspectionPlanDetail.java`
- Create: `backend/src/main/java/com/kylinops/inspection/api/InspectionPlanSummary.java`
- Create: `backend/src/main/java/com/kylinops/inspection/api/InspectionExecutionDetail.java`
- Create: `backend/src/main/java/com/kylinops/inspection/api/InspectionExecutionSummary.java`
- Create: `backend/src/main/java/com/kylinops/inspection/api/InspectionTemplateView.java`
- Test: `backend/src/test/java/com/kylinops/inspection/InspectionControllerTest.java`
- Test: `backend/src/test/java/com/kylinops/inspection/InspectionSecurityBoundaryTest.java`

- [ ] **Step 1: Write controller contract tests**

Cover all endpoints from the spec, pagination/filter parameters, 409 version conflict, 400 validation errors, 404 missing plan, 409 delete-running, and manual run returning `executionId`.

- [ ] **Step 2: Write authentication/CSRF tests**

Assert unauthenticated GET returns 401 and authenticated mutating requests require CSRF. No new role model is added.

- [ ] **Step 3: Run tests and verify failure**

```powershell
mvn -Dtest=InspectionControllerTest,InspectionSecurityBoundaryTest test
```

- [ ] **Step 4: Implement API**

Keep controller thin. Obtain manual operator from the existing authenticated principal. Clamp execution list size to `1..100`.

Lock the create contract to a concrete payload:

```json
{
  "name": "每日系统巡检",
  "description": "每天早上检查 nginx 节点",
  "templateType": "HEALTH",
  "templateParams": { "serviceName": "nginx" },
  "thresholds": {
    "cpuWarningPercent": 80,
    "memoryWarningPercent": 80,
    "diskWarningPercent": 85
  },
  "schedule": {
    "scheduleType": "DAILY",
    "localTime": "08:00",
    "timezone": "Asia/Shanghai"
  },
  "notificationPolicy": "ON_ABNORMAL"
}
```

The response returns the persisted plan with `enabled=false`, `version=0`, and nullable `nextRunAt`. `POST /{planId}/run` returns:

```json
{ "executionId": "uuid", "status": "RUNNING" }
```

- [ ] **Step 5: Run tests**

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add backend/src/main/java/com/kylinops/inspection backend/src/test/java/com/kylinops/inspection
git commit -m "feat(inspection): 添加巡检管理接口"
```

---

### Task 8: Add frontend contracts, API client, route, and navigation

**Files:**
- Create: `frontend/src/types/inspection.ts`
- Create: `frontend/src/api/inspections.ts`
- Create: `frontend/src/api/inspections.spec.ts`
- Modify: `frontend/src/router/index.ts`
- Modify: `frontend/src/layouts/AppLayout.vue`
- Modify: `frontend/src/layouts/AppLayout.spec.ts`

- [ ] **Step 1: Write failing API tests**

Assert exact methods, paths, query encoding, CSRF-bearing mutations, and `run` response type.

- [ ] **Step 2: Write failing navigation test**

Assert sidebar contains “定时巡检” and route `/inspections`.

- [ ] **Step 3: Run focused frontend tests**

```powershell
cd frontend
npm run test:unit -- --run src/api/inspections.spec.ts src/layouts/AppLayout.spec.ts
```

Expected: FAIL because contracts and route do not exist.

- [ ] **Step 4: Implement types and API wrappers**

Mirror backend DTOs exactly. Do not calculate safety, execution status, or abnormal state in the frontend.

- [ ] **Step 5: Add lazy route and navigation item**

Place “定时巡检” between “系统总览” and “工具中心”. Use an existing Element Plus icon; do not add a dependency.

- [ ] **Step 6: Run focused tests**

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add frontend/src/types/inspection.ts frontend/src/api/inspections.ts frontend/src/api/inspections.spec.ts frontend/src/router/index.ts frontend/src/layouts/AppLayout.vue frontend/src/layouts/AppLayout.spec.ts
git commit -m "feat(frontend): 添加定时巡检入口"
```

---

### Task 9: Build the Scheduled Inspection management page

**Files:**
- Create: `frontend/src/pages/ScheduledInspection/index.vue`
- Create: `frontend/src/pages/ScheduledInspection/index.spec.ts`

- [ ] **Step 1: Write failing page tests**

Cover:

- plan and execution tabs;
- loading/error/empty states;
- template-driven form fields;
- new plan defaults disabled;
- threshold validation;
- allowlisted service select;
- daily/weekly/monthly conditional fields;
- create/edit/enable/disable/delete;
- delete history-preservation warning;
- run-now polling;
- execution filters;
- audit link `/audit?auditId=...`;
- report link `/reports?reportId=...`.

- [ ] **Step 2: Run the page test and verify failure**

```powershell
npm run test:unit -- --run src/pages/ScheduledInspection/index.spec.ts
```

- [ ] **Step 3: Implement the page shell and read paths**

Use existing common loading/error/empty components and project CSS tokens. Avoid adding a new design system or state library.

- [ ] **Step 4: Implement plan form and mutations**

Use an Element Plus drawer or dialog consistent with current pages. Keep the form structured; never expose cron or arbitrary JSON.

- [ ] **Step 5: Implement execution history and polling**

Poll only the returned execution ID and stop on terminal status or component unmount. Do not add global background polling.

- [ ] **Step 6: Run page and full unit tests**

```powershell
npm run test:unit -- --run src/pages/ScheduledInspection/index.spec.ts
npm run test:unit -- --run
```

Expected: focused test PASS; full unit suite PASS.

- [ ] **Step 7: Run frontend build**

```powershell
npm run build
```

Expected: TypeScript check and Vite build PASS.

- [ ] **Step 8: Commit**

```powershell
git add frontend/src/pages/ScheduledInspection
git commit -m "feat(frontend): 实现定时巡检管理页"
```

---

### Task 10: Add end-to-end acceptance coverage

**Files:**
- Modify: `frontend/tests/e2e/fixtures.ts`
- Create: `frontend/tests/e2e/scheduled-inspection.spec.ts`
- Create: `backend/src/test/java/com/kylinops/inspection/InspectionEndToEndIntegrationTest.java`
- Create: `backend/src/test/java/com/kylinops/inspection/InspectionLlmDisabledIntegrationTest.java`

- [ ] **Step 1: Add deterministic mocked API fixtures**

Fixture states:

- enabled HEALTH plan;
- DISK execution with `SUCCESS + abnormal=true`;
- SERVICE execution with no pending action;
- `SKIPPED` overlap execution;
- report and audit IDs.

- [ ] **Step 2: Write E2E scenarios**

Cover:

1. create and enable a daily HEALTH plan;
2. run now and observe terminal execution;
3. abnormal DISK record links to report and audit;
4. SERVICE record contains advice but no confirm card;
5. overlap is `SKIPPED` with no report link;
6. navigation and narrow viewport remain usable.

- [ ] **Step 3: Run focused E2E**

```powershell
npm run test:e2e -- scheduled-inspection.spec.ts
```

Expected: PASS.

- [ ] **Step 4: Write a real backend closed-loop integration test**

Boot the Spring context with H2 and deterministic test `OpsTool` beans. Use the real repositories, `RiskCheckService`, `ToolExecutor`, `InspectionExecutionService`, audit, report, and notification-record persistence.

Assert:

1. one plan creates one execution and one `auditId`;
2. every `RiskCheckRecord.checkedAt` precedes the corresponding `ToolCallRecord.startedAt`;
3. every tool call and risk record shares the execution `auditId`;
4. report references the same audit;
5. no `PendingAction` row exists;
6. abnormal DISK execution persists a notification record when a test channel is enabled.

- [ ] **Step 5: Write LLM-disabled integration**

Run with:

```java
@SpringBootTest(properties = "kylinops.llm.enabled=false")
```

Execute HEALTH, DISK, and SERVICE templates. Assert all reach terminal states without invoking `LlmClient`, `HybridIntentService`, or chat persistence.

- [ ] **Step 6: Run backend integration tests**

```powershell
cd backend
mvn -Dtest=InspectionEndToEndIntegrationTest,InspectionLlmDisabledIntegrationTest test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add frontend/tests/e2e/fixtures.ts frontend/tests/e2e/scheduled-inspection.spec.ts backend/src/test/java/com/kylinops/inspection/InspectionEndToEndIntegrationTest.java backend/src/test/java/com/kylinops/inspection/InspectionLlmDisabledIntegrationTest.java
git commit -m "test(inspection): 覆盖定时巡检闭环"
```

---

### Task 11: Update product, test, and deployment documentation

**Files:**
- Modify: `docs/product/functional-defect-and-roadmap.md`
- Modify: `docs/product/software-requirements-analysis.md`
- Modify: `docs/design/software-design-document.md`
- Modify: `docs/product/product-manual.md`
- Modify: `docs/test/functional-test-report.md`
- Modify: `docs/deploy/install-and-deploy-guide.md`

- [ ] **Step 1: Update roadmap and product contracts**

Mark D-08 complete only after implementation and verification. Document:

- read-only boundary;
- three templates;
- schedule types;
- threshold limits;
- no retry/no catch-up;
- single-admin scope;
- no multi-host claim.

- [ ] **Step 2: Update design/API/data documentation**

Add `/api/inspections/**`, two new tables, the seventh frontend page, scheduling configuration, and inspection notification event types.

- [ ] **Step 3: Update deployment guide**

Document `inspection.allowed-services` and note that systemd/journal/OS tools still degrade gracefully on non-Linux development hosts. Do not claim LoongArch validation unless performed.

- [ ] **Step 4: Update test report with actual counts/results**

Do not copy projected counts. Run tests first and record actual totals and skipped cases.

- [ ] **Step 5: Commit**

```powershell
git add docs/product docs/design docs/test/functional-test-report.md docs/deploy/install-and-deploy-guide.md
git commit -m "docs(inspection): 更新定时巡检交付文档"
```

---

### Task 12: Full verification and safety audit

**Files:**
- Create: `backend/src/test/java/com/kylinops/inspection/InspectionPerformanceBudgetTest.java`
- No other new files unless verification reveals a scoped defect.

- [ ] **Step 1: Add deterministic performance budget tests**

With controlled test tools and a real H2 repository assert:

- HEALTH end-to-end duration `< 30s`;
- DISK and SERVICE duration `< 10s`;
- 100 warmed plan-list queries have measured P95 `< 500ms`;
- a scheduled callback recorded by a latch fires within 5 seconds of its target instant.

These tests verify application orchestration budgets, not LoongArch hardware performance. Record real Linux/LoongArch measurements only if actually run.

- [ ] **Step 2: Run LLM-disabled and performance tests explicitly**

```powershell
cd backend
mvn -Dtest=InspectionLlmDisabledIntegrationTest,InspectionPerformanceBudgetTest test
```

Expected: PASS.

- [ ] **Step 3: Commit performance acceptance**

```powershell
git add backend/src/test/java/com/kylinops/inspection/InspectionPerformanceBudgetTest.java
git commit -m "test(inspection): 添加巡检性能预算"
```

- [ ] **Step 4: Run complete backend tests**

```powershell
cd backend
mvn test
```

Expected: all backend tests pass; only previously documented environment-dependent skips remain.

- [ ] **Step 5: Run backend package build**

```powershell
mvn -B clean package -DskipTests
```

Expected: `target/kylin-ops-guard.jar` created.

- [ ] **Step 6: Run complete frontend verification**

```powershell
cd ..\frontend
npm run test:unit -- --run
npm run build
npm run test:e2e
```

Expected: all existing and new unit/E2E tests pass; only explicitly documented pre-existing skips remain.

- [ ] **Step 7: Run red-line source audit**

```powershell
cd ..
rg -n "/api/(exec|shell|command/run)|ProcessBuilder|SafeExecutor|ActionConfirmService|PendingAction" backend/src/main/java/com/kylinops/inspection
```

Expected:

- no forbidden endpoint;
- no `ProcessBuilder`;
- no dependency on `SafeExecutor`, `ActionConfirmService`, or `PendingAction`.

Then verify every inspection tool invocation is routed through `ToolExecutor` and preceded by `RiskCheckService`.

- [ ] **Step 8: Inspect the final diff**

```powershell
git status --short
git diff --check
git log --oneline --max-count=15
```

Confirm unrelated pre-existing workspace changes were not staged or modified by this feature.

- [ ] **Step 9: Run `@superpowers:requesting-code-review`**

Review against the design spec and the nine KylinOps hard rules. Resolve all blocking findings.

- [ ] **Step 10: Final verification commit if needed**

Only if verification required scoped fixes:

```powershell
git add <only-files-fixed-during-verification>
git commit -m "fix(inspection): 修复巡检验收问题"
```
