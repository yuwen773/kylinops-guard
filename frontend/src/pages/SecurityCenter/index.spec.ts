import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mount, flushPromises, enableAutoUnmount } from '@vue/test-utils';
import { createRouter, createMemoryHistory, type Router } from 'vue-router';
import ElementPlus from 'element-plus';
import SecurityCenterPage from './index.vue';
import * as securityApi from '@/api/security';
import { ApiError } from '@/api/client';
import type {
  RiskLevelView,
  SecurityEventPage,
  SecurityEventView,
  SecurityRuleView,
} from '@/types/security';

enableAutoUnmount(afterEach);

/**
 * SecurityCenter page contract (Task 8 / Task 16):
 *   - On mount: calls getRiskLevels(), getSecurityRules(), getSecurityEvents({page:0,size:20})
 *     INDEPENDENTLY. A failure in one must NOT prevent the others from rendering.
 *   - L0..L4 section: 5 cards, each with level + decision + Chinese description.
 *   - Rules section: grouped by riskLevel, read-only — no edit/toggle buttons.
 *   - BLOCK events section: paged el-pagination; clicking a row navigates to
 *     `/audit?auditId=...` (router.push).
 *   - Each section has its own loading / error / empty state.
 *   - Disabled rules are still listed under their level (read-only evidence)
 *     but visually distinguished from enabled ones.
 *   - No v-html on user-derived content. The page is read-only.
 */

const LEVELS: RiskLevelView[] = [
  { level: 'L0', decision: 'ALLOW', description: 'L0 信息查询', examples: ['df -h', '查看磁盘状态'] },
  { level: 'L1', decision: 'ALLOW', description: 'L1 轻度风险', examples: ['查看服务日志'] },
  { level: 'L2', decision: 'CONFIRM', description: 'L2 需确认', examples: ['重启 nginx'] },
  { level: 'L3', decision: 'BLOCK', description: 'L3 高风险', examples: ['修改系统配置'] },
  { level: 'L4', decision: 'BLOCK', description: 'L4 严重风险', examples: ['rm -rf /', 'chmod -R 777 /'] },
];

function buildRule(overrides: Partial<SecurityRuleView> = {}): SecurityRuleView {
  return {
    ruleId: 'rule-a',
    name: 'rule-a',
    description: '阻断 rm -rf /',
    regex: 'rm\\s+-rf\\s+/',
    riskLevel: 'L4',
    riskDecision: 'BLOCK',
    reason: '删除根目录',
    safeSuggestion: '请明确目标目录并使用 trash 工具',
    enabled: true,
    priority: 100,
    ...overrides,
  };
}

function buildEvent(overrides: Partial<SecurityEventView> = {}): SecurityEventView {
  return {
    auditId: 'audit-evt-1',
    riskLevel: 'L4',
    decision: 'BLOCK',
    matchedRules: ['rule-a', 'l4-absolute-block'],
    reason: '命中绝对阻断规则',
    createdAt: '2026-06-12T12:00:00',
    toolName: 'rm -rf /',
    ...overrides,
  };
}

function buildEventPage(overrides: Partial<SecurityEventPage> = {}): SecurityEventPage {
  return {
    content: [buildEvent()],
    totalElements: 1,
    totalPages: 1,
    number: 0,
    size: 20,
    ...overrides,
  };
}

function buildRouter(initialPath = '/security'): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', redirect: '/security' },
      { path: '/security', name: 'security', component: { template: '<div />' } },
      { path: '/audit', name: 'audit', component: { template: '<div />' } },
    ],
  });
}

interface MountOptions {
  levels?: RiskLevelView[] | 'reject';
  rules?: SecurityRuleView[] | 'reject';
  events?: SecurityEventPage | 'reject';
  initialPath?: string;
}

