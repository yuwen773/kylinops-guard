# Phase 1 Runtime and Database Baseline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make process execution bounded, production configuration fail-closed, and the schema reproducible on H2 and PostgreSQL without breaking existing health and demo behavior.

**Architecture:** Isolate process execution configuration from business tools, introduce Flyway V1/V2 migrations, and split dev/test/prod profiles. Keep repositories and entities unchanged until migration tests establish the legacy baseline.

**Tech Stack:** Java 17, Spring Boot 3.3, JUnit 5, Mockito, Flyway, H2 PostgreSQL Mode, PostgreSQL/Testcontainers, Maven.

---

## File Map

**Create**

- `backend/src/main/java/com/kylinops/config/RuntimeProperties.java`: bounded command-executor settings.
- `backend/src/main/java/com/kylinops/config/ProductionConfigValidator.java`: fail-closed prod startup checks.
- `backend/src/main/java/com/kylinops/chat/ReadinessService.java`: database/rule readiness checks.
- `backend/src/main/resources/application-dev.yml`
- `backend/src/main/resources/application-test.yml`
- `backend/src/main/resources/application-prod.yml`
- `backend/src/main/resources/db/migration/V1__legacy_schema.sql`
- `backend/src/main/resources/db/migration/V2__execution_audit_schema.sql`
- `backend/src/test/java/com/kylinops/os/OsCommandExecutorConcurrencyTest.java`
- `backend/src/test/java/com/kylinops/executor/SafeExecutorActionRegistryTest.java`
- `backend/src/test/java/com/kylinops/config/ProductionConfigValidatorTest.java`
- `backend/src/test/java/com/kylinops/config/ProfileConfigurationTest.java`
- `backend/src/test/java/com/kylinops/common/GlobalExceptionHandlerTest.java`
- `backend/src/test/java/com/kylinops/config/WebConfigTest.java`
- `backend/src/test/java/com/kylinops/migration/FlywayH2MigrationTest.java`
- `backend/src/test/java/com/kylinops/migration/FlywayPostgresMigrationTest.java`
- `backend/src/test/java/com/kylinops/migration/LegacyH2UpgradeTest.java`
- `backend/src/test/resources/legacy/kylinops-v0.1.mv.db`: sanitized representative legacy fixture.

**Modify**

- `backend/pom.xml`: Spring JDBC/Flyway/PostgreSQL/Testcontainers dependencies.
- `backend/src/main/resources/application.yml`: shared safe defaults only.
- `backend/src/main/resources/application-h2.yml`: deprecate or redirect to dev profile.
- `backend/src/test/resources/application.yml`: activate test profile and Flyway.
- `backend/src/main/java/com/kylinops/os/OsCommandExecutor.java`: concurrent stream drains, hard timeout, descendant cleanup.
- `backend/src/main/java/com/kylinops/config/KylinOpsConfig.java`: remove runtime settings that move to focused properties.
- `backend/src/main/java/com/kylinops/config/WebConfig.java`: profile-scoped CORS origins.
- `backend/src/main/java/com/kylinops/common/GlobalExceptionHandler.java`: stable sanitized 500 response.
- `backend/src/main/java/com/kylinops/chat/HealthController.java`: preserve `/api/health`, add `/live` and `/ready`.
- `backend/src/main/java/com/kylinops/tool/MockTool.java`: dev/test profile only.
- `backend/src/main/java/com/kylinops/tool/FailingMockTool.java`: dev/test profile only.
- `.github/workflows/ci.yml`: PostgreSQL migration job.
- `docs/deploy/install-and-deploy-guide.md`: profiles and legacy H2 migration.

### Task 1: Add Migration and PostgreSQL Dependencies

- [ ] **Step 1: Write a failing context test for Flyway**

Create `FlywayH2MigrationTest` with:

```java
@SpringBootTest(properties = {
        "spring.profiles.active=test",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class FlywayH2MigrationTest {
    @Autowired JdbcTemplate jdbc;

    @Test
    void flywayCreatesLegacyTables() {
        Integer count = jdbc.queryForObject(
                "select count(*) from flyway_schema_history", Integer.class);
        assertThat(count).isPositive();
    }
}
```

- [ ] **Step 2: Run the test and verify it fails**

Run:

```bash
cd backend
mvn -Dtest=FlywayH2MigrationTest test
```

Expected: FAIL because Flyway/JdbcTemplate migration setup does not exist.

- [ ] **Step 3: Add dependencies**

Modify `backend/pom.xml`:

