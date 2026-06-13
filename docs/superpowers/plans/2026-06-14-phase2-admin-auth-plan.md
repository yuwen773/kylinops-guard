# Phase 2 Single-Administrator Authentication Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Protect all business APIs with one server-side administrator session and bind L2 actions to the authenticated principal/session.

**Architecture:** Use Spring Security 6 with HttpSession, BCrypt, CSRF, and explicit JSON handlers. Add a small Vue auth service and login route; do not add registration, RBAC, Pinia, or JWT.

**Tech Stack:** Spring Security 6, HttpSession, BCrypt, Bucket4j or a small bounded in-memory limiter, Vue Router, Axios, Element Plus, Vitest, Playwright.

---

## File Map

**Create**

- `backend/src/main/java/com/kylinops/auth/AuthProperties.java`
- `backend/src/main/java/com/kylinops/auth/AdminAuthenticationService.java`
- `backend/src/main/java/com/kylinops/auth/AuthController.java`
- `backend/src/main/java/com/kylinops/auth/AuthSessionResponse.java`
- `backend/src/main/java/com/kylinops/auth/LoginRequest.java`
- `backend/src/main/java/com/kylinops/auth/LoginRateLimiter.java`
- `backend/src/main/java/com/kylinops/auth/ApiRateLimiter.java`
- `backend/src/main/java/com/kylinops/auth/AbsoluteSessionExpiryFilter.java`
- `backend/src/main/java/com/kylinops/config/SecurityConfig.java`
- `backend/src/main/java/com/kylinops/executor/ExecutionAttempt.java`
- `backend/src/main/java/com/kylinops/executor/ExecutionAttemptRepository.java`
- `backend/src/main/java/com/kylinops/executor/ExecutionOutcomeRecord.java`
- `backend/src/main/java/com/kylinops/executor/ExecutionOutcomeRepository.java`
- `backend/src/main/java/com/kylinops/executor/ExecutionReconciliationService.java`
- `backend/src/test/java/com/kylinops/auth/AuthControllerTest.java`
- `backend/src/test/java/com/kylinops/auth/SecurityBoundaryIntegrationTest.java`
- `backend/src/test/java/com/kylinops/auth/SessionExpiryAndRateLimitTest.java`
- `backend/src/test/java/com/kylinops/executor/ExecutionAuditFailClosedTest.java`
- `frontend/src/api/auth.ts`
- `frontend/src/types/auth.ts`
- `frontend/src/pages/Login/index.vue`
- `frontend/src/pages/Login/index.spec.ts`
- `frontend/src/auth/session.ts`
- `frontend/tests/e2e/auth.spec.ts`

**Modify**

- `backend/pom.xml`: Spring Security dependency.
- `backend/src/main/java/com/kylinops/config/KylinOpsConfig.java`: auth/security values if not moved entirely to `AuthProperties`.
- `backend/src/main/java/com/kylinops/executor/PendingAction.java`
- `backend/src/main/java/com/kylinops/executor/PendingActionRepository.java`
- `backend/src/main/java/com/kylinops/executor/ActionConfirmService.java`
- `backend/src/main/java/com/kylinops/executor/ActionConfirmController.java`
- `backend/src/main/java/com/kylinops/agent/AgentOrchestrator.java`
- `backend/src/main/java/com/kylinops/audit/AuditLogService.java`
- `backend/src/main/resources/db/migration/V2__execution_audit_schema.sql`
- `frontend/src/api/client.ts`
- `frontend/src/api/client.spec.ts`
- `frontend/src/router/index.ts`
- `frontend/src/App.vue`
- `frontend/src/layouts/AppLayout.vue`
- `frontend/src/layouts/AppLayout.spec.ts`
- `frontend/tests/e2e/fixtures.ts`
- all backend controller tests that currently assume anonymous access.

### Task 1: Add Spring Security and JSON Security Boundaries

- [ ] **Step 1: Write failing anonymous-access integration tests**

Assert:

```java
mockMvc.perform(get("/api/audit/logs"))
       .andExpect(status().isUnauthorized());
mockMvc.perform(get("/api/health"))
       .andExpect(status().isOk());
mockMvc.perform(get("/api/health/live"))
       .andExpect(status().isOk());
```

- [ ] **Step 2: Run and verify failure**

