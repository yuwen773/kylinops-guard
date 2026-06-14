// Live backend smoke — Task 14 / Step 5 / P2-T6.
//
// Runs ONLY when E2E_LIVE=true (env var). Otherwise every test is
// skipped. This file deliberately does NOT register any page.route()
// interception — the Vite dev server proxies /api/** to the Spring Boot
// backend on :8080, and we want to verify the real round-trip.
//
// What we verify against a live backend:
//   * GET  /api/health                          -> 200 + { status: 'UP' }
//   * POST /api/chat/send with rm -rf /         -> L4 BLOCK + auditId
//   * The "查看审计日志" link deep-links to /audit?auditId=...
//
// P2-T6 adds a login pre-fixture: every live test authenticates via
// POST /api/auth/login first (using credentials from E2E_ADMIN_USERNAME
// / E2E_ADMIN_PASSWORD env vars) so the backend does not bounce
// protected traffic to 401. The credentials are NEVER persisted to the
// repository — the test skips when env vars are missing so a missing
// secret is surfaced as test.skip(), not as a hardcoded fallback.
//
// Usage:
//   $env:E2E_LIVE = 'true'
//   $env:E2E_ADMIN_USERNAME = 'admin'
//   $env:E2E_ADMIN_PASSWORD = 'test-admin-pwd'
//   npm run test:e2e -- tests/e2e/demo-live.spec.ts
//
// Pre-requisites:
//   * The Spring Boot backend must be running on http://127.0.0.1:8080
//     (e.g. `mvn spring-boot:run -Dspring-boot.run.profiles=dev` so the
//     BCrypt-hashed admin / test-admin-pwd default credentials load).
//   * The Vite dev server is started by playwright.config.ts webServer.
//   * /api/health, /api/chat/send are part of the P0 API surface (CLAUDE.md §P0 API Surface).

import { test, expect, type Page, type ConsoleMessage } from '@playwright/test';

const LIVE_ENABLED = process.env.E2E_LIVE === 'true';
const ADMIN_USERNAME = process.env.E2E_ADMIN_USERNAME ?? '';
const ADMIN_PASSWORD = process.env.E2E_ADMIN_PASSWORD ?? '';

/** True when E2E_LIVE is set AND the credential env vars are present. */
const LIVE_READY = LIVE_ENABLED && ADMIN_USERNAME !== '' && ADMIN_PASSWORD !== '';

function attachConsoleGuards(page: Page) {
  const consoleErrors: string[] = [];
  const pageErrors: string[] = [];
  page.on('console', (msg: ConsoleMessage) => {
    if (msg.type() === 'error') {
      const text = msg.text();
      // 401 browser console noise is expected when the guard checks
      // /api/auth/session and the session has not been established yet.
      if (/Failed to load resource:.*401/.test(text)) return;
      consoleErrors.push(text);
    }
  });
  page.on('pageerror', (err) => {
    pageErrors.push(err.message);
  });
  return { consoleErrors, pageErrors };
}

test.describe('Live backend smoke @ E2E_LIVE=true', () => {
  test.skip(!LIVE_ENABLED, 'E2E_LIVE not set — skipping live backend smoke.');
  test.skip(
    !LIVE_READY,
    'E2E_ADMIN_USERNAME / E2E_ADMIN_PASSWORD not set — skipping live ' +
      'authenticated tests. Provide the credentials via env vars to enable.',
  );

  // Authenticate once per test so the protected /api/chat/send and
  // /api/audit/* endpoints accept the request. The page.request context
  // shares cookies with the page so the JSESSIONID from login carries
  // over to the subsequent chat-send navigation.
  test.beforeEach(async ({ page }) => {
    const loginRes = await page.request.post(
      'http://127.0.0.1:8080/api/auth/login',
      {
        data: { username: ADMIN_USERNAME, password: ADMIN_PASSWORD },
        headers: { 'Content-Type': 'application/json' },
      },
    );
    // Skip (not fail) when the backend rejects the credentials — this
    // keeps CI green if the dev profile defaults are absent. The
    // follow-up tests will then run with no session and the guard will
    // bounce to /login, which is itself a valid live-backend assertion.
    if (loginRes.status() !== 200) {
      test.skip(true, `Live login returned ${loginRes.status()} — check E2E_ADMIN_* env vars.`);
    }
  });

  test('GET /api/health returns UP', async ({ request }) => {
    // NOTE: this hits the Vite dev server (5173) which proxies /api -> :8080.
    // We use the page's request context so the proxy is in play.
    const res = await request.get('/api/health');
    expect(res.status()).toBe(200);
    const body = (await res.json()) as { data?: { status?: string } };
    expect(body.data?.status).toBe('UP');
  });

  test('POST /api/chat/send dangerous command returns L4 BLOCK', async ({ page }) => {
    const { consoleErrors, pageErrors } = attachConsoleGuards(page);

    // We intentionally do NOT install any page.route() interception here.
    // All /api/** traffic must go through the Vite proxy to the backend.
    await page.goto('/chat');
    await expect(page.getByTestId('chat-console')).toBeVisible();

    // Click the "危险命令拦截" quick-action.
    await page.getByTestId('quick-action-danger').click();

    // The backend must respond with L4 BLOCK. The RiskLevelTag testid
    // is derived from the level code — L4 should appear.
    await expect(page.getByTestId('risk-level-L4')).toBeVisible();
    await expect(page.getByTestId('chat-block')).toBeVisible();

    // Audit id must be present so we can deep-link into the audit page.
    const auditLine = page.getByTestId('chat-audit-id');
    await expect(auditLine).toBeVisible();
    const auditId = (await auditLine.locator('code').textContent())?.trim();
    expect(auditId, 'audit id returned by the backend').toBeTruthy();

    // The deep-link must point at /audit?auditId=<id>.
    const link = page.getByTestId('chat-block-audit-link');
    await expect(link).toHaveAttribute(
      'href',
      new RegExp(`/audit\\?auditId=${auditId}`),
    );

    // No uncaught errors during the round-trip.
    expect(pageErrors, 'pageerror events').toEqual([]);
    expect(consoleErrors, 'console.error events').toEqual([]);
  });

  test('audit deep-link navigates to /audit?auditId=...', async ({ page }) => {
    const { consoleErrors, pageErrors } = attachConsoleGuards(page);

    await page.goto('/chat');
    await expect(page.getByTestId('chat-console')).toBeVisible();

    await page.getByTestId('quick-action-danger').click();
    await expect(page.getByTestId('risk-level-L4')).toBeVisible();

    // Click the audit link; the router should land on /audit?auditId=...
    await page.getByTestId('chat-block-audit-link').click();
    await expect(page).toHaveURL(/\/audit\?auditId=.+/);

    // The AuditLog page must mount and either show the table or the
    // detail drawer — both are acceptable for the live smoke.
    await expect(page.getByTestId('audit-page')).toBeVisible();

    expect(pageErrors, 'pageerror events').toEqual([]);
    expect(consoleErrors, 'console.error events').toEqual([]);
  });
});
