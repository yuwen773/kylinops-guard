// Demo flows — Task 14 / Step 3.
//
// Covers the four scenarios mandated by 演示视频脚本 §3.x:
//   1. System health check  — multi-tool fan-out renders correctly.
//   2. Disk diagnosis       — advice shown, NO auto-delete.
//   3. nginx CONFIRM flow   — L2 card → confirm → audit detail.
//   4. L4 BLOCK + inject    — verdict rendered + audit deep-link.
//
// Strategy:
//   * Route-intercept /api/** with deterministic fixtures (no live
//     backend). Fixtures mirror the Java DTOs in fixtures.ts.
//   * Watch the request body of /api/actions/confirm to lock the wire
//     contract: payload is EXACTLY { actionId, confirm }.
//   * Every assertion is a locator.waitFor / expect(...).toBeVisible —
//     no `waitForTimeout`.
//
// The fixtures used here are scene-specific so each test can run
// independently and in any order.

import { test, expect, type Page, type ConsoleMessage } from '@playwright/test';
import {
  mockAgentResult,
  mockApiResponse,
  mockAuditLogDetail,
  mockAuthSession,
  mockConfirmedAuditDetail,
  mockBlockedAuditDetail,
  mockHealthAgentResult,
  mockDiskAgentResult,
  mockNginxConfirmAgentResult,
  mockServiceStatusAgentResult,
  mockBlockedAgentResult,
} from './fixtures';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

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

interface ChatRouteHandlers {
  onChatSend: (reqBody: { content: string; sessionId?: string }) => unknown;
  onActionsConfirm?: (reqBody: { actionId: string; confirm: boolean }) => unknown;
  onAuditDetail?: (auditId: string) => unknown;
}

function installChatMocks(page: Page, handlers: ChatRouteHandlers) {
  // Always intercept /api/** to keep noise off the page.
  page.route(/^https?:\/\/[^/]+\/api(?:\/|$)/, async (route) => {
    const url = route.request().url();
    const method = route.request().method();

    if (url.endsWith('/api/health') && method === 'GET') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'UP' }),
      });
    }

    // GET /api/auth/session — P2-T5 router guard pulls this on every
    // protected navigation. Mocking 200 keeps the existing tests
    // authenticated without going through the login form.
    if (url.endsWith('/api/auth/session') && method === 'GET') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(mockApiResponse(mockAuthSession())),
      });
    }

    if (url.endsWith('/api/chat/send') && method === 'POST') {
      const reqBody = JSON.parse(route.request().postData() ?? '{}') as {
        content: string;
        sessionId?: string;
      };
      const data = handlers.onChatSend(reqBody);
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(mockApiResponse(data)),
      });
    }

    if (url.endsWith('/api/actions/confirm') && method === 'POST') {
      const reqBody = JSON.parse(route.request().postData() ?? '{}') as {
        actionId: string;
        confirm: boolean;
      };
      if (handlers.onActionsConfirm) {
        const data = handlers.onActionsConfirm(reqBody);
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(mockApiResponse(data)),
        });
      }
      // Default: confirm successfully.
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(
          mockApiResponse({
            actionId: reqBody.actionId,
            auditId: 'audit-confirm-001',
            status: 'CONFIRMED',
          }),
        ),
      });
    }

    // GET /api/audit/logs/{auditId}
    const auditMatch = url.match(/\/api\/audit\/logs\/([^?]+)/);
    if (auditMatch && method === 'GET') {
      const auditId = decodeURIComponent(auditMatch[1]);
      const data = handlers.onAuditDetail
        ? handlers.onAuditDetail(auditId)
        : mockAuditLogDetail({ auditId });
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(mockApiResponse(data)),
      });
    }

    // GET /api/audit/logs (list)
    if (/^.*\/api\/audit\/logs(\?|$)/.test(url) && method === 'GET') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(
          mockApiResponse({
            content: [],
            totalElements: 0,
            totalPages: 0,
            number: 0,
            size: 20,
          }),
        ),
      });
    }

    // Fallback: respond with null so the page does not crash on stale calls.
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(mockApiResponse(null)),
    });
  });
}

async function gotoChat(page: Page) {
  await page.goto('/chat');
  await expect(page.getByTestId('chat-console')).toBeVisible();
}

// ---------------------------------------------------------------------------
// Scenario 1 — System health check fan-out.
// ---------------------------------------------------------------------------

