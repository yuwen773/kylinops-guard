/**
 * Landing page — P1-03 public marketing route.
 *
 * Strategy:
 *   - Intercept /api/auth/session with an anonymous (401) response so
 *     the router falls through to public-route logic without redirecting
 *     to /login (the landing route is itself public).
 *   - Verify the 9 sections are visible end-to-end on a real browser.
 *   - Verify the primary CTA actually navigates to /login.
 *
 * Why a real browser test (vs. vitest only):
 *   - Confirms Vue Router + App.vue meta.public branching work together
 *     (no AppLayout chrome bleeds into /landing).
 *   - Confirms backdrop-filter and pinned nav behave on real CSS layout.
 */

import { test, expect, type Page } from '@playwright/test';

async function stubAuthSession(page: Page): Promise<void> {
  // Landing is public — bypass the auth guard by returning an anonymous
  // 401 on /api/auth/session. The router guard treats 401 as "no session"
  // and only redirects when the route is NOT public.
  await page.route('**/api/auth/session', async (route) => {
    await route.fulfill({
      status: 401,
      contentType: 'application/json',
      body: JSON.stringify({ error: 'unauthenticated' }),
    });
  });
}

test.describe('Landing page', () => {
  test.beforeEach(async ({ page }) => {
    await stubAuthSession(page);
  });

  test('visiting /landing renders all 9 sections', async ({ page }) => {
    await page.goto('/landing');
    await expect(page.getByTestId('landing-page')).toBeVisible();
    await expect(page.getByTestId('landing-nav')).toBeVisible();
    await expect(page.getByTestId('landing-hero')).toBeVisible();
    await expect(page.getByTestId('landing-bento')).toBeVisible();
    await expect(page.getByTestId('closed-loop')).toBeVisible();
    await expect(page.getByTestId('landing-scenarios')).toBeVisible();
    await expect(page.getByTestId('landing-tool-matrix')).toBeVisible();
    await expect(page.getByTestId('landing-cta')).toBeVisible();
    await expect(page.getByTestId('landing-footer')).toBeVisible();
  });

  test('does not render AppLayout chrome on /landing', async ({ page }) => {
    await page.goto('/landing');
    // AppLayout renders a sidebar with this exact menu item label.
    // If chrome leaks, the test fails loudly.
    await expect(page.getByText('对话控制台')).toHaveCount(0);
    await expect(page.locator('.app-aside')).toHaveCount(0);
  });

  test('redirects / to /landing', async ({ page }) => {
    await page.goto('/');
    await expect(page).toHaveURL(/\/landing$/);
    await expect(page.getByTestId('landing-hero')).toBeVisible();
  });

  test('hero primary CTA navigates to /login', async ({ page }) => {
    await page.goto('/landing');
    await page.getByTestId('hero-cta-primary').click();
    await expect(page).toHaveURL(/\/login$/);
  });

  test('CTA banner primary navigates to /login', async ({ page }) => {
    await page.goto('/landing');
    // Scroll to CTA banner first so the button is in viewport.
    await page.getByTestId('landing-cta').scrollIntoViewIfNeeded();
    await page.getByTestId('cta-primary').click();
    await expect(page).toHaveURL(/\/login$/);
  });

  test('nav CTA navigates to /login', async ({ page }) => {
    await page.goto('/landing');
    await page.getByTestId('landing-nav-cta').click();
    await expect(page).toHaveURL(/\/login$/);
  });

  test('hero "演示脚本" CTA scrolls to scenarios section', async ({ page }) => {
    await page.goto('/landing');
    await page.getByTestId('hero-cta-secondary').click();
    // After scrollIntoView, scenarios testid should be in viewport.
    const scenarios = page.getByTestId('landing-scenarios');
    await expect(scenarios).toBeInViewport();
  });

  test('renders 4 demo scenarios with risk tags', async ({ page }) => {
    await page.goto('/landing');
    await page.getByTestId('landing-scenarios').scrollIntoViewIfNeeded();
    for (const num of ['01', '02', '03', '04']) {
      const card = page.getByTestId(`scenario-${num}`);
      await expect(card).toBeVisible();
      await expect(card.locator('[data-testid^="risk-level-"]').first()).toBeVisible();
    }
  });

  test('tool matrix lists all 10 PRD P0 tools', async ({ page }) => {
    await page.goto('/landing');
    const matrix = page.getByTestId('landing-tool-matrix');
    await matrix.scrollIntoViewIfNeeded();
    const requiredTools = [
      'system_info_tool',
      'cpu_status_tool',
      'memory_status_tool',
      'disk_usage_tool',
      'large_file_scan_tool',
      'process_list_tool',
      'process_detail_tool',
      'network_port_tool',
      'service_status_tool',
      'journal_log_tool',
    ];
    for (const name of requiredTools) {
      await expect(matrix.getByText(name, { exact: true })).toBeVisible();
    }
  });

  test('page contains zero em-dash characters', async ({ page }) => {
    await page.goto('/landing');
    const text = await page.locator('body').innerText();
    expect(text).not.toContain('—');
    expect(text).not.toContain('–');
  });
});
