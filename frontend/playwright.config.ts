import { defineConfig } from '@playwright/test';

// Playwright E2E config — Task 14.
//
// Coverage:
//   * navigation.spec — six pages, route interception, 1280x720
//   * demo-flows.spec — four demo scenarios (mock fixtures)
//   * demo-live.spec  — opt-in live backend smoke (only when E2E_LIVE=true)
//
// `baseURL` points at the Vite dev server (default 127.0.0.1:5173). The
// `webServer` block spawns `npm run dev` if it is not already running, so
// the local-dev loop stays `npm run test:e2e` with no extra setup.
//
// The mocked specs intercept `/api/**` so they do not require the backend
// to be up. demo-live.spec deliberately skips interception and only runs
// when E2E_LIVE=true so we never silently fake a backend round-trip.
export default defineConfig({
  testDir: './tests/e2e',
  fullyParallel: true,
  // Each spec collects console / pageerror noise into its own listener,
  // so we do not want Playwright to retry on flakes — a single failed
  // assertion is enough to surface a regression.
  retries: 0,
  reporter: 'list',
  timeout: 30_000,
  expect: { timeout: 5_000 },
  use: {
    baseURL: 'http://127.0.0.1:5173',
    trace: 'retain-on-failure',
    // Task 14 hard rule: every page is asserted at the demo resolution.
    viewport: { width: 1280, height: 720 },
  },
  webServer: {
    command: 'npm run dev',
    url: 'http://127.0.0.1:5173',
    reuseExistingServer: true,
    timeout: 120_000,
  },
  projects: [
    {
      name: 'chromium',
      use: { browserName: 'chromium' },
    },
  ],
});