```bash
cd backend
mvn -Dtest=SecurityBoundaryIntegrationTest test
```

Expected: business endpoint is currently 200.

- [ ] **Step 3: Add Spring Security**

Add `spring-boot-starter-security` to `backend/pom.xml`.

- [ ] **Step 4: Implement SecurityConfig**

Required rules:

```java
authorize.requestMatchers(
        "/api/health", "/api/health/live", "/api/auth/login"
).permitAll();
authorize.requestMatchers("/api/**").authenticated();
```

Use JSON authentication entry point and access denied handler returning `ApiResponse` with traceId. Exempt only `/api/auth/login` from CSRF.

- [ ] **Step 5: Run boundary tests**

```bash
cd backend
mvn -Dtest=SecurityBoundaryIntegrationTest test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/pom.xml backend/src/main/java/com/kylinops/config/SecurityConfig.java backend/src/test/java/com/kylinops/auth/SecurityBoundaryIntegrationTest.java
git commit -m "feat(auth): 保护业务 API 边界"
```

### Task 2: Implement Login, Session Rotation, CSRF, and Lockout

- [ ] **Step 1: Write AuthController tests**

Cover:

- valid BCrypt credentials return 200 and administrator summary;
- invalid credentials return 401 without revealing which field failed;
- fifth consecutive failure locks for 15 minutes;
- successful login rotates session ID;
- `GET /api/auth/session` returns CSRF token when authenticated;
- logout returns 204 and invalidates session.

- [ ] **Step 2: Run and verify failure**

```bash
cd backend
mvn -Dtest=AuthControllerTest test
```

Expected: FAIL because auth classes do not exist.

- [ ] **Step 3: Implement focused auth properties**

```java
@ConfigurationProperties("kylinops.auth")
public record AuthProperties(
        String username,
        String passwordHash,
        Duration idleTimeout,
        Duration absoluteTimeout,
        int maxFailures,
        Duration lockDuration) {}
```

- [ ] **Step 4: Implement authentication service and controller**

Use `BCryptPasswordEncoder.matches`, store principal and login timestamp in the server session, call `request.changeSessionId()`, and return the CSRF token from Spring's request attribute.

- [ ] **Step 5: Implement bounded login rate limiter**

Limit per IP to 10 attempts/minute and administrator failures to 5 before a 15-minute lock. Inject `Clock` for deterministic tests.

- [ ] **Step 6: Enforce idle/absolute expiry and Chat rate limit**

Configure Spring session idle timeout to 30 minutes. `AbsoluteSessionExpiryFilter` compares the server-stored login timestamp to the configured 8-hour absolute limit and invalidates expired sessions. `ApiRateLimiter` limits `/api/chat/send` to 30 requests per authenticated session per minute and returns 429 before controller execution.

- [ ] **Step 7: Configure cookie attributes**

Set `HttpOnly=true`, `SameSite=Strict`, `Path=/`, and `Secure=true` in prod; allow `Secure=false` only in dev/test HTTP profiles.

- [ ] **Step 8: Run tests**

