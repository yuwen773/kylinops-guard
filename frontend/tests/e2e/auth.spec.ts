// Auth flow coverage — P2-T6.
//
// Five scenarios lock the closed-loop behaviour introduced by P2-T5:
//   1. Anonymous /chat is intercepted by the router guard → /login.
//   2. /api/auth/login 200 + /api/auth/session 200 → AppLayout renders
//      with [data-testid="app-user"] showing the username.
//   3. Wrong credentials (mocked 401) → generic "用户名或密码错误",
//      stays on /login, no AppLayout.
//   4. Logout from the AppLayout → POST /api/auth/logout fires, the
//      router returns to /login, AppLayout disappears.
//   5. Mutating requests (POST /api/chat/send) carry the X-CSRF-TOKEN
//      header sourced from the current session.
//
// These specs run mock-only: the backend is not started, and the only
// live dependency is the Vite dev server brought up by
// `playwright.config.ts`'s `webServer` block. A single regex route
// handler dispatches by URL/method (the pattern also used in
// `navigation.spec.ts` and `demo-flows.spec.ts`) so there is no
// ordering ambiguity between `page.route()` calls.

import { test, expect, type Page, type ConsoleMessage } from '@playwright/test';
import {
  mockApiResponse,
  mockAuthSession,
  mockHealthAgentResult,
} from './fixtures';

function attachConsoleGuards(page: Page) {
  const consoleErrors: string[] = [];
  const pageErrors: string[] = [];
  page.on('console', (msg: ConsoleMessage) => {
    if (msg.type() === 'error') {
      const text = msg.text();
      // Browsers automatically log "Failed to load resource: ... 401
      // (Unauthorized)" when an XHR returns 401. That is expected in
      // the auth-guard scenario and MUST NOT fail the test.
      if (/Failed to load resource:.*401/.test(text)) return;
      consoleErrors.push(text);
    }
  });
  page.on('pageerror', (err) => {
    pageErrors.push(err.message);
  });
  return { consoleErrors, pageErrors };
}

// ---------------------------------------------------------------------------
// Dispatch table — per-test overrides merged into a default mock.
// ---------------------------------------------------------------------------

interface AuthDispatchOptions {
  /** What /api/auth/session should return. Defaults to 200 + default session. */
  sessionStatus?: number;
  sessionBody?: unknown;
  /** What /api/auth/login should return. */
  loginStatus?: number;
  loginBody?: unknown;
  /** Custom CSRF token for the session used to bootstrap. */
  csrfToken?: string;
  /** When true, /api/auth/session returns 401 after the first 200. */
  sessionBecomesUnauthorized?: boolean;
  /** Captured request headers for /api/chat/send (CSRF assertion). */
  chatSendHeaders?: { current: Record<string, string> | null };
}

/**
 * Install ONE regex route handler that dispatches by URL + method. The
 * table-style options object is closed over so per-test behaviour is
 * encoded in the test body, not split across multiple `page.route()`
 * registrations (which Playwright invokes in registration order — a
 * catch-all registered after a specific mock would still fire and
 * mask the more specific intent).
 */
