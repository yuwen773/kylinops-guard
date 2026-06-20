// Landing page screenshot harness.
//
// Captures the P1-03 public marketing landing page at desktop (1280x720) and/or
// mobile (375x667) viewport sizes, with /api/auth/session mocked (anonymous 401).
// Writes PNGs to tests/e2e/screenshots/{theme}/ for each (theme, viewport) pair.
//
// Run via:
//   node tests/e2e/screenshot-landing.mjs --theme=dark --viewport=desktop
//   node tests/e2e/screenshot-landing.mjs --theme=light --viewport=mobile
//   node tests/e2e/screenshot-landing.mjs --theme=all  --viewport=all

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

const viewportArg = (process.argv.find((a) => a.startsWith('--viewport=')) ?? '--viewport=all')
  .split('=')[1];

const VIEWPORTS = {
  desktop: { width: 1280, height: 720, suffix: '' },
  mobile: { width: 375, height: 667, suffix: '-mobile' },
};

const viewports =
  viewportArg === 'all'
    ? [VIEWPORTS.desktop, VIEWPORTS.mobile]
    : [VIEWPORTS[viewportArg] ?? VIEWPORTS.desktop];

async function captureTheme(theme) {
  await mkdir(resolve(OUT_DIR, theme), { recursive: true });

  const browser = await chromium.launch({ channel: 'chromium' });

  for (const vp of viewports) {
    const context = await browser.newContext({ viewport: vp });
    // Set theme preference via localStorage before page loads.
    // The actual key in useTheme.ts is `kg-theme`.
    await context.addInitScript((t) => {
      try {
        window.localStorage.setItem('kg-theme', t);
      } catch {}
    }, theme);

    const page = await context.newPage();
    // Mock auth so the guard does not redirect away from /landing.
    await page.route('**/api/auth/session', async (route) => {
      await route.fulfill({
        status: 401,
        contentType: 'application/json',
        body: JSON.stringify({ error: 'unauthenticated' }),
      });
    });

    await page.goto(`${BASE_URL}/landing`, { waitUntil: 'networkidle' });
    // Wait for hero to be in viewport so the screenshot is meaningful.
    await page.waitForSelector('[data-testid="landing-hero"]', { state: 'visible', timeout: 10000 });
    await page.waitForTimeout(400);

    const outPath = resolve(OUT_DIR, theme, `ui-01-05-landing${vp.suffix}.png`);
    await page.screenshot({ path: outPath, fullPage: false });
    console.log(`  ${theme}${vp.suffix}: ${outPath}`);

    await context.close();
  }

  await browser.close();
}

console.log(`Capturing landing page screenshots (themes: ${themes.join(', ')}; viewports: ${viewports.map((v) => `${v.width}x${v.height}`).join(', ')})`);
for (const theme of themes) {
  await captureTheme(theme);
}
console.log('Done.');