test.describe('Demo scenario 1 — system health fan-out', () => {
  test('health quick-action renders multi-tool cards + audit id', async ({ page }) => {
    const { consoleErrors, pageErrors } = attachConsoleGuards(page);

    installChatMocks(page, {
      onChatSend: (body) => {
        // Lock the wire shape: POST /api/chat/send must be { content, sessionId? }.
        expect(typeof body.content).toBe('string');
        return mockHealthAgentResult();
      },
    });

    await gotoChat(page);

    // Click the health quick-action button (testid is wired in the page).
    const sendRequest = page.waitForRequest(
      (req) => req.url().endsWith('/api/chat/send') && req.method() === 'POST',
    );
    await page.getByTestId('quick-action-health').click();
    await sendRequest;

    // Verdict: ALLOW + L0 from backend must be rendered verbatim.
    await expect(page.getByTestId('risk-level-L0')).toBeVisible();

    // Multi-tool fan-out: every tool from the fixture must appear.
    for (const tool of [
      'system_info_tool',
      'cpu_status_tool',
      'memory_status_tool',
      'disk_usage_tool',
      'service_status_tool',
      'network_port_tool',
    ]) {
      await expect(page.getByTestId(`tool-call-${tool}`)).toBeVisible();
    }

    // Audit id from backend must be shown.
    await expect(page.getByTestId('chat-audit-id')).toContainText('audit-health-001');

    expect(pageErrors, 'pageerror events').toEqual([]);
    expect(consoleErrors, 'console.error events').toEqual([]);
  });
});

// ---------------------------------------------------------------------------
// Scenario 2 — Disk diagnosis: advice only, NEVER auto-delete.
// ---------------------------------------------------------------------------

test.describe('Demo scenario 2 — disk advice without auto-delete', () => {
  test('disk quick-action shows advice and forbids delete buttons', async ({ page }) => {
    const { consoleErrors, pageErrors } = attachConsoleGuards(page);

    installChatMocks(page, {
      onChatSend: (body) => {
        expect(body.content).toContain('磁盘');
        return mockDiskAgentResult();
      },
    });

    await gotoChat(page);

    await page.getByTestId('quick-action-disk').click();

    // L1 verdict (the disk fixture is L1 ALLOW).
    await expect(page.getByTestId('risk-level-L1')).toBeVisible();

    // The answer text mentions safe cleanup but NOT any delete keyword.
    await expect(page.getByTestId('chat-answer')).toBeVisible();
    const answer = await page.getByTestId('chat-answer').textContent();
    expect(answer ?? '').toContain('安全清理');
    expect(answer ?? '').not.toContain('已删除');
    expect(answer ?? '').not.toContain('已清理');

    // No confirmation card should appear for an ALLOW verdict.
    await expect(page.locator('[data-testid^="execution-confirm-"]')).toHaveCount(0);

    // No "delete" / "清理" buttons must be rendered. The page only shows
    // the chat input + quick actions, neither of which contains those
    // tokens for the disk scenario.
    const allButtons = await page.locator('button').allInnerTexts();
    for (const text of allButtons) {
      // Allow the generic 重置 button on the audit page; this assertion
      // is scoped to the chat console so only quick-actions + input
      // buttons are present.
      expect(text).not.toMatch(/^删除$/);
      expect(text).not.toMatch(/^立即清理$/);
      expect(text).not.toMatch(/^一键清理$/);
    }

    expect(pageErrors, 'pageerror events').toEqual([]);
    expect(consoleErrors, 'console.error events').toEqual([]);
  });
});

// ---------------------------------------------------------------------------
// Scenario 3 — nginx service diagnosis + L2 CONFIRM flow.
// ---------------------------------------------------------------------------

test.describe('Demo scenario 3 — nginx CONFIRM flow', () => {
  test('service quick-action → context restart → confirm → audit detail', async ({
    page,
  }) => {
    const { consoleErrors, pageErrors } = attachConsoleGuards(page);

    let confirmBody: { actionId: string; confirm: boolean } | null = null;

    installChatMocks(page, {
      onChatSend: (body) => {
        // Step A: nginx status diagnosis returns ALLOW.
        // Step B: nginx restart returns L2 CONFIRM.
        if (body.content.includes('重启')) {
          return mockNginxConfirmAgentResult();
        }
        return mockServiceStatusAgentResult();
      },
      onActionsConfirm: (body) => {
        // Lock the wire contract: payload is EXACTLY { actionId, confirm }.
        // No toolName, params, command, or target should be present.
        confirmBody = body;
        expect(Object.keys(body).sort()).toEqual(['actionId', 'confirm']);
        expect(body.actionId).toBe('action-nginx-001');
        expect(body.confirm).toBe(true);
        return {
          actionId: body.actionId,
          auditId: 'audit-confirm-001',
          status: 'CONFIRMED',
        };
      },
      onAuditDetail: (auditId) => mockConfirmedAuditDetail(auditId, 'action-nginx-001'),
    });

    await gotoChat(page);

    // Step A: diagnose nginx.
    await page.getByTestId('quick-action-service').click();

    // Verdict: L0 ALLOW from backend.
    await expect(page.getByTestId('risk-level-L0')).toBeVisible();
    await expect(page.getByTestId('tool-call-service_status_tool')).toBeVisible();

    // Contextual restart button appears once a service_status_tool ran.
    await expect(page.getByTestId('quick-action-nginx-restart')).toBeVisible();

    // Fill the input from the contextual restart button (button only fills —
    // it does not send). The actual send is the user's explicit choice.
    await page.getByTestId('quick-action-nginx-restart').click();
    await expect(page.getByTestId('chat-input-field')).not.toHaveValue('');
    await page.getByTestId('chat-input-submit').click();

    // L2 CONFIRM verdict + execution card must render.
    await expect(page.getByTestId('risk-level-L2').first()).toBeVisible();
    await expect(page.getByTestId('execution-confirm-action-nginx-001')).toBeVisible();

    // Click confirm; the action card emits `confirm` and the parent
    // fires POST /api/actions/confirm.
    const confirmRequest = page.waitForRequest(
      (req) => req.url().endsWith('/api/actions/confirm') && req.method() === 'POST',
    );
    await page.getByTestId('execution-confirm-confirm-action-nginx-001').click();
    await confirmRequest;

    // After the round-trip + audit re-fetch, the "操作已确认" final
    // block appears with the audit link.
    await expect(
      page.getByTestId('execution-final-action-nginx-001'),
    ).toBeVisible();
    await expect(
      page.getByTestId('execution-final-audit-link-action-nginx-001'),
    ).toBeVisible();

    // Wire-contract assertion: payload was EXACTLY { actionId, confirm }.
    expect(confirmBody).not.toBeNull();
    expect(confirmBody).toEqual({ actionId: 'action-nginx-001', confirm: true });

    expect(pageErrors, 'pageerror events').toEqual([]);
    expect(consoleErrors, 'console.error events').toEqual([]);
  });
});

