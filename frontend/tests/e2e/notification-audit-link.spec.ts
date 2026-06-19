// Audit Log — Notification Records E2E — P1-01 Plan Task 11 = User Task 10.
//
// Asserts the audit-log detail drawer renders the notification records card
// (Task 10) under three conditions: populated, null, and FAILED.
//
// Pattern: each test installs its own page.route() mocks.

import { test, expect, type Page } from '@playwright/test';
import {
  mockApiResponse,
  mockAuditLogPage,
  mockAuditLogSummary,
  mockAuthSession,
} from './fixtures';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function attachConsoleGuards(page: Page) {
  const consoleErrors: string[] = [];
  const pageErrors: string[] = [];
  page.on('console', (msg) => {
    if (msg.type() === 'error') consoleErrors.push(msg.text());
  });
  page.on('pageerror', (err) => {
    pageErrors.push(err.message);
  });
  return { consoleErrors, pageErrors };
}

interface AuditMockOpts {
  notificationRecords?: unknown[] | null;
  auditId?: string;
}

function installAuditDetailMocks(page: Page, opts: AuditMockOpts) {
  const auditId = opts.auditId ?? 'audit-notif-001';
  const detailPayload: Record<string, unknown> = {
    auditId,
    sessionId: 'session-audit-001',
    userInput: '检查系统状态',
    intentType: 'HEALTH_CHECK',
    riskLevel: 'L0',
    riskDecision: 'ALLOW',
    status: 'SUCCESS',
    createdAt: '2026-06-12T10:00:00',
    updatedAt: '2026-06-12T10:00:05',
    toolCalls: [
      {
        toolCallId: 'tc-1',
        toolName: 'cpu_status_tool',
        status: 'SUCCESS',
        durationMs: 95,
      },
    ],
    riskChecks: [
      {
        riskCheckId: 'rc-1',
        targetType: 'command',
        riskLevel: 'L0',
        riskDecision: 'ALLOW',
        reason: 'L0 只读查询',
        checkedAt: '2026-06-12T10:00:01',
      },
    ],
  };
  if (opts.notificationRecords !== undefined) {
    detailPayload.notificationRecords = opts.notificationRecords;
  }

  page.route(/^https?:\/\/[^/]+\/api(?:\/|$)/, async (route) => {
    const url = route.request().url();
    const method = route.request().method();

    // GET /api/auth/session
    if (url.endsWith('/api/auth/session') && method === 'GET') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(mockApiResponse(mockAuthSession())),
      });
    }

    // GET /api/audit/logs (list — needed for the table)
    if (/^.*\/api\/audit\/logs(\?|$)/.test(url) && method === 'GET') {
      const rows = [
        mockAuditLogSummary({
          auditId,
          userInput: '检查系统状态',
          intentType: 'HEALTH_CHECK',
          riskLevel: 'L0',
          riskDecision: 'ALLOW',
          status: 'SUCCESS',
          toolCallCount: 1,
          createdAt: '2026-06-12T10:00:00',
        }),
      ];
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(mockApiResponse(mockAuditLogPage(rows))),
      });
    }

    // GET /api/audit/logs/{auditId} (detail)
    if (url.endsWith(`/api/audit/logs/${auditId}`) && method === 'GET') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(mockApiResponse(detailPayload)),
      });
    }

    // Catch-all
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(mockApiResponse(null)),
    });
  });
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

test.describe('Audit Log — notification records @ 1280x720', () => {
  test('1 — detail drawer shows notification records card', async ({ page }) => {
    const { consoleErrors, pageErrors } = attachConsoleGuards(page);
    installAuditDetailMocks(page, {
      auditId: 'audit-notif-001',
      notificationRecords: [
        {
          recordId: 'nr-001',
          eventId: 'evt-001',
          auditId: 'audit-notif-001',
          channelId: 'ch-feishu-1',
          channelType: 'FEISHU',
          status: 'SENT',
          responseCode: 200,
          errorMessage: null,
          retryCount: 0,
          sentAt: '2026-06-12T10:00:06',
          createdAt: '2026-06-12T10:00:03',
        },
      ],
    });

    await page.goto('/audit');
    await expect(page.getByTestId('audit-table')).toBeVisible();

    // Click the first row to open the detail drawer.
    await page.getByTestId('audit-row-audit-notif-001').click();
    await expect(page.getByTestId('audit-detail-drawer')).toBeVisible();

    // The notification records card should exist.
    await expect(page.getByTestId('audit-notification-records')).toBeVisible();
    await expect(page.getByText('已发送')).toBeVisible();
    await expect(page.getByText('飞书')).toBeVisible();

    expect(pageErrors).toEqual([]);
    expect(consoleErrors).toEqual([]);
  });

  test('2 — null notificationRecords shows empty state', async ({ page }) => {
    const { consoleErrors, pageErrors } = attachConsoleGuards(page);
    installAuditDetailMocks(page, {
      auditId: 'audit-notif-null',
      notificationRecords: null,
    });

    await page.goto('/audit');
    await expect(page.getByTestId('audit-table')).toBeVisible();

    await page.getByTestId('audit-row-audit-notif-null').click();
    await expect(page.getByTestId('audit-detail-drawer')).toBeVisible();

    await expect(page.getByText('暂无通知发送记录')).toBeVisible();

    expect(pageErrors).toEqual([]);
    expect(consoleErrors).toEqual([]);
  });

  test('3 — FAILED notification records show error message', async ({ page }) => {
    const { consoleErrors, pageErrors } = attachConsoleGuards(page);
    installAuditDetailMocks(page, {
      auditId: 'audit-notif-fail',
      notificationRecords: [
        {
          recordId: 'nr-fail-001',
          eventId: 'evt-fail-001',
          auditId: 'audit-notif-fail',
          channelId: 'ch-webhook-1',
          channelType: 'WEBHOOK',
          status: 'FAILED',
          responseCode: 500,
          errorMessage: '后端连接超时',
          retryCount: 0,
          sentAt: null,
          createdAt: '2026-06-12T10:00:03',
        },
      ],
    });

    await page.goto('/audit');
    await expect(page.getByTestId('audit-table')).toBeVisible();

    await page.getByTestId('audit-row-audit-notif-fail').click();
    await expect(page.getByTestId('audit-detail-drawer')).toBeVisible();

    await expect(page.getByTestId('audit-notification-records')).toBeVisible();
    await expect(page.getByText('发送失败')).toBeVisible();
    await expect(page.getByText('后端连接超时')).toBeVisible();

    expect(pageErrors).toEqual([]);
    expect(consoleErrors).toEqual([]);
  });
});
