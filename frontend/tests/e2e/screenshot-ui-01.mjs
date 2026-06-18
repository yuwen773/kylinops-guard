// UI-01 screenshot harness.
//
// Captures Dashboard / SecurityCenter / ToolCenter / ChatConsole (AppLayout)
// at 1280x720 demo resolution with mocked /api/** responses. Writes PNGs to
// `tests/e2e/screenshots/{theme}/ui-01-*.png` for each requested theme.
//
// Run via the npm script:
//   npm run screenshot:ui01            (dark, default, backward-compat)
//   npm run screenshot:ui01:dark
//   npm run screenshot:ui01:light
//   npm run screenshot:ui01:all        (both themes)
//
// Or directly:
//   node tests/e2e/screenshot-ui-01.mjs
//   node tests/e2e/screenshot-ui-01.mjs --theme light

import { chromium } from 'playwright';
import { mkdir } from 'node:fs/promises';
import { dirname, resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const OUT_DIR = resolve(__dirname, 'screenshots');
const BASE_URL = process.env.E2E_BASE_URL ?? 'http://127.0.0.1:5173';

const PAGES = [
  { id: 'app-layout', path: '/chat', file: 'ui-01-01-applayout-chat.png' },
  { id: 'dashboard', path: '/dashboard', file: 'ui-01-02-dashboard.png' },
  { id: 'security', path: '/security', file: 'ui-01-03-security-center.png' },
  { id: 'tools', path: '/tools', file: 'ui-01-04-tool-center.png' },
];

// ---- Theme selection ------------------------------------------------------
// --theme=light / --theme=dark / --theme=all
const themeArg = (process.argv.find((a) => a.startsWith('--theme=')) ?? '--theme=dark')
  .split('=')[1];
const themes = themeArg === 'all' ? ['dark', 'light'] : [themeArg];

// ---- Mock /api/** responses (identical to before) -------------------------
const now = '2026-06-17T08:00:00Z';

await mkdir(OUT_DIR, { recursive: true });
for (const t of themes) {
  await mkdir(join(OUT_DIR, t), { recursive: true });
}

const browser = await chromium.launch({ channel: 'chromium' });
const context = await browser.newContext({ viewport: { width: 1280, height: 720 } });
const page = await context.newPage();

// Auth bypass: mock /api/auth/session so the route guard treats us as logged in.
// The mock shape mirrors com.kylinops.auth.AuthSession on the backend.
await context.route(/^https?:\/\/[^/]+\/api(?:\/|$)/, async (route) => {
  const url = route.request().url();
  const method = route.request().method();
  if (url.endsWith('/api/auth/session') && method === 'GET') {
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 200,
        message: 'ok',
        data: {
          username: 'kylinops-admin',
          csrfToken: 'csrf-screenshot-001',
          loginAt: now,
          expiresAt: '2026-06-17T20:00:00Z',
          idleTimeout: 1800,
        },
        timestamp: Date.now(),
      }),
    });
  }
  if (url.endsWith('/api/health')) {
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ status: 'UP' }) });
  }
  if (url.endsWith('/api/dashboard/overview')) {
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 200, message: 'ok', timestamp: Date.now(),
        data: {
          score: 86, successfulMetricCount: 9, totalMetricCount: 10, degraded: true,
          auditId: 'audit-shot-001',
          collectedAt: now,
          metrics: [
            { toolName: 'cpu_status_tool', status: 'success', data: { usagePercent: 42, loadAvg1: 0.85 }, durationMs: 12 },
            { toolName: 'memory_status_tool', status: 'success', data: { totalMB: 8192, usedMB: 5200, usedPercent: 63 }, durationMs: 8 },
            { toolName: 'disk_usage_tool', status: 'success', data: { partitions: [{ mount: '/', usedPercent: 86, size: '100G', used: '86G' }] }, durationMs: 14 },
            { toolName: 'service_status_tool', status: 'success', data: { serviceName: 'nginx', activeState: 'inactive', subState: 'dead' }, durationMs: 9 },
            { toolName: 'network_port_tool', status: 'success', data: { ports: [] }, durationMs: 7 },
            { toolName: 'system_info_tool', status: 'success', data: { hostname: 'kylin-v11', osName: 'Kylin Advanced Server V11', arch: 'loongarch64' }, durationMs: 6 },
            { toolName: 'process_list_tool', status: 'success', data: { processes: [] }, durationMs: 5 },
            { toolName: 'large_file_scan_tool', status: 'success', data: { files: [] }, durationMs: 11 },
            { toolName: 'process_detail_tool', status: 'success', data: null, durationMs: 4 },
            { toolName: 'journal_log_tool', status: 'failed', errorMessage: 'journalctl 不可用', durationMs: 3000 },
          ],
        },
      }),
    });
  }
  if (/\/api\/tools(\?|$)/.test(url)) {
    const tools = [
      'system_info_tool', 'cpu_status_tool', 'memory_status_tool',
      'disk_usage_tool', 'large_file_scan_tool', 'process_list_tool',
      'process_detail_tool', 'network_port_tool', 'service_status_tool',
      'journal_log_tool',
    ].map((toolName, i) => ({
      toolName,
      description: `${toolName} - 注册的 OpsTool`,
      inputSchema: '{"type":"object","properties":{}}',
      outputSchema: '{"type":"object"}',
      riskLevel: ['L0','L0','L1','L1','L1','L0','L1','L0','L1','L1'][i] ?? 'L1',
      permissionType: 'READ',
      toolStatus: 'ENABLED',
      timeoutMs: 3000,
      auditRequired: true,
      callCount: i * 3,
      successRate: i % 3 === 0 ? null : 0.95,
      lastCalledAt: i % 2 === 0 ? now : null,
    }));
    return route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({ code: 200, message: 'ok', timestamp: Date.now(), data: tools }),
    });
  }
  if (url.endsWith('/api/security/risk-levels')) {
    return route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({
        code: 200, message: 'ok', timestamp: Date.now(),
        data: [
          { level: 'L0', decision: 'ALLOW', description: '只读查询', examples: ['df -h', '查看磁盘状态'] },
          { level: 'L1', decision: 'ALLOW', description: '放行并审计', examples: ['查看服务日志'] },
          { level: 'L2', decision: 'CONFIRM', description: '用户确认后执行', examples: ['重启 nginx'] },
          { level: 'L3', decision: 'BLOCK', description: '高风险阻断', examples: ['修改系统配置'] },
          { level: 'L4', decision: 'BLOCK', description: '绝对阻断', examples: ['rm -rf /', 'chmod -R 777 /'] },
        ],
      }),
    });
  }
  if (url.endsWith('/api/security/rules')) {
    const rules = [
      { ruleId: 'rm-rf-root', name: 'rm-rf-root', description: '阻断 rm -rf /', regex: 'rm\\s+-rf\\s+/', riskLevel: 'L4', riskDecision: 'BLOCK', reason: '删除根目录', safeSuggestion: '请明确目标目录', enabled: true, priority: 100 },
      { ruleId: 'chmod-777', name: 'chmod-777', description: '阻断 chmod 777', regex: 'chmod\\s+-R\\s+777', riskLevel: 'L4', riskDecision: 'BLOCK', reason: '开放权限', safeSuggestion: '使用更细粒度权限', enabled: true, priority: 99 },
      { ruleId: 'service-restart', name: 'service-restart', description: '服务重启需确认', regex: 'systemctl\\s+restart', riskLevel: 'L2', riskDecision: 'CONFIRM', reason: '影响服务可用性', safeSuggestion: '在低峰期执行', enabled: true, priority: 50 },
      { ruleId: 'prompt-inject', name: 'prompt-inject', description: '提示词注入检测', regex: '忽略|绕过|关闭安全', riskLevel: 'L4', riskDecision: 'BLOCK', reason: '注入企图', safeSuggestion: '拒绝执行', enabled: true, priority: 200 },
    ];
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'ok', timestamp: Date.now(), data: rules }) });
  }
  if (url.endsWith('/api/security/events')) {
    return route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({
        code: 200, message: 'ok', timestamp: Date.now(),
        data: {
          content: [
            { auditId: 'audit-block-shot-001', riskLevel: 'L4', decision: 'BLOCK', matchedRules: ['rm-rf-root'], reason: '命中绝对阻断规则 rm -rf /', createdAt: now, toolName: 'rm -rf /' },
            { auditId: 'audit-block-shot-002', riskLevel: 'L4', decision: 'BLOCK', matchedRules: ['chmod-777'], reason: '命中绝对阻断规则 chmod 777', createdAt: now, toolName: 'chmod -R 777 /' },
          ],
          totalElements: 2, totalPages: 1, number: 0, size: 20,
        },
      }),
    });
  }
  return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'ok', timestamp: Date.now(), data: null }) });
});

// Pre-seed localStorage so the page loads in the correct theme on first paint
await context.addInitScript((theme) => {
  try { localStorage.setItem('kg-theme', theme); } catch {}
}, themes[0]);

for (const theme of themes) {
  // eslint-disable-next-line no-console
  console.log(`\n=== theme: ${theme} ===`);
  for (const target of PAGES) {
    // Re-seed localStorage per-theme before each navigation
    await context.addInitScript((t) => {
      try { localStorage.setItem('kg-theme', t); } catch {}
    }, theme);
    await page.goto(`${BASE_URL}${target.path}`, { waitUntil: 'networkidle' });
    await page.waitForTimeout(150);
    const file = resolve(OUT_DIR, theme, target.file);
    await page.screenshot({ path: file, fullPage: false });
    // eslint-disable-next-line no-console
    console.log(`saved ${theme}/${target.file}`);
  }
}

await browser.close();