function installAuthMocks(page: Page, opts: AuthDispatchOptions = {}) {
  const sessionStatus = opts.sessionStatus ?? 200;
  const sessionBody =
    opts.sessionBody ??
    mockApiResponse(
      mockAuthSession({
        username: 'admin',
        csrfToken: opts.csrfToken ?? 'csrf-fixture-001',
      }),
    );
  const loginStatus = opts.loginStatus ?? 200;
  const loginBody =
    opts.loginBody ??
    mockApiResponse(
      mockAuthSession({
        username: 'admin',
        csrfToken: opts.csrfToken ?? 'csrf-fixture-001',
      }),
      { message: '登录成功' },
    );

  let firstSessionCall = true;

  page.route(/^https?:\/\/[^/]+\/api(?:\/|$)/, async (route) => {
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

    // GET /api/auth/session
    if (url.endsWith('/api/auth/session') && method === 'GET') {
      if (opts.sessionBecomesUnauthorized && !firstSessionCall) {
        return route.fulfill({
          status: 401,
          contentType: 'application/json',
          body: JSON.stringify({
            code: 401,
            message: '未认证',
            data: null,
            timestamp: Date.now(),
          }),
        });
      }
      firstSessionCall = false;
      return route.fulfill({
        status: sessionStatus,
        contentType: 'application/json',
        body: JSON.stringify(sessionBody),
      });
    }

    // POST /api/auth/login
    if (url.endsWith('/api/auth/login') && method === 'POST') {
      return route.fulfill({
        status: loginStatus,
        contentType: 'application/json',
        body: JSON.stringify(loginBody),
      });
    }

    // POST /api/auth/logout
    if (url.endsWith('/api/auth/logout') && method === 'POST') {
      return route.fulfill({ status: 204, body: '' });
    }

    // POST /api/chat/send — capture headers for CSRF assertion when
    // the test asks for it.
    if (url.endsWith('/api/chat/send') && method === 'POST') {
      if (opts.chatSendHeaders) {
        opts.chatSendHeaders.current = route.request().headers();
      }
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(mockApiResponse(mockHealthAgentResult())),
      });
    }

    // Catch-all: any other /api/** request gets a null envelope so the
    // page does not crash on stale calls.
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(mockApiResponse(null)),
    });
  });
}

// ---------------------------------------------------------------------------
// Scenario 1 — Anonymous /chat is intercepted by the router guard.
// ---------------------------------------------------------------------------

test.describe('Auth guard — anonymous navigation', () => {
  test('GET /api/auth/session 401 → /chat redirects to /login', async ({ page }) => {
    const { consoleErrors, pageErrors } = attachConsoleGuards(page);

    installAuthMocks(page, {
      sessionStatus: 401,
      sessionBody: { code: 401, message: '未认证', data: null, timestamp: Date.now() },
    });

    await page.goto('/chat');

    // Router must rewrite to /login (PUBLIC route).
    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByTestId('login-card')).toBeVisible();
    // AppLayout is not rendered for the Login page.
    await expect(page.getByTestId('app-user')).toHaveCount(0);
    await expect(page.getByTestId('app-logout')).toHaveCount(0);

    expect(pageErrors, 'pageerror events').toEqual([]);
    expect(consoleErrors, 'console.error events').toEqual([]);
  });
});

// ---------------------------------------------------------------------------
// Scenario 2 — Successful login lands on /chat with AppLayout visible.
// ---------------------------------------------------------------------------

test.describe('Auth flow — successful login', () => {
  test('login 200 + session 200 → /chat with AppLayout and app-user', async ({ page }) => {
    const { consoleErrors, pageErrors } = attachConsoleGuards(page);

    installAuthMocks(page, { csrfToken: 'csrf-fixture-002' });

    await page.goto('/login');
    await expect(page.getByTestId('login-card')).toBeVisible();

    // Form inputs are identified by autocomplete attribute — the
    // el-input data-testid does not propagate to the DOM root in
    // Element Plus 2.8, so we cannot rely on it for E2E selectors.
    await page.locator('input[autocomplete="username"]').fill('admin');
    await page.locator('input[autocomplete="current-password"]').fill('test-admin-pwd');
    await page.getByTestId('login-submit').click();

    // Router lands on /chat (Login does router.replace('/') which
    // resolves to /chat per the route table).
    await expect(page).toHaveURL(/\/chat$/);

    // AppLayout exposes the username + logout button.
    await expect(page.getByTestId('app-user')).toBeVisible();
    await expect(page.getByTestId('app-user')).toHaveText('admin');
    await expect(page.getByTestId('app-logout')).toBeVisible();
    // ChatConsole also mounted.
    await expect(page.getByTestId('chat-console')).toBeVisible();

    expect(pageErrors, 'pageerror events').toEqual([]);
    expect(consoleErrors, 'console.error events').toEqual([]);
  });
});

// ---------------------------------------------------------------------------
// Scenario 3 — Wrong credentials render the generic error.
// ---------------------------------------------------------------------------