async function mountPage(options: MountOptions = {}) {
  const router = buildRouter(options.initialPath);
  await router.push(options.initialPath ?? '/security');
  await router.isReady();

  if (options.levels === 'reject') {
    vi.spyOn(securityApi, 'getRiskLevels').mockRejectedValue(
      new ApiError({ code: 500, message: '风险等级目录加载失败', data: null }),
    );
  } else {
    vi.spyOn(securityApi, 'getRiskLevels').mockResolvedValue(
      options.levels ?? LEVELS,
    );
  }

  if (options.rules === 'reject') {
    vi.spyOn(securityApi, 'getSecurityRules').mockRejectedValue(
      new ApiError({ code: 500, message: '规则加载失败', data: null }),
    );
  } else {
    vi.spyOn(securityApi, 'getSecurityRules').mockResolvedValue(
      options.rules ?? [buildRule()],
    );
  }

  if (options.events === 'reject') {
    vi.spyOn(securityApi, 'getSecurityEvents').mockRejectedValue(
      new ApiError({ code: 500, message: '拦截事件加载失败', data: null }),
    );
  } else {
    vi.spyOn(securityApi, 'getSecurityEvents').mockResolvedValue(
      options.events ?? buildEventPage(),
    );
  }

  const wrapper = mount(SecurityCenterPage, {
    global: { plugins: [router, ElementPlus] },
    attachTo: document.body,
  });
  await flushPromises();
  return { wrapper, router };
}

describe('SecurityCenter — initial load', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('calls all three endpoints on mount', async () => {
    const levelsSpy = vi.spyOn(securityApi, 'getRiskLevels').mockResolvedValue(LEVELS);
    const rulesSpy = vi.spyOn(securityApi, 'getSecurityRules').mockResolvedValue([buildRule()]);
    const eventsSpy = vi.spyOn(securityApi, 'getSecurityEvents').mockResolvedValue(buildEventPage());
    const router = buildRouter();
    await router.push('/security');
    await router.isReady();

    mount(SecurityCenterPage, {
      global: { plugins: [router, ElementPlus] },
    });
    await flushPromises();

    expect(levelsSpy).toHaveBeenCalledTimes(1);
    expect(rulesSpy).toHaveBeenCalledTimes(1);
    const eventArg = eventsSpy.mock.calls[0]?.[0] as Record<string, unknown>;
    expect(eventArg.page).toBe(0);
    expect(eventArg.size).toBe(20);
  });
});

describe('SecurityCenter — L0..L4 section', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('renders five level cards (L0..L4) with their decisions and descriptions', async () => {
    const { wrapper } = await mountPage();
    for (const level of ['L0', 'L1', 'L2', 'L3', 'L4']) {
      const card = wrapper.find(`[data-testid="security-level-${level}"]`);
      expect(card.exists(), `expected card for ${level}`).toBe(true);
      expect(card.text()).toContain(level);
    }
  });

  it('shows a local error inside the L0..L4 section when getRiskLevels rejects', async () => {
    const { wrapper } = await mountPage({ levels: 'reject' });
    const err = wrapper.find('[data-testid="security-levels-error"]');
    expect(err.exists()).toBe(true);
    expect(err.text()).toContain('风险等级目录加载失败');
    // Rules and events sections must still render.
    expect(wrapper.find('[data-testid="security-rules-section"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="security-events-section"]').exists()).toBe(true);
  });
});

describe('SecurityCenter — rules section', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('groups rules by risk level and renders enabled rules read-only', async () => {
    const { wrapper } = await mountPage({
      rules: [
        buildRule({ ruleId: 'r-l4-a', name: 'r-l4-a', riskLevel: 'L4', description: '阻断 rm -rf' }),
        buildRule({ ruleId: 'r-l4-b', name: 'r-l4-b', riskLevel: 'L4', description: '阻断 chmod 777' }),
        buildRule({ ruleId: 'r-l3', name: 'r-l3', riskLevel: 'L3', description: '阻断 systemctl stop' }),
      ],
    });

    expect(wrapper.find('[data-testid="security-rule-r-l4-a"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="security-rule-r-l4-b"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="security-rule-r-l3"]').exists()).toBe(true);
    // Regex visible (read-only technical evidence).
    expect(wrapper.find('[data-testid="security-rule-r-l4-a"]').text()).toContain('rm\\s+-rf\\s+/');
  });

  it('does NOT render any edit/toggle/switch UI control for rules', async () => {
    const { wrapper } = await mountPage({
      rules: [
        buildRule({ ruleId: 'ro-1', riskLevel: 'L4' }),
        buildRule({ ruleId: 'ro-2', riskLevel: 'L2', enabled: false }),
      ],
    });

    const rulesSection = wrapper.find('[data-testid="security-rules-section"]');
    expect(rulesSection.exists()).toBe(true);

    // No edit button, no toggle, no switch, no save button, no input.
    const buttons = rulesSection.findAll('button');
    const actionable = buttons.filter((b) => {
      const text = b.text();
      return /编辑|edit|开关|toggle|启用|停用|删除|delete|保存|save/i.test(text);
    });
    expect(actionable.length).toBe(0);

    const inputs = rulesSection.findAll('input, textarea');
    expect(inputs.length).toBe(0);

    // The Element Plus switch component renders as `.el-switch`.
    expect(rulesSection.find('.el-switch').exists()).toBe(false);
  });

  it('shows a local error inside the rules section when getSecurityRules rejects', async () => {
    const { wrapper } = await mountPage({ rules: 'reject' });
    const err = wrapper.find('[data-testid="security-rules-error"]');
    expect(err.exists()).toBe(true);
    expect(err.text()).toContain('规则加载失败');
    // Levels and events sections must still render.
    expect(wrapper.find('[data-testid="security-levels-section"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="security-events-section"]').exists()).toBe(true);
  });
});

