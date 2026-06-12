import { afterEach, describe, expect, it, vi } from 'vitest';
import { mount, enableAutoUnmount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import ExecutionConfirmCard from './index.vue';
import type { RiskDecision, RiskLevel } from '@/types/safety';

enableAutoUnmount(afterEach);

function mountCard(props: {
  actionId?: string;
  summary?: string;
  riskLevel?: RiskLevel;
  decision?: RiskDecision;
  detail?: string;
} = {}) {
  return mount(ExecutionConfirmCard, {
    props: {
      actionId: props.actionId ?? 'act-001',
      summary: props.summary ?? '重启 nginx 服务',
      riskLevel: props.riskLevel ?? 'L2',
      decision: props.decision ?? 'CONFIRM',
      detail: props.detail,
    },
    global: { plugins: [ElementPlus] },
  });
}

describe('ExecutionConfirmCard', () => {
  it('renders the action summary verbatim', () => {
    const wrapper = mountCard({ summary: '清理 /tmp/cache-demo 临时文件' });
    expect(wrapper.text()).toContain('清理 /tmp/cache-demo 临时文件');
  });

  it('emits confirm with the actionId when the user clicks 确认执行', async () => {
    const wrapper = mountCard({ actionId: 'act-007' });
    const button = wrapper.find('[data-testid="execution-confirm-confirm-act-007"]');
    expect(button.exists()).toBe(true);
    await button.trigger('click');
    const events = wrapper.emitted('confirm');
    expect(events).toBeTruthy();
    expect(events?.[0]).toEqual(['act-007']);
  });

  it('emits cancel with the actionId when the user clicks 取消', async () => {
    const wrapper = mountCard({ actionId: 'act-008' });
    const button = wrapper.find('[data-testid="execution-confirm-cancel-act-008"]');
    expect(button.exists()).toBe(true);
    await button.trigger('click');
    const events = wrapper.emitted('cancel');
    expect(events).toBeTruthy();
    expect(events?.[0]).toEqual(['act-008']);
  });

  it('disables both buttons after the first click (in-flight guard)', async () => {
    const wrapper = mountCard({ actionId: 'act-009' });
    const confirm = wrapper.find('[data-testid="execution-confirm-confirm-act-009"]');
    const cancel = wrapper.find('[data-testid="execution-confirm-cancel-act-009"]');
    expect(confirm.attributes('disabled')).toBeUndefined();
    expect(cancel.attributes('disabled')).toBeUndefined();

    await confirm.trigger('click');

    // After click, the DOM is patched — re-query.
    const confirmAfter = wrapper.find('[data-testid="execution-confirm-confirm-act-009"]');
    const cancelAfter = wrapper.find('[data-testid="execution-confirm-cancel-act-009"]');
    expect(confirmAfter.attributes('disabled')).toBeDefined();
    expect(cancelAfter.attributes('disabled')).toBeDefined();

    // A second click must not re-emit.
    await confirmAfter.trigger('click');
    const events = wrapper.emitted('confirm');
    expect(events).toHaveLength(1);
  });

  it('hides both buttons and shows the blocked notice when decision is BLOCK', () => {
    const wrapper = mountCard({ actionId: 'act-010', decision: 'BLOCK' });
    expect(wrapper.find('[data-testid="execution-confirm-confirm-act-010"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="execution-confirm-cancel-act-010"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="execution-confirm-blocked-act-010"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('该操作已被安全规则阻断');
  });

  it('hides both buttons and shows the blocked notice when decision is ALLOW', () => {
    // ALLOW decisions are auto-executed by the backend; the user has nothing
    // to confirm. The card degrades to an info notice.
    const wrapper = mountCard({ actionId: 'act-011', decision: 'ALLOW' });
    expect(wrapper.find('[data-testid="execution-confirm-confirm-act-011"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="execution-confirm-blocked-act-011"]').exists()).toBe(true);
  });

  it('renders the detail block when provided', () => {
    const wrapper = mountCard({
      actionId: 'act-012',
      detail: '匹配规则：L2-restart-systemd-service',
    });
    expect(wrapper.find('[data-testid="execution-confirm-detail-act-012"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('匹配规则：L2-restart-systemd-service');
  });

  it('does not call any API — emits are the only side effect', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockImplementation(() =>
      Promise.resolve(new Response('{}')),
    );
    const wrapper = mountCard({ actionId: 'act-013' });
    await wrapper.find('[data-testid="execution-confirm-confirm-act-013"]').trigger('click');
    expect(fetchSpy).not.toHaveBeenCalled();
    expect(wrapper.emitted('confirm')).toBeTruthy();
    fetchSpy.mockRestore();
  });
});