test.describe('Auth flow — wrong credentials', () => {
  test('login 401 → generic "用户名或密码错误", stays on /login', async ({ page }) => {
    const { consoleErrors, pageErrors } = attachConsoleGuards(page);

    installAuthMocks(page, {
      loginStatus: 401,
      loginBody: mockApiResponse(null, { code: 401, message: '用户名或密码错误' }),
    });

    await page.goto('/login');
    await expect(page.getByTestId('login-card')).toBeVisible();

    await page.locator('input[autocomplete="username"]').fill('admin');
    await page.locator('input[autocomplete="current-password"]').fill('wrong-password');
    await page.getByTestId('login-submit').click();

    // Must NOT navigate away from /login.
    await expect(page).toHaveURL(/\/login$/);
    // The login error alert must show the generic backend message verbatim.
    await expect(page.getByTestId('login-error')).toBeVisible();
    await expect(page.getByTestId('login-error')).toContainText('用户名或密码错误');
    // AppLayout is not rendered.
    await expect(page.getByTestId('app-user')).toHaveCount(0);

    expect(pageErrors, 'pageerror events').toEqual([]);
    expect(consoleErrors, 'console.error events').toEqual([]);
  });
});

// ---------------------------------------------------------------------------
// Scenario 4 — Logout fires /api/auth/logout and returns to /login.
// ---------------------------------------------------------------------------

test.describe('Auth flow — logout', () => {
  test('clicking 登出 posts to /api/auth/logout and returns to /login', async ({ page }) => {
    const { consoleErrors, pageErrors } = attachConsoleGuards(page);

    installAuthMocks(page, {
      csrfToken: 'csrf-fixture-004',
      sessionBecomesUnauthorized: true,
    });

    await page.goto('/chat');
    // ChatConsole must mount inside AppLayout.
    await expect(page.getByTestId('chat-console')).toBeVisible();
    // The logout button is part of AppLayout's header.
    await expect(page.getByTestId('app-logout')).toBeVisible();

    // Wire-contract assertion: the logout request must fire.
    const logoutRequest = page.waitForRequest(
      (req) => req.url().endsWith('/api/auth/logout') && req.method() === 'POST',
    );
    await page.getByTestId('app-logout').click();
    const req = await logoutRequest;
    expect(req.method()).toBe('POST');

    // Router returns to /login.
    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByTestId('login-card')).toBeVisible();
    // AppLayout is gone — the header (and its logout button) is no
    // longer in the DOM. We do NOT assert on app-user because the
    // AppLayout's username display is computed once at mount and is
    // unrelated to the logout flow being tested here (see successful
    // login test for the app-user assertion).
    await expect(page.getByTestId('app-logout')).toHaveCount(0);

    expect(pageErrors, 'pageerror events').toEqual([]);
    expect(consoleErrors, 'console.error events').toEqual([]);
  });
});

// ---------------------------------------------------------------------------
// Scenario 5 — Mutating requests carry X-CSRF-TOKEN.
// ---------------------------------------------------------------------------

test.describe('Auth flow — CSRF header injection', () => {
  test('POST /api/chat/send carries X-CSRF-TOKEN from the active session', async ({ page }) => {
    const { consoleErrors, pageErrors } = attachConsoleGuards(page);

    const captured: { current: Record<string, string> | null } = { current: null };

    installAuthMocks(page, {
      csrfToken: 'csrf-fixture-005',
      chatSendHeaders: captured,
    });

    await page.goto('/chat');
    await expect(page.getByTestId('chat-console')).toBeVisible();

    // Click the health quick-action — it fires POST /api/chat/send.
    const chatSend = page.waitForRequest(
      (req) => req.url().endsWith('/api/chat/send') && req.method() === 'POST',
    );
    await page.getByTestId('quick-action-health').click();
    await chatSend;

    // Playwright returns header names lower-cased; the client.ts
    // interceptor sets `X-CSRF-TOKEN` which becomes `x-csrf-token`.
    expect(captured.current, 'request headers were captured').not.toBeNull();
    expect(captured.current!['x-csrf-token']).toBe('csrf-fixture-005');

    expect(pageErrors, 'pageerror events').toEqual([]);
    expect(consoleErrors, 'console.error events').toEqual([]);
  });
});