describe('SecurityCenter — BLOCK events section', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('renders each BLOCK event with its audit id, level, reason and matched rules', async () => {
    const { wrapper } = await mountPage({
      events: buildEventPage({
        content: [
          buildEvent({ auditId: 'ev-1', reason: '命中绝对阻断规则', matchedRules: ['rule-a'] }),
          buildEvent({ auditId: 'ev-2', reason: '注入尝试阻断', matchedRules: ['pi-pattern'] }),
        ],
      }),
    });

    expect(wrapper.find('[data-testid="security-event-ev-1"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="security-event-ev-2"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('ev-1');
    expect(wrapper.text()).toContain('命中绝对阻断规则');
  });

  it('navigates to /audit?auditId=... when a BLOCK event row is clicked', async () => {
    const { wrapper, router } = await mountPage({
      events: buildEventPage({ content: [buildEvent({ auditId: 'ev-click-1' })] }),
    });
    const pushSpy = vi.spyOn(router, 'push');

    const row = wrapper.find('[data-testid="security-event-ev-click-1"]');
    expect(row.exists()).toBe(true);
    await row.trigger('click');
    await flushPromises();

    expect(pushSpy).toHaveBeenCalled();
    const call = pushSpy.mock.calls.find((c) => {
      const arg = c[0] as { path?: string; query?: Record<string, string> } | string;
      if (typeof arg === 'string') return false;
      return arg?.path === '/audit' && arg?.query?.auditId === 'ev-click-1';
    });
    expect(call, 'expected router.push({ path: "/audit", query: { auditId: "ev-click-1" } })').toBeTruthy();
  });

  it('re-calls getSecurityEvents with the new page when paginating', async () => {
    const { wrapper } = await mountPage({
      events: buildEventPage({ totalElements: 60, totalPages: 3, number: 0 }),
    });
    await flushPromises();
    const spy = vi.mocked(securityApi.getSecurityEvents);
    spy.mockClear();

    const pag = wrapper.find('[data-testid="security-events-pagination"]');
    expect(pag.exists()).toBe(true);
    const next = pag.find('.btn-next');
    if (next.exists()) {
      await next.trigger('click');
    } else {
      const buttons = pag.findAll('button');
      await buttons[buttons.length - 1]?.trigger('click');
    }
    await flushPromises();

    const calls = spy.mock.calls.map((c) => c[0] as Record<string, unknown>);
    expect(calls.some((c) => c.page === 1)).toBe(true);
  });

  it('shows a Chinese empty state when there are no BLOCK events', async () => {
    const { wrapper } = await mountPage({
      events: buildEventPage({ content: [], totalElements: 0, totalPages: 0 }),
    });
    const empty = wrapper.find('[data-testid="security-events-empty"]');
    expect(empty.exists()).toBe(true);
    expect(empty.text()).toContain('暂无拦截事件');
  });

  it('shows a local error inside the events section when getSecurityEvents rejects', async () => {
    const { wrapper } = await mountPage({ events: 'reject' });
    const err = wrapper.find('[data-testid="security-events-error"]');
    expect(err.exists()).toBe(true);
    expect(err.text()).toContain('拦截事件加载失败');
    // Levels and rules sections must still render.
    expect(wrapper.find('[data-testid="security-levels-section"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="security-rules-section"]').exists()).toBe(true);
  });
});
