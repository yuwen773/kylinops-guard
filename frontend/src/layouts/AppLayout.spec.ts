import { afterEach, describe, expect, it } from 'vitest';
import { mount, enableAutoUnmount } from '@vue/test-utils';
import { createRouter, createMemoryHistory, type Router } from 'vue-router';
import AppLayout from './AppLayout.vue';
import ElementPlus from 'element-plus';

// The product name is mandated by the v0.1 PRD — change is a spec deviation.
const PRODUCT_NAME = '麒麟安全智能运维 Agent';

// Mandated sidebar order. Locked by the Phase 2 task card and the demo video script.
const NAV_ITEMS: ReadonlyArray<{ path: string; label: string }> = [
  { path: '/chat', label: '对话控制台' },
  { path: '/dashboard', label: '系统总览' },
  { path: '/tools', label: '工具中心' },
  { path: '/security', label: '安全中心' },
  { path: '/audit', label: '审计日志' },
  { path: '/reports', label: '报告中心' },
];

function buildRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', redirect: '/chat' },
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