// ---------------------------------------------------------------------------
// Scenario 4 — L4 BLOCK + prompt injection: verdict + audit deep-link.
// ---------------------------------------------------------------------------

test.describe('Demo scenario 4 — L4 BLOCK + prompt injection', () => {
  test('danger quick-action renders BLOCK verdict + audit link', async ({ page }) => {
    const { consoleErrors, pageErrors } = attachConsoleGuards(page);

    installChatMocks(page, {
      onChatSend: (body) => {
        expect(body.content).toContain('rm -rf');
        return mockBlockedAgentResult(
          'audit-block-001',
          '匹配绝对阻断规则 rm-rf-root',
        );
      },
      onAuditDetail: (auditId) => mockBlockedAuditDetail(auditId),
    });

    await gotoChat(page);

    await page.getByTestId('quick-action-danger').click();

    // L4 BLOCK verdict rendered verbatim.
    await expect(page.getByTestId('risk-level-L4')).toBeVisible();
    await expect(page.getByTestId('chat-block')).toBeVisible();
    await expect(page.getByTestId('chat-block-reason')).toContainText('rm-rf-root');

    // The audit link points to /audit?auditId=audit-block-001.
    const link = page.getByTestId('chat-block-audit-link');
    await expect(link).toBeVisible();
    await expect(link).toHaveAttribute('href', /\/audit\?auditId=audit-block-001/);

    // The audit id line is still shown so the operator can copy it.
    await expect(page.getByTestId('chat-audit-id')).toContainText('audit-block-001');

    expect(pageErrors, 'pageerror events').toEqual([]);
    expect(consoleErrors, 'console.error events').toEqual([]);
  });

  test('prompt-inject quick-action is also routed to L4 BLOCK', async ({ page }) => {
    const { consoleErrors, pageErrors } = attachConsoleGuards(page);

    installChatMocks(page, {
      onChatSend: (body) => {
        expect(body.content).toContain('root');
        return mockBlockedAgentResult(
          'audit-inject-001',
          'Prompt injection + 绝对阻断规则 chmod-777',
        );
      },
      onAuditDetail: (auditId) => mockBlockedAuditDetail(auditId),
    });

    await gotoChat(page);

    await page.getByTestId('quick-action-inject').click();

    await expect(page.getByTestId('risk-level-L4')).toBeVisible();
    await expect(page.getByTestId('chat-block-reason')).toContainText(
      'Prompt injection',
    );

    const link = page.getByTestId('chat-block-audit-link');
    await expect(link).toHaveAttribute('href', /\/audit\?auditId=audit-inject-001/);

    expect(pageErrors, 'pageerror events').toEqual([]);
    expect(consoleErrors, 'console.error events').toEqual([]);
  });
});

// ---------------------------------------------------------------------------
// Defensive — AgentResult helper unit check.
// ---------------------------------------------------------------------------

test('mockAgentResult omits undefined fields', () => {
  const result = mockAgentResult({
    sessionId: 'session-x',
    answer: 'hi',
    riskLevel: 'L0',
    riskDecision: 'ALLOW',
    auditId: 'audit-x',
  });
  expect(result.sessionId).toBe('session-x');
  expect(result.answer).toBe('hi');
  expect(result.riskLevel).toBe('L0');
  expect(result.riskDecision).toBe('ALLOW');
  expect(result.auditId).toBe('audit-x');
  // Keys we did not set must not be present (frontend compares === undefined).
  expect('needConfirmation' in result).toBe(false);
  expect('pendingAction' in result).toBe(false);
  expect('errorMessage' in result).toBe(false);
});
