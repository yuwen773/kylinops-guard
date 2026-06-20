import { afterEach, describe, expect, it } from 'vitest';
import { flushPromises, mount, enableAutoUnmount } from '@vue/test-utils';
import {
  createRouter,
  createMemoryHistory,
  type Router,
} from 'vue-router';
import ElementPlus from 'element-plus';
import Landing from './index.vue';

function buildRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div data-testid="home" />' } },
      { path: '/login', component: { template: '<div data-testid="login" />' } },
      { path: '/landing', component: { template: '<div data-testid="landing-stub" />' } },
    ],
  });
}

async function mountLanding() {
  const router = buildRouter();
  router.push('/landing');
  await router.isReady();
  const wrapper = mount(Landing, {
    global: { plugins: [router, ElementPlus] },
  });
  await flushPromises();
  return { wrapper, router };
}

describe('Landing page', () => {
  enableAutoUnmount(afterEach);

  it('mounts the landing root container', async () => {
    const { wrapper } = await mountLanding();
    expect(wrapper.find('[data-testid="landing-page"]').exists()).toBe(true);
  });

  it('renders all 9 sections in order', async () => {
    const { wrapper } = await mountLanding();
    // 1. nav, 2. hero, 3. trust (no testid but title), 4. bento,
    // 5. closed loop, 6. scenarios, 7. tool matrix, 8. cta, 9. footer
    expect(wrapper.find('[data-testid="landing-nav"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="landing-hero"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="landing-bento"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="closed-loop"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="landing-scenarios"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="landing-tool-matrix"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="landing-cta"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="landing-footer"]').exists()).toBe(true);
  });

  it('renders 6 bento cells with distinct testids', async () => {
    const { wrapper } = await mountLanding();
    for (const span of ['a', 'b', 'c', 'd', 'e', 'f']) {
      expect(wrapper.find(`[data-testid="bento-cell-${span}"]`).exists()).toBe(true);
    }
  });

  it('renders 4 demo scenario cards with risk tags', async () => {
    const { wrapper } = await mountLanding();
    for (const num of ['01', '02', '03', '04']) {
      const card = wrapper.find(`[data-testid="scenario-${num}"]`);
      expect(card.exists()).toBe(true);
      // Each scenario must render a real RiskLevelTag (el-tag with risk-level-* testid).
      const tag = card.find('[data-testid^="risk-level-"]');
      expect(tag.exists()).toBe(true);
    }
  });

  it('renders 10 tool rows in the tool matrix', async () => {
    const { wrapper } = await mountLanding();
    const matrix = wrapper.find('[data-testid="landing-tool-matrix"]');
    expect(matrix.exists()).toBe(true);
    // All 10 tools declared in the PRD P0 set.
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
      expect(matrix.text()).toContain(name);
    }
  });

  it('renders ConsolePreview with real ToolCallCards', async () => {
    const { wrapper } = await mountLanding();
    const hero = wrapper.find('[data-testid="landing-hero"]');
    // ConsolePreview must render at least one real tool-call card.
    const toolCallCard = hero.find('[data-testid^="tool-call-"]');
    expect(toolCallCard.exists()).toBe(true);
  });

  it('marks ConsolePreview data as sample fixture (honesty rule)', async () => {
    const { wrapper } = await mountLanding();
    const text = wrapper.text();
    // design-taste-frontend §9.D: fabricated data must be flagged.
    expect(text).toContain('示例数据');
    expect(text).toContain('sample fixture');
  });

  it('navigates to /login when the hero primary CTA is clicked', async () => {
    const { wrapper, router } = await mountLanding();
    const cta = wrapper.find('[data-testid="hero-cta-primary"]');
    expect(cta.exists()).toBe(true);
    await cta.trigger('click');
    await flushPromises();
    expect(router.currentRoute.value.path).toBe('/login');
  });

  it('navigates to /login when the CTA banner primary is clicked', async () => {
    const { wrapper, router } = await mountLanding();
    const cta = wrapper.find('[data-testid="cta-primary"]');
    expect(cta.exists()).toBe(true);
    await cta.trigger('click');
    await flushPromises();
    expect(router.currentRoute.value.path).toBe('/login');
  });

  it('navigates to /login when the nav CTA is clicked', async () => {
    const { wrapper, router } = await mountLanding();
    const cta = wrapper.find('[data-testid="landing-nav-cta"]');
    expect(cta.exists()).toBe(true);
    await cta.trigger('click');
    await flushPromises();
    expect(router.currentRoute.value.path).toBe('/login');
  });

  it('does not contain any em-dash characters in visible copy', async () => {
    const { wrapper } = await mountLanding();
    // design-taste-frontend §9.G zero-em-dash rule.
    const text = wrapper.text();
    expect(text).not.toContain('—');
    expect(text).not.toContain('–');
  });
});
