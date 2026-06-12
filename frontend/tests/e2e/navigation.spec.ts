// Navigation coverage — Task 14 / Step 2.
//
// Asserts every Phase-2 page route loads without console errors or
// uncaught page errors at the demo resolution (1280x720).
//
// Strategy:
//   * Install a `page.on('console')` + `page.on('pageerror')` listener on
//     each page before navigation so we never miss a runtime warning.
//   * Mock ALL /api/** responses with deterministic fixtures so the
//     pages render without depending on a running Spring Boot backend.
//     Mock data MUST stay aligned with the Java DTOs (see fixtures.ts).
//   * No `waitForTimeout` — every wait is a locator assertion.
//
// Each route is a separate test for parallel safety. The mock handler is
// per-test so the tests are independent of execution order.

import { test, expect, type Page, type ConsoleMessage } from '@playwright/test';
import {
  mockApiResponse,
  mockAuditLogPage,
  mockAuditLogSummary,
  mockDashboardOverview,
  mockReportPage,
  mockReportSummary,
  mockSecurityEventPage,
  mockSecurityEventView,
  mockSecurityRuleView,
  mockToolDefinitionList,
} from './fixtures';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Install console-error + pageerror listeners on a page BEFORE navigation.
 * Returns the two buffers the test will assert on at the end.
 */
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

/**
 * Install a permissive route mock that responds to every /api/** request
 * with an empty (or minimal) ApiResponse envelope so the page renders
 * without a real backend.
 *
 * Specific endpoints get the matching fixture; anything else gets a
 * generic 200 with null data — enough to keep the page from erroring.
 */
function installApiMocks(page: Page) {
  page.route('**/api/**', async (route) => {
    const url = route.request().url();
    const method = route.request().method();

    // GET /api/health
    if (url.endsWith('/api/health') && method === 'GET') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'UP' }),
      });
    }

    // GET /api/dashboard/overview
    if (url.endsWith('/api/dashboard/overview') && method === 'GET') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(mockApiResponse(mockDashboardOverview())),
      });
    }

    // GET /api/tools
    if (/^.*\/api\/tools(\?|$)/.test(url) && method === 'GET') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(mockApiResponse(mockToolDefinitionList())),
      });
    }

    // GET /api/security/rules
    if (url.endsWith('/api/security/rules') && method === 'GET') {
      const rules = [
        mockSecurityRuleView('rm-rf-root', 'L4', 'BLOCK'),
        mockSecurityRuleView('chmod-777', 'L4', 'BLOCK'),
        mockSecurityRuleView('service-restart', 'L2', 'CONFIRM'),
      ];
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(mockApiResponse(rules)),
      });
    }

    // GET /api/security/events
    if (url.endsWith('/api/security/events') && method === 'GET') {
      const events = [
        mockSecurityEventView('audit-block-001', '命中绝对阻断规则 rm-rf-root'),
      ];
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(mockApiResponse(mockSecurityEventPage(events))),
      });
    }

    // GET /api/audit/logs (with or without query)
    if (/^.*\/api\/audit\/logs(\?|$)/.test(url) && method === 'GET') {
      const rows = [
        mockAuditLogSummary({
          auditId: 'audit-row-001',
          userInput: '帮我检查当前系统健康状态',
          intentType: 'HEALTH_CHECK',
          riskLevel: 'L0',
          riskDecision: 'ALLOW',
          status: 'SUCCESS',
          toolCallCount: 6,
          createdAt: '2026-06-12T10:00:00',
        }),
      ];
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(mockApiResponse(mockAuditLogPage(rows))),
      });
    }

    // GET /api/reports
    if (/^.*\/api\/reports(\?|$)/.test(url) && method === 'GET') {
      const rows = [
        mockReportSummary('report-001', 'audit-health-001'),
      ];
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(mockApiResponse(mockReportPage(rows))),
      });
    }

    // Anything else: generic 200 with null data so the page does not crash.
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(mockApiResponse(null)),
    });
  });
}

// ---------------------------------------------------------------------------
// Tests — one per page route.
// ---------------------------------------------------------------------------

test.describe('Phase-2 page navigation @ 1280x720', () => {
  test('ChatConsole loads without console errors', async ({ page }) => {
    const { consoleErrors, pageErrors } = attachConsoleGuards(page);
    installApiMocks(page);

    await page.goto('/chat');
    await expect(page.getByTestId('chat-console')).toBeVisible();

    expect(pageErrors, 'pageerror events').toEqual([]);
    expect(consoleErrors, 'console.error events').toEqual([]);
  });

  test('Dashboard loads without console errors', async ({ page }) => {
    const { consoleErrors, pageErrors } = attachConsoleGuards(page);
    installApiMocks(page);

    await page.goto('/dashboard');
    await expect(page.getByTestId('dashboard-page')).toBeVisible();
    await expect(page.getByTestId('dashboard-score-card')).toBeVisible();

    expect(pageErrors, 'pageerror events').toEqual([]);
    expect(consoleErrors, 'console.error events').toEqual([]);
  });

  test('ToolCenter loads without console errors', async ({ page }) => {
    const { consoleErrors, pageErrors } = attachConsoleGuards(page);
    installApiMocks(page);

    await page.goto('/tools');
    await expect(page.getByTestId('tool-center-page')).toBeVisible();
    await expect(page.getByTestId('tool-table')).toBeVisible();

    expect(pageErrors, 'pageerror events').toEqual([]);
    expect(consoleErrors, 'console.error events').toEqual([]);
  });

  test('SecurityCenter loads without console errors', async ({ page }) => {
    const { consoleErrors, pageErrors } = attachConsoleGuards(page);
    installApiMocks(page);

    await page.goto('/security');
    await expect(page.getByTestId('security-page')).toBeVisible();
    await expect(page.getByTestId('security-rules-section')).toBeVisible();

    expect(pageErrors, 'pageerror events').toEqual([]);
    expect(consoleErrors, 'console.error events').toEqual([]);
  });

  test('AuditLog loads without console errors', async ({ page }) => {
    const { consoleErrors, pageErrors } = attachConsoleGuards(page);
    installApiMocks(page);

    await page.goto('/audit');
    await expect(page.getByTestId('audit-page')).toBeVisible();
    await expect(page.getByTestId('audit-table')).toBeVisible();

    expect(pageErrors, 'pageerror events').toEqual([]);
    expect(consoleErrors, 'console.error events').toEqual([]);
  });

  test('ReportCenter loads without console errors', async ({ page }) => {
    const { consoleErrors, pageErrors } = attachConsoleGuards(page);
    installApiMocks(page);

    await page.goto('/reports');
    await expect(page.getByTestId('report-page')).toBeVisible();
    await expect(page.getByTestId('report-table')).toBeVisible();

    expect(pageErrors, 'pageerror events').toEqual([]);
    expect(consoleErrors, 'console.error events').toEqual([]);
  });

  test('Demo resolution is pinned to 1280x720', async ({ page }) => {
    // The config sets viewport 1280x720; this test asserts the runtime
    // viewport actually honours it. Element Plus layouts break at
    // narrower widths, so a regression here breaks the demo.
    expect(page.viewportSize()).toEqual({ width: 1280, height: 720 });
  });
});