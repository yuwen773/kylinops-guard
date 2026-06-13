# Phase 4 LoongArch Deployment and Acceptance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce a repeatable Kylin V11/LoongArch64 production deployment and evidence-backed acceptance for runtime, auth, LLM, database, safety, and recovery.

**Architecture:** Package the existing Spring Boot JAR and Vue dist behind Nginx, run the backend under systemd with a protected prod environment file, and execute scripted/manual acceptance without installing browser automation on LoongArch.

**Tech Stack:** Kylin Advanced Server V11, LoongArch64 JDK 17, PostgreSQL, Nginx, systemd, curl, jq, shell.

---

## File Map

**Create**

- `deploy/config/application-prod.yml`
- `deploy/config/kylinops.env.example`
- `deploy/systemd/kylinops-guard.service`
- `deploy/nginx/kylinops-guard.conf`
- `deploy/scripts/migrate-legacy-h2.sh`
- `deploy/scripts/backup-postgres.sh`
- `deploy/scripts/restore-postgres.sh`
- `deploy/scripts/acceptance-smoke.sh`
- `docs/test/evidence/README.md`

**Modify**

- `deploy/scripts/check-env.sh`
- `deploy/scripts/start-backend.sh`
- `deploy/scripts/update.sh`
- `.github/workflows/ci.yml`
- `docs/deploy/kylin-loongarch-deploy-guide.md`
- `docs/deploy/environment-checklist.md`
- `docs/test/functional-test-report.md`
- `docs/test/performance-test-report.md`
- `docs/test/security-test-cases.md`
- `frontend/tests/e2e/demo-live.spec.ts`

### Task 1: Build Production Deployment Artifacts

- [ ] **Step 1: Write shell syntax checks**

Run:

```bash
bash -n deploy/scripts/*.sh
```

Expected before creation: missing new scripts.

- [ ] **Step 2: Add systemd unit**

Required properties:

```ini
[Service]
User=kylinops
Group=kylinops
EnvironmentFile=/etc/kylinops/kylinops.env
ExecStart=/usr/bin/java -jar /opt/kylinops/kylin-ops-guard.jar --spring.profiles.active=prod
Restart=on-failure
NoNewPrivileges=true
PrivateTmp=true
```

Do not grant root or unrestricted filesystem write access.

- [ ] **Step 3: Add Nginx config**

Serve frontend dist, proxy `/api/` to `127.0.0.1:8080`, set TLS placeholders, request-size limits, and security headers.

- [ ] **Step 4: Add env template**

Include variable names only, never secrets:

```bash
DB_URL=
DB_USERNAME=
DB_PASSWORD=
ADMIN_USERNAME=
ADMIN_PASSWORD_HASH=
LLM_ENABLED=false
LLM_BASE_URL=
LLM_API_KEY=
LLM_MODEL=
```

- [ ] **Step 5: Update package workflow**

Package systemd, Nginx, prod config, migrations, and scripts; do not package dev H2 credentials as production config.

- [ ] **Step 6: Verify shell/config syntax**

```bash
bash -n deploy/scripts/*.sh
nginx -t -c "$PWD/deploy/nginx/kylinops-guard.conf"
```

Expected: scripts valid; Nginx config valid in an environment with Nginx.

- [ ] **Step 7: Commit**

```bash
git add deploy .github/workflows/ci.yml
git commit -m "build(deploy): 添加麒麟生产部署配置"
```

### Task 2: Add Backup, Restore, Migration, and Acceptance Scripts

- [ ] **Step 1: Implement safe PostgreSQL backup**

Use `pg_dump --format=custom`, timestamped output, restrictive permissions, and non-zero exit propagation.

- [ ] **Step 2: Implement restore**

Require explicit target database and confirmation flag; never delete arbitrary paths.

- [ ] **Step 3: Implement legacy H2 migration helper**

The script must:

1. stop service;
2. copy H2 files to timestamped backup;
3. compute SHA-256;
4. run schema fingerprint tool;
5. baseline only on exact match;
6. start app and check readiness;
7. print rollback command.

