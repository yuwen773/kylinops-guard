// Fix-01 RCA E2E — demo scenario 2 端到端可见 ReasoningChain。
//
// 验证：
//   * 输入「帮我看看磁盘为什么快满了」触发 DISK_DIAGNOSIS
//   * 后端返回的 rootCauseChain 在 ChatConsole 渲染为 <ReasoningChain>
//   * 标题为「根因分析链」（来自 rcaTitleFor）
//   * 关键字段：symptom / conclusion / evidence source 都可见

import { test, expect } from '@playwright/test';
import {
  mockApiResponse,
  mockAuthSession,
  mockDiskAgentResult,
} from './fixtures';

test('RCA 推理链在演示场景 2 磁盘诊断中可见', async ({ page }) => {
  // 登录态（mock — P2-T5 router guard 拉 /api/auth/session）
  await page.route('**/api/auth/session', (route) =>
    route.fulfill({ json: mockAuthSession() }),
  );

  // 拦截 /api/chat/send：返回带 rootCauseChain 的 disk result
  await page.route('**/api/chat/send', (route) =>
    route.fulfill({
      json: mockApiResponse(mockDiskAgentResult('audit-rca-disk-001')),
    }),
  );

  await page.goto('/chat');

  // 触发演示场景 2
  const input = page.getByTestId('chat-input-field');
  await input.fill('帮我看看磁盘为什么快满了');
  await input.press('Enter');

  // 等待 RCA 组件渲染
  const rca = page.getByTestId('chat-rca-audit-rca-disk-001');
  await expect(rca).toBeVisible({ timeout: 10000 });

  // 标题
  await expect(rca.getByText('根因分析链')).toBeVisible();

  // symptom（来自 rootCauseChain.symptom）
  await expect(rca.getByTestId('rc-symptom')).toContainText('86%');

  // conclusion（指向 /var/log/app.log）
  await expect(rca.getByTestId('rc-conclusion')).toContainText('/var/log/app.log');

  // evidence 来源
  await expect(rca).toContainText('disk_usage_tool');
  await expect(rca).toContainText('large_file_scan_tool');

  // excluded cause（敏感数据库目录）
  await expect(rca).toContainText('/var/lib/mysql');
});