```xml
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-database-postgresql</artifactId>
</dependency>
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>postgresql</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 4: Add V1 migration from current entities**

Create `V1__legacy_schema.sql` from the current entity contract. The migration must contain exactly these legacy tables:

| Table | Entity source |
| --- | --- |
| `kylin_session` | `chat/Session.java` |
| `kylin_message` | `chat/Message.java` |
| `kylin_audit_log` | `audit/AuditLog.java` |
| `kylin_tool_definition` | `tool/ToolDefinition.java` |
| `kylin_tool_call_record` | `tool/ToolCallRecord.java` |
| `kylin_risk_check_record` | `security/RiskCheckRecord.java` |
| `kylin_pending_action` | `executor/PendingAction.java` |
| `kylin_report` | `report/Report.java` |

Before writing V1, run the existing application once against a disposable H2 database with Hibernate schema creation enabled, export `SCRIPT NODATA`, and compare every column/constraint against the eight entity files. Normalize only type syntax for H2/PostgreSQL portability. Do not add auth or execution-attempt columns to V1.

- [ ] **Step 5: Configure tests to use Flyway and validation**

Set in `application-test.yml`:

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:kylinops-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1
  flyway:
    enabled: true
  jpa:
    hibernate:
      ddl-auto: validate
```

- [ ] **Step 6: Run the migration test**

Run:

```bash
cd backend
mvn -Dtest=FlywayH2MigrationTest test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/pom.xml backend/src/main/resources/db backend/src/main/resources/application-test.yml backend/src/test/java/com/kylinops/migration/FlywayH2MigrationTest.java
git commit -m "build(db): 引入 Flyway 基线迁移"
```

### Task 2: Verify PostgreSQL and Legacy H2 Upgrade

- [ ] **Step 1: Write PostgreSQL migration test**

Use Testcontainers with `@DynamicPropertySource` and assert V1 migration plus JPA validation.

- [ ] **Step 2: Run it and verify failure before PostgreSQL-specific fixes**

```bash
cd backend
mvn -Dtest=FlywayPostgresMigrationTest test
```

Expected: FAIL on any non-portable SQL or missing Flyway PostgreSQL support.

- [ ] **Step 3: Make V1 portable**

Use portable types (`bigint generated by default as identity`, `varchar`, `text`, `timestamp`) and avoid H2-only clauses.

- [ ] **Step 4: Add the legacy fixture and upgrade test**

The test must:

1. Copy the sanitized v0.1 H2 file to a temporary directory.
2. Validate its schema fingerprint against the frozen V1 definition.
3. Baseline at version 1.
4. Run V2+ migrations.
5. Assert existing audit/report rows remain.

- [ ] **Step 5: Add V2 execution audit tables**

In V2, add `creator_principal` and `creator_auth_session_id` to `kylin_pending_action`, then create `kylin_execution_attempt` and `kylin_execution_outcome`. The attempt table requires `id`, unique `attempt_id`, `audit_id`, `action_id`, `action_type`, `target_summary`, `started_at`, and `created_at`. The outcome table requires `id`, unique `outcome_id`, unique `attempt_id`, `status`, `summary`, `evidence_json`, `finished_at`, and `created_at`. Use foreign keys only where both H2 and PostgreSQL upgrade fixtures prove they do not break legacy data.

- [ ] **Step 6: Run both migration tests**

```bash
cd backend
mvn -Dtest=FlywayPostgresMigrationTest,LegacyH2UpgradeTest test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/resources/db backend/src/test/java/com/kylinops/migration backend/src/test/resources/legacy
git commit -m "test(db): 覆盖 PostgreSQL 与旧 H2 升级"
```

### Task 3: Split Runtime Profiles and Fail Closed

- [ ] **Step 1: Write failing profile tests**

Test:

- dev enables H2 and optional console.
- test uses in-memory H2.
- prod requires PostgreSQL URL/user/password and admin BCrypt hash.
- prod never falls back to H2.

- [ ] **Step 2: Run and verify failure**

```bash
cd backend
mvn -Dtest=ProfileConfigurationTest,ProductionConfigValidatorTest test
```

Expected: FAIL because profiles and validator do not exist.

- [ ] **Step 3: Move configuration into profiles**

Keep `application.yml` limited to shared application/Jackson/limits settings. Put H2 credentials and console only in `application-dev.yml`; put PostgreSQL placeholders and `ddl-auto: validate` in `application-prod.yml`.

- [ ] **Step 4: Implement validator**

`ProductionConfigValidator` should run on `ApplicationReadyEvent` or implement `InitializingBean`, activate only under `prod`, and throw `IllegalStateException` for missing required values.

- [ ] **Step 5: Profile Mock tools**

Add:

```java
@Profile({"dev", "test"})
@Component
public class MockTool implements OpsTool {
    // Keep the existing implementation unchanged.
}
```

Apply the same to `FailingMockTool`.

- [ ] **Step 6: Run profile tests and current registration test**

```bash
cd backend
mvn -Dtest=ProfileConfigurationTest,ProductionConfigValidatorTest,OsToolRegistrationTest test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/resources/application*.yml backend/src/main/java/com/kylinops/config backend/src/main/java/com/kylinops/tool/MockTool.java backend/src/main/java/com/kylinops/tool/FailingMockTool.java backend/src/test/java/com/kylinops/config
git commit -m "build(config): 拆分安全运行配置"
```

