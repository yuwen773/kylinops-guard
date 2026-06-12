# KylinOps Guard — Frontend

Vue 3 + TypeScript + Vite frontend for the **麒麟安全智能运维 Agent** demo.

This is the **Phase 2 baseline** scaffold: tooling, test harness, build pipeline and API proxy only.
Business pages (ChatConsole / Dashboard / ToolCenter / SecurityCenter / AuditLog / ReportCenter) are
implemented in later Phase 2 tasks (Task 02 / 13–17).

## Stack

- Vue 3 + TypeScript + Vite
- Element Plus (UI components)
- Axios (HTTP client)
- Vue Router 4
- Vitest + @vue/test-utils + jsdom (unit tests)
- Playwright (E2E tests)

## Scripts

```bash
npm install
npm run dev           # Vite dev server on http://127.0.0.1:5173
npm run build         # type-check + production bundle to dist/
npm run preview       # serve the built bundle on :5173
npm run test:unit     # Vitest unit tests
npm run test:e2e      # Playwright E2E tests (requires dev server)
```

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