import { defineConfig } from '@playwright/test';

// Playwright E2E config.
// `baseURL` must point at the Vite dev server (default 127.0.0.1:5173).
// Run `npm run dev` (or `npm run preview` after a build) before `test:e2e`.
export default defineConfig({
  testDir: './tests/e2e',
  fullyParallel: true,
  reporter: 'list',
  use: {
    baseURL: 'http://127.0.0.1:5173',
    trace: 'on-first-retry',
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