### Task 4: Rebuild OsCommandExecutor with Hard Bounds

- [ ] **Step 1: Write process stress tests**

Create platform-aware helper commands that:

- emit endless stdout;
- emit endless stderr;
- emit both streams;
- spawn a child process;
- exceed configured queue capacity.

Assert timeout returns by `timeoutMs + 1000`, both streams truncate at configured caps, and descendants terminate.

- [ ] **Step 2: Run and verify existing implementation fails**

```bash
cd backend
mvn -Dtest=OsCommandExecutorConcurrencyTest test
```

Expected: at least the endless-output or dual-stream test times out/fails.

- [ ] **Step 3: Add focused properties**

`RuntimeProperties` fields:

```java
private int maxProcesses = 8;
private int queueCapacity = 32;
private int maxLinesPerStream = 1000;
private int maxBytesPerStream = 1_048_576;
private Duration gracefulKill = Duration.ofMillis(250);
private Duration cleanupBudget = Duration.ofSeconds(1);
```

- [ ] **Step 4: Implement concurrent drains**

Use a fixed process executor and separate stream executor. Start both drains immediately after `ProcessBuilder.start()`, wait on process completion from the start deadline, then clean descendants with `ProcessHandle.descendants()`.

- [ ] **Step 5: Add overload result**

Extend `CommandResult` with stable error code `TOOL_EXECUTOR_OVERLOADED`; never use an unbounded executor or caller-runs policy.

- [ ] **Step 6: Run process and OS tool tests**

```bash
cd backend
mvn -Dtest=OsCommandExecutorConcurrencyTest,OsToolEdgeCaseTest,ServiceDiagnosticToolTest,SafeExecutorTest test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/kylinops/os/OsCommandExecutor.java backend/src/main/java/com/kylinops/config/RuntimeProperties.java backend/src/test/java/com/kylinops/os
git commit -m "fix(runtime): 修复命令流阻塞与超时"
```

### Task 5: Lock the Production Action Surface

- [ ] **Step 1: Write the failing action-registry test**

Enumerate `ExecutionPolicy` and the `SafeExecutor.execute` dispatcher. Assert the only action that may produce a real system side effect is `safe_service_restart`; the three cleanup actions must end in `_preview`.

- [ ] **Step 2: Run the test**

```bash
cd backend
mvn -Dtest=SafeExecutorActionRegistryTest test
```

Expected: PASS against the current dispatcher. This becomes a permanent regression tripwire.

- [ ] **Step 3: Add endpoint-surface assertion**

Scan Spring request mappings in a context test and fail if `/api/exec`, `/api/shell`, `/api/command/run`, or equivalent arbitrary-command endpoints appear.

- [ ] **Step 4: Run safety tests**

```bash
cd backend
mvn -Dtest=SafeExecutorActionRegistryTest,SafeExecutorTest,RiskRuleEngineTest test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/test/java/com/kylinops/executor/SafeExecutorActionRegistryTest.java
git commit -m "test(security): 锁定生产动作白名单"
```

### Task 6: Harden HTTP Errors, CORS, and Health

- [ ] **Step 1: Write failing controller tests**

Add assertions:

- `/api/health` remains HTTP 200 with `data.status=UP`.
- `/api/health/live` is HTTP 200.
- `/api/health/ready` is 200 only when DB and rules are ready.
- unhandled exceptions return generic Chinese text plus traceId, not `ex.getMessage()`.
- dev CORS allows only the two Vite origins.

- [ ] **Step 2: Run and verify failure**

```bash
cd backend
mvn -Dtest=KylinOpsApplicationTests,GlobalExceptionHandlerTest,WebConfigTest test
```

Expected: FAIL for new endpoints and sanitized error/CORS behavior.

- [ ] **Step 3: Implement readiness and health endpoints**

Keep existing `health()` unchanged for compatibility; add dedicated `live()` and `ready()` methods using `ReadinessService`.

- [ ] **Step 4: Sanitize internal exceptions**

Return:

```java
return ApiResponse.error(500, "服务器内部错误").traceId(traceId);
```

Keep full exception only in server logs.

- [ ] **Step 5: Make CORS profile-driven**

Bind allowed origins from configuration; use no wildcard with credentials.

- [ ] **Step 6: Run the tests**

```bash
cd backend
mvn -Dtest=KylinOpsApplicationTests,GlobalExceptionHandlerTest,WebConfigTest test
```

Expected: PASS.

- [ ] **Step 7: Update CI and deployment docs**

Add PostgreSQL service/Testcontainers-compatible job and profile/migration instructions.

- [ ] **Step 8: Run full backend verification**

```bash
cd backend
mvn -B clean test
```

Expected: full suite passes.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main backend/src/test .github/workflows/ci.yml docs/deploy/install-and-deploy-guide.md
git commit -m "feat(runtime): 完成生产基线加固"
```
