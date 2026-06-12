// Live backend smoke — Task 14 / Step 5.
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
// Usage:
//   $env:E2E_LIVE = 'true'
//   npm run test:e2e -- tests/e2e/demo-live.spec.ts
//
// Pre-requisites:
//   * The Spring Boot backend must be running on http://127.0.0.1:8080
//     (e.g. java -jar backend/target/kylin-ops-guard.jar or `mvn spring-boot:run`).
//   * The Vite dev server is started by playwright.config.ts webServer.
//   * /api/health, /api/chat/send are part of the P0 API surface (CLAUDE.md §P0 API Surface).

import { test, expect, type Page, type ConsoleMessage } from '@playwright/test';

const LIVE_ENABLED = process.env.E2E_LIVE === 'true';

function attachConsoleGuards(page: Page) {
  const consoleErrors: string[] = [];
  const pageErrors: string[] = [];
  page.on('console', (msg: ConsoleMessage) => {
    if (msg.type() === 'error') consoleErrors.push(msg.text());
  });
  page.on('pageerror', (err) => {
    pageErrors.push(err.message);
  });
  return { consoleErrors, pageErrors };
}

test.describe('Live backend smoke @ E2E_LIVE=true', () => {
  test.skip(!LIVE_ENABLED, 'E2E_LIVE not set — skipping live backend smoke.');

  test('GET /api/health returns UP', async ({ request }) => {
    // NOTE: this hits the Vite dev server (5173) which proxies /api -> :8080.
    // We use the page's request context so the proxy is in play.
    const res = await request.get('/api/health');
    expect(res.status()).toBe(200);
    const body = (await res.json()) as { status?: string };
    expect(body.status).toBe('UP');
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