# KylinOps Guard Production and LLM Hardening Master Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the approved production hardening, single-admin authentication, configurable DeepSeek/Qwen integration, dual-database support, and LoongArch acceptance within 15 working days.

**Architecture:** Execute four independently testable phase plans in order. Each phase preserves the existing ToolRegistry -> RiskCheck -> SafeExecutor -> Audit safety authority and must leave the application deployable before the next phase begins.

**Tech Stack:** Java 17, Spring Boot 3.3, Spring Security 6, Spring RestClient, Spring Data JPA, Flyway, H2, PostgreSQL, Vue 3, TypeScript, Axios, Vitest, Playwright, Nginx, systemd.

---

## Plan Suite

1. [Phase 1: Runtime and Database Baseline](2026-06-14-phase1-runtime-database-plan.md)
2. [Phase 2: Single-Administrator Authentication](2026-06-14-phase2-admin-auth-plan.md)
3. [Phase 3: OpenAI-Compatible LLM Enhancement](2026-06-14-phase3-llm-integration-plan.md)
4. [Phase 4: LoongArch Deployment and Acceptance](2026-06-14-phase4-loongarch-acceptance-plan.md)

The source design is [2026-06-13-production-llm-hardening-design.md](../specs/2026-06-13-production-llm-hardening-design.md).

## Execution Rules

- Implement in a dedicated git worktree created with `superpowers:using-git-worktrees`.
- Use `superpowers:test-driven-development` for every behavior change.
- Run `superpowers:verification-before-completion` before each phase completion claim.
- Do not add real-effect actions beyond `safe_service_restart`.
- Do not let LLM output directly produce a ToolPlan, RiskDecision, command, or confirmation.
- Do not include `.tmp-install-node.sh` or other unrelated working-tree files in commits.
- Use focused Chinese Conventional Commits.

## Phase Gates

### Gate 1: Runtime and Database

- Backend unit/integration tests pass.
- H2 fresh migration, H2 legacy upgrade, and PostgreSQL migration pass.
- Command timeout returns by `timeout + 1s`.
- `/api/health` compatibility remains intact.
- Production startup fails closed when required configuration is absent.

### Gate 2: Authentication

- Anonymous business API calls return 401.
- Login creates a rotated server session.
- Authenticated mutations require CSRF.
- Cross-auth-session PendingAction confirmation is blocked.
- Audit failure prevents ToolExecutor and SafeExecutor invocation.

### Gate 3: LLM

- DeepSeek and Qwen pass the same OpenAI-compatible contract tests.
- Prompt injection is checked before any model call.
- BLOCK, CONFIRM, general chat, and no-tool requests use templates.
- Every production tool has an LLM context policy.
- Model failure preserves the four baseline scenarios.

### Gate 4: LoongArch

- Kylin V11/LoongArch environment matrix is recorded.
- PostgreSQL, Nginx, systemd, and migrations work on target.
- Four core scenarios, auth, both models, fallback, restart, backup/restore, and 10-concurrent smoke pass.
- Evidence is committed under `docs/test/evidence/<date>-loongarch/`.

## Schedule

| Phase | Mandatory duration | Buffer |
| --- | ---: | ---: |
| Runtime/database | 3 days | 1 day |
| Authentication | 3 days | 1 day |
| LLM integration | 4 days | 1 day |
| LoongArch acceptance | 2 days | 0 days |
| **Total** | **12 days** | **3 days maximum** |

## Final Verification

- [ ] Run backend full suite:

```bash
cd backend
mvn -B clean test
```

Expected: all backend tests pass.

- [ ] Run frontend full suite and build:

```bash
cd frontend
npm ci
npm run test:unit -- --run
npm run build
npm run test:e2e
```

Expected: unit tests, type checking, production build, and mock E2E pass.

- [ ] Run live smoke with authenticated backend:

```bash
cd frontend
npm run test:e2e -- demo-live.spec.ts
```

Expected: login and three live backend smoke flows pass.

- [ ] Confirm working tree contains only intended changes:

```bash
git status --short
git diff --check
```

Expected: no accidental files and no whitespace errors.
