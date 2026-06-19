import { afterEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount, enableAutoUnmount } from '@vue/test-utils';
import { createRouter, createMemoryHistory, type Router } from 'vue-router';
import AppLayout from './AppLayout.vue';
import ElementPlus from 'element-plus';
import * as authApi from '@/api/auth';
import { clearSession, setSession } from '@/auth/session';

// The product name is mandated by the v0.1 PRD — change is a spec deviation.
const PRODUCT_NAME = '麒麟安全智能运维 Agent';

// Mandated sidebar order. Locked by the Phase 2 task card and the demo video script.
// P1-01 Task 6 added the 通知配置 entry.
const NAV_ITEMS: ReadonlyArray<{ path: string; label: string }> = [
  { path: '/chat', label: '对话控制台' },
  { path: '/dashboard', label: '系统总览' },
  { path: '/tools', label: '工具中心' },
  { path: '/security', label: '安全中心' },
  { path: '/audit', label: '审计日志' },
  { path: '/reports', label: '报告中心' },
  { path: '/notification-settings', label: '通知配置' },
];

function buildRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', redirect: '/chat' },
      { path: '/login', component: { template: '<div data-testid="login-stub" />' } },
      ...NAV_ITEMS.map((item) => ({
        path: item.path,
        name: item.path.slice(1),
        component: { template: '<div data-testid="page" />' },
      })),
    ],
  });
}

describe('AppLayout', () => {
  enableAutoUnmount(afterEach);

  it('renders the mandated product name in the topbar', async () => {
    const router = buildRouter();
    router.push('/chat');
    await router.isReady();

    const wrapper = mount(AppLayout, {
      global: { plugins: [router, ElementPlus] },
    });
    expect(wrapper.text()).toContain(PRODUCT_NAME);
  });

  it('renders all six navigation labels in mandated order', async () => {
    const router = buildRouter();
    router.push('/chat');
    await router.isReady();

    const wrapper = mount(AppLayout, {
      global: { plugins: [router, ElementPlus] },
    });

    // Element Plus renders sidebar items as .el-menu-item elements. We assert
    // via text-content order rather than DOM structure so the test is stable
    // against minor style refactors.
    const menuItems = wrapper.findAll('.el-menu-item');
    expect(menuItems).toHaveLength(NAV_ITEMS.length);

    const rendered = menuItems.map((node) => node.text().trim());
    const expected = NAV_ITEMS.map((i) => i.label);
    expect(rendered).toEqual(expected);
  });

  it('exposes router-view so each route can mount its page', async () => {
    const router = buildRouter();
    router.push('/dashboard');
    await router.isReady();

    const wrapper = mount(AppLayout, {
      global: { plugins: [router, ElementPlus] },
    });

    // router-view renders the matched component. With our test routes that
    // component emits a data-testid="page" element.
    expect(wrapper.find('[data-testid="page"]').exists()).toBe(true);
  });

  it('highlights the active nav item for the current route', async () => {
    const router = buildRouter();
    router.push('/security');
    await router.isReady();

    const wrapper = mount(AppLayout, {
      global: { plugins: [router, ElementPlus] },
    });

    const menuItems = wrapper.findAll('.el-menu-item');
    const activeItem = menuItems.find((node) =>
      node.classes().includes('is-active'),
    );
    expect(activeItem?.text().trim()).toBe('安全中心');
  });
});

describe('AppLayout — logout button (P2-T5)', () => {
  // NOTE: `enableAutoUnmount` is process-global in vue-test-utils — the
  // previous `describe` already enabled it, so we only need afterEach
  // cleanup of the session + spies here.

  afterEach(() => {
    clearSession();
    vi.restoreAllMocks();
  });

  it('renders the logout button in the topbar', async () => {
    const router = buildRouter();
    router.push('/chat');
    await router.isReady();
    setSession({
      username: 'admin',
      csrfToken: 'csrf-1',
      loginAt: '2026-06-14T00:00:00Z',
      expiresAt: '2026-06-14T12:00:00Z',
      idleTimeout: 1800,
    });

    const wrapper = mount(AppLayout, {
      global: { plugins: [router, ElementPlus] },
    });

    const btn = wrapper.find('[data-testid="app-logout"]');
    expect(btn.exists()).toBe(true);
    expect(btn.text()).toContain('登出');
  });

  it('shows the current username when a session is present', async () => {
    const router = buildRouter();
    router.push('/chat');
    await router.isReady();
    setSession({
      username: 'kylinops-admin',
      csrfToken: 'csrf-1',
      loginAt: '2026-06-14T00:00:00Z',
      expiresAt: '2026-06-14T12:00:00Z',
      idleTimeout: 1800,
    });

    const wrapper = mount(AppLayout, {
      global: { plugins: [router, ElementPlus] },
    });
    expect(wrapper.find('[data-testid="app-user"]').text()).toBe('kylinops-admin');
  });

  it('calls auth.logout and navigates to /login when the button is clicked', async () => {
    const router = buildRouter();
    router.push('/chat');
    await router.isReady();
    setSession({
      username: 'admin',
      csrfToken: 'csrf-1',
      loginAt: '2026-06-14T00:00:00Z',
      expiresAt: '2026-06-14T12:00:00Z',
      idleTimeout: 1800,
    });

    const logoutSpy = vi.spyOn(authApi, 'logout').mockResolvedValue(undefined);
    const navSpy = vi.spyOn(router, 'replace');

    const wrapper = mount(AppLayout, {
      global: { plugins: [router, ElementPlus] },
    });

    await wrapper.find('[data-testid="app-logout"]').trigger('click');
    await flushPromises();

    expect(logoutSpy).toHaveBeenCalledTimes(1);
    expect(navSpy).toHaveBeenCalledWith('/login');
  });
});
