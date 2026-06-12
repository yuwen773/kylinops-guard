# KylinOps Guard — Frontend

Vue 3 + TypeScript + Vite frontend for the **麒麟安全智能运维 Agent** demo.

This is the **Phase 2** deliverable: six pages (ChatConsole / Dashboard / ToolCenter /
SecurityCenter / AuditLog / ReportCenter), test harness, build pipeline and API proxy. Acceptance
evidence lives in [`../docs/test/phase2-demo-acceptance.md`](../docs/test/phase2-demo-acceptance.md).

## Stack

- Vue 3 + TypeScript + Vite
- Element Plus (UI components)
- Axios (HTTP client)
- Vue Router 4
- Vitest + @vue/test-utils + jsdom (unit tests)
- Playwright (E2E tests, default + opt-in `E2E_LIVE`)

## Scripts

```bash
npm ci                # clean install from package-lock.json
npm run dev           # Vite dev server on http://127.0.0.1:5173
npm run build         # vue-tsc --noEmit + vite build → dist/
npm run preview       # serve the built bundle on :5173
npm run test:unit     # Vitest unit tests (interactive by default; use --run for CI)
npm run test:e2e      # Playwright E2E (see "E2E" below)
```

### Unit tests

For one-shot runs (CI / smoke) use:

```bash
npm run test:unit -- --run
```

### E2E tests (Playwright)

The first time, install the browser binary:

```bash
npx playwright install chromium
```

`playwright.config.ts` ships a `webServer` block that auto-starts `npm run dev` on
`http://127.0.0.1:5173` (`reuseExistingServer: true`). Viewport is locked to **1280×720** —
that is the demo video resolution.

Two modes:

```bash
# 1. Default — route-intercepted, no backend required.
#    Covers navigation.spec (six pages) + demo-flows.spec (four scenarios with fixtures).
npm run test:e2e

# 2. Live backend smoke — only runs when E2E_LIVE=true.
#    The Spring Boot backend must already be running on http://127.0.0.1:8080.
#    Covers demo-live.spec (real /api/health, real /api/chat/send, real audit deep-link).
$env:E2E_LIVE = 'true'                # PowerShell
npx playwright test tests/e2e/demo-live.spec.ts
```

Retries are disabled (`retries: 0`): a single failed assertion surfaces a regression.

## API Proxy

`vite.config.ts` proxies `/api/*` to `http://127.0.0.1:8080` (Spring Boot backend).
Application code reads the base URL via `import.meta.env.VITE_API_BASE_URL ?? '/api'`,
so the same code works behind the Vite proxy in dev and behind a reverse proxy in production.

## Hard Rules Reminder

- The frontend MUST NOT lower any risk decision returned by the backend.
- All state-changing actions must come from backend decisions; the UI only confirms L2 pending
  actions via `/api/actions/confirm`.
- User-visible text is Chinese; code identifiers and comments are English.
- No raw shell, no `/api/exec`, no `/api/shell`, no `/api/command/run` — ever.

## Acceptance Status

| Item | Status |
|---|---|
| Six pages implemented | Done (Task 02, 13–17) |
| Quick-action buttons (5 + 1) on ChatConsole | Done (matches `演示视频脚本 §3.2`) |
| Playwright E2E (mock + live) | Done (Task 14) |
| Forbidden endpoint scan | Clean (Task 15) |
| Live demo smoke on Windows | See `docs/test/phase2-demo-acceptance.md` §5 |
| Live demo smoke on Kylin/LoongArch | **待验证** (deferred to Task 20/21) |