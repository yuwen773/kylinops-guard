// Notification settings page screenshot harness.
// Captures dark + light variants of the notification settings page
// (which had hardcoded light-mode color leaks).
//
// Run via:
//   node tests/e2e/screenshot-notification.mjs --theme=dark
//   node tests/e2e/screenshot-notification.mjs --theme=light
//   node tests/e2e/screenshot-notification.mjs --theme=all

import { chromium } from 'playwright';
import { mkdir } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const OUT_DIR = resolve(__dirname, 'screenshots');
const BASE_URL = process.env.E2E_BASE_URL ?? 'http://127.0.0.1:5173';

const themeArg = (process.argv.find((a) => a.startsWith('--theme=')) ?? '--theme=dark')
  .split('=')[1];
const themes = themeArg === 'all' ? ['dark', 'light'] : [themeArg];

const VIEWPORT = { width: 1280, height: 900 };

async function captureTheme(theme) {
  await mkdir(resolve(OUT_DIR, theme), { recursive: true });

  const browser = await chromium.launch({ channel: 'chromium' });
  const context = await browser.newContext({ viewport: VIEWPORT });
  await context.addInitScript((t) => {
    try {
      window.localStorage.setItem('kg-theme', t);
    } catch {}
  }, theme);

  const page = await context.newPage();
  // Mock auth so the guard lets us through; the layout is a protected route.
  await page.route('**/api/auth/session', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        username: 'admin',
        roles: ['ADMIN'],
        csrfToken: 'csrf-fix-test',
        loginAt: '2026-06-20T00:00:00Z',
        expiresAt: '2026-06-21T00:00:00Z',
        idleTimeout: 1800,
      }),
    });
  });
  // Mock notification settings so the page renders content rather than an
  // empty error state. The hardcoded-color leak was most visible in
  // section / toggle cards.
  await page.route('**/api/notification/settings', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        enabled: true,
        dryRun: false,
        version: 3,
        channels: [
          {
            id: 'feishu-prod',
            type: 'FEISHU',
            enabled: true,
            url: 'https://open.feishu.cn/open-apis/bot/v2/hook/****',
            secretConfigured: true,
            timeoutMs: 5000,
            updatedAt: '2026-06-20T08:30:00Z',
            lastTestResult: { status: 'SENT', at: '2026-06-20T08:31:00Z' },
          },
          {
            id: 'webhook-archive',
            type: 'WEBHOOK',
            enabled: false,
            url: 'https://archive.internal.example.com/notify',
            secretConfigured: false,
            timeoutMs: 3000,
            updatedAt: '2026-06-19T14:02:11Z',
            lastTestResult: null,
          },
        ],
      }),
    });
  });
  await page.route('**/api/notification/test-records*', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([]),
    });
  });

  await page.goto(`${BASE_URL}/notification-settings`, { waitUntil: 'networkidle' });
  await page.waitForSelector('[data-testid="notification-settings-page"]', { timeout: 10000 });
  await page.waitForTimeout(500);

  const outPath = resolve(OUT_DIR, theme, 'ui-01-06-notification.png');
  await page.screenshot({ path: outPath, fullPage: false });
  console.log(`  ${theme}: ${outPath}`);

  await browser.close();
}

console.log(`Capturing notification-settings screenshots (themes: ${themes.join(', ')})`);
for (const theme of themes) {
  await captureTheme(theme);
}
console.log('Done.');