- [ ] **Step 4: Implement authenticated smoke**

`acceptance-smoke.sh` should:

- check `/api/health`, `/live`, `/ready`;
- login and preserve Cookie/CSRF;
- run health, disk, restart-confirm, and dangerous-command flows;
- assert auditId and expected risk decision;
- never auto-confirm a non-whitelisted service.

- [ ] **Step 5: Run shell lint**

```bash
bash -n deploy/scripts/*.sh
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add deploy/scripts
git commit -m "feat(deploy): 添加迁移备份与验收脚本"
```

### Task 3: Execute Target Environment Matrix and Phase Gates

- [ ] **Step 1: Create evidence directory**

```bash
EVIDENCE=docs/test/evidence/$(date +%F)-loongarch
mkdir -p "$EVIDENCE"
```

- [ ] **Step 2: Record versions**

Capture:

```bash
uname -a
cat /etc/os-release
java -version
psql --version
nginx -v
systemctl --version
```

Redact hostnames/IPs if required.

- [ ] **Step 3: Run runtime/database gate**

Execute migration twice, command timeout smoke, and health endpoints. Expected: idempotent migration, timeout within declared budget, compatibility health 200.

- [ ] **Step 4: Run authentication gate**

Expected:

- anonymous audit request 401;
- authenticated request 200;
- mutation without CSRF 403;
- session B cannot confirm session A action.

- [ ] **Step 5: Run LLM gate**

Execute one real DeepSeek request, one real Qwen request, then invalid-key fallback. Save only model name, timing, status, and sanitized output.

- [ ] **Step 6: Run safety gate**

Verify production action registry contains only `safe_service_restart` as real-effect action and dangerous commands remain BLOCK.

- [ ] **Step 7: Commit evidence**

```bash
git add docs/test/evidence
git commit -m "test(loongarch): 记录阶段验收证据"
```

### Task 4: Verify Recovery and Limited Concurrency

- [ ] **Step 1: Backup PostgreSQL**

Run backup script and record checksum.

- [ ] **Step 2: Restore into a separate validation database**

Never overwrite production during the test. Compare audit/report row counts and a sample detail.

- [ ] **Step 3: Restart recovery**

Restart backend and PostgreSQL separately. Expected: systemd recovers backend, readiness remains false until DB returns, then becomes true.

- [ ] **Step 4: Test INDETERMINATE reconciliation**

Using a controlled test profile/fault injection, leave one attempt without outcome. Verify diagnostics find it and no action auto-retries.

- [ ] **Step 5: Run 10-concurrent smoke**

Use a bounded shell loop against authenticated read-only health checks. Expected: no executor starvation, no 5xx, and response time remains within documented budget.

- [ ] **Step 6: Commit recovery evidence**

```bash
git add docs/test/evidence
git commit -m "test(loongarch): 验证恢复与有限并发"
```

### Task 5: Update Final Documentation and Run Release Verification

- [ ] **Step 1: Update deployment guide**

Replace all target-machine “待验证” statements only where evidence exists. Keep unsupported Playwright-on-LoongArch claims as not applicable.

- [ ] **Step 2: Update functional, performance, and security reports**

Link exact evidence paths and include dates, versions, commands, thresholds, and pass/fail results.

- [ ] **Step 3: Run backend verification**

```bash
cd backend
mvn -B clean test
mvn -B clean package -DskipTests
```

Expected: tests pass and JAR builds.

- [ ] **Step 4: Run frontend verification**

```bash
cd frontend
npm ci
npm run test:unit -- --run
npm run build
npm run test:e2e
```

Expected: PASS.

- [ ] **Step 5: Run target smoke**

```bash
bash deploy/scripts/acceptance-smoke.sh
```

Expected: all mandatory checks PASS.

- [ ] **Step 6: Commit final reports**

```bash
git add docs/deploy docs/test README.md CLAUDE.md
git commit -m "docs: 完成麒麟真机验收回填"
```
