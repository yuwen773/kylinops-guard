import { test, expect } from '@playwright/test';

test.describe('Theme toggle (dark <-> light)', () => {
  test.beforeEach(async ({ context }) => {
    // Bypass the route guard by seeding an in-memory auth session.
    await context.addInitScript(() => {
      try {
        if (!localStorage.getItem('kg-theme')) {
          localStorage.setItem('kg-theme', 'dark');
        }
      } catch {}
    });
    await context.route('**/api/auth/session', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 200,
          message: 'ok',
          data: {
            username: 'kylinops-e2e',
            csrfToken: 'csrf-e2e-001',
            loginAt: '2026-06-18T00:00:00Z',
            expiresAt: '2026-06-18T12:00:00Z',
            idleTimeout: 1800,
          },
          timestamp: Date.now(),
        }),
      });
    });
    await context.route('**/api/health', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'UP' }),
      });
    });
  });

  test('default theme is dark and toggle button is visible', async ({ page }) => {
    await page.goto('/chat');
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
    const toggle = page.locator('[data-testid="app-theme-toggle"]');
    await expect(toggle).toBeVisible();
  });

  test('clicking toggle switches to light and persists to localStorage', async ({ page }) => {
    await page.goto('/chat');
    const toggle = page.locator('[data-testid="app-theme-toggle"]');
    await toggle.click();
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'light');
    const stored = await page.evaluate(() => localStorage.getItem('kg-theme'));
    expect(stored).toBe('light');
  });

  test('clicking again switches back to dark', async ({ page }) => {
    await page.goto('/chat');
    const toggle = page.locator('[data-testid="app-theme-toggle"]');
    await toggle.click(); // -> light
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'light');
    await toggle.click(); // -> dark
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
    const stored = await page.evaluate(() => localStorage.getItem('kg-theme'));
    expect(stored).toBe('dark');
  });

  test('theme persists across full page reload', async ({ page }) => {
    await page.goto('/chat');
    const toggle = page.locator('[data-testid="app-theme-toggle"]');
    await toggle.click();
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'light');
    await page.reload();
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'light');
  });

  test('aria-label is localized Chinese', async ({ page }) => {
    await page.goto('/chat');
    const toggle = page.locator('[data-testid="app-theme-toggle"]');
    await expect(toggle).toHaveAttribute('aria-label', '切换到亮色主题');
    await toggle.click();
    await expect(toggle).toHaveAttribute('aria-label', '切换到暗色主题');
  });
});