```bash
cd backend
mvn -Dtest=AuthControllerTest,SecurityBoundaryIntegrationTest,SessionExpiryAndRateLimitTest test
```

Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/com/kylinops/auth backend/src/main/java/com/kylinops/config/SecurityConfig.java backend/src/test/java/com/kylinops/auth
git commit -m "feat(auth): 添加单管理员会话登录"
```

### Task 3: Bind PendingAction to Authenticated Ownership

- [ ] **Step 1: Write failing ownership tests**

Create two authenticated MockMvc sessions. Create an L2 action in session A; confirm from session B and expect 403. Confirm from A and expect normal execution path.

- [ ] **Step 2: Run and verify failure**

```bash
cd backend
mvn -Dtest=ActionConfirmControllerTest,ActionConfirmServiceTest test
```

Expected: new cross-session test fails because ownership fields do not exist.

- [ ] **Step 3: Map the V2 ownership columns**

Add:

```java
private String creatorPrincipal;
private String creatorAuthSessionId;
```

Do not repurpose chat `sessionId`.

- [ ] **Step 4: Pass server-derived identity**

Change `createAction` to accept an `AuthenticatedOperator` value obtained from `SecurityContext` and `HttpSession`, never request JSON.

- [ ] **Step 5: Enforce ownership atomically**

Change repository claim query to include `creatorPrincipal` and `creatorAuthSessionId` in the WHERE clause.

- [ ] **Step 6: Run ownership tests**

```bash
cd backend
mvn -Dtest=ActionConfirmControllerTest,ActionConfirmServiceTest test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/kylinops/executor backend/src/test/java/com/kylinops/executor
git commit -m "fix(auth): 绑定待确认动作会话归属"
```

### Task 4: Make Audit and Confirmed Execution Fail Closed

- [ ] **Step 1: Write failure-injection tests**

Use mocks/transaction tests to assert:

- audit creation failure means no ToolPlanning/ToolExecutor;
- pre-execution audit update failure means no ToolExecutor;
- L2 pre-execution audit failure means no SafeExecutor;
- outcome persistence failure after side effect returns `INDETERMINATE` and does not retry.

- [ ] **Step 2: Run and verify failure**

```bash
cd backend
mvn -Dtest=AgentOrchestratorSecurityTest,ExecutionAuditFailClosedTest test
```

Expected: at least audit-failure tests fail against current catch-and-continue behavior.

- [ ] **Step 3: Add append-only attempt/outcome entities**

`ExecutionAttempt` status is always `STARTED`. `ExecutionOutcomeRecord` status is one of `SUCCEEDED`, `FAILED`, `DEGRADED`. Do not update attempt rows.

- [ ] **Step 4: Refactor ActionConfirmService**

Flow:

1. claim owned PendingAction;
2. recheck risk;
3. persist attempt;
4. execute SafeExecutor outside transaction;
5. persist outcome + audit + PendingAction final status in one transaction;
6. if step 5 fails, return/throw an `INDETERMINATE` business error and never retry.

- [ ] **Step 5: Add reconciliation query**

Repository method finds attempts older than threshold with no outcome. Service exposes diagnostics only; it must not execute actions.

- [ ] **Step 6: Run fail-closed tests**

```bash
cd backend
mvn -Dtest=AgentOrchestratorSecurityTest,ExecutionAuditFailClosedTest,ActionConfirmServiceTest test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/kylinops/agent/AgentOrchestrator.java backend/src/main/java/com/kylinops/executor backend/src/main/java/com/kylinops/audit backend/src/test/java/com/kylinops/agent backend/src/test/java/com/kylinops/executor
git commit -m "fix(audit): 执行前审计失败闭锁"
```

### Task 5: Add Frontend Login and Session-Aware API Client

- [ ] **Step 1: Write failing API client tests**

Assert:

- Axios uses `withCredentials: true`.
- mutating requests include current `X-CSRF-TOKEN`.
- 401 clears auth state and invokes redirect hook.

- [ ] **Step 2: Run and verify failure**

```bash
cd frontend
npm run test:unit -- --run src/api/client.spec.ts
```

Expected: FAIL.

- [ ] **Step 3: Implement auth API and session module**

`auth.ts` exports `login`, `logout`, `getSession`. `session.ts` stores only in-memory auth summary and CSRF token; never persist password or session cookie.

- [ ] **Step 4: Implement login page**

Use a compact Element Plus form with username/password, submit loading, generic error, and no registration links.

- [ ] **Step 5: Add router guard and layout logout**

Mark `/login` public. Guard all other routes by calling `getSession()` once. Update `App.vue` so login renders without `AppLayout`.

- [ ] **Step 6: Run unit tests**

```bash
cd frontend
npm run test:unit -- --run
npm run build
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add frontend/src frontend/tests/e2e/fixtures.ts
git commit -m "feat(ui): 添加管理员登录流程"
```

### Task 6: Update E2E Fixtures and Verify Authenticated Demo

- [ ] **Step 1: Add Playwright auth fixture**

Login through `/api/auth/login` for live tests and inject authenticated API responses for mock tests.

- [ ] **Step 2: Add auth E2E tests**

Cover anonymous redirect, successful login, navigation, logout, and CSRF rejection.

- [ ] **Step 3: Run unit and mock E2E**

```bash
cd frontend
npm run test:unit -- --run
npm run test:e2e
```

Expected: PASS.

- [ ] **Step 4: Run backend full suite**

```bash
cd backend
mvn -B clean test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/tests backend/src/test
git commit -m "test(auth): 覆盖登录与会话安全"
```
