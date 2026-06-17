import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import AppStateBanner, { type AppStateBannerTone } from './AppStateBanner.vue';

describe('AppStateBanner', () => {
  it.each<AppStateBannerTone>(['info', 'success', 'warning', 'danger'])(
    'applies the %s tone class',
    (tone) => {
      const wrapper = mount(AppStateBanner, {
        props: { tone, title: '提示' },
      });
      expect(wrapper.find(`[data-testid="app-state-banner-${tone}"]`).exists()).toBe(true);
    },
  );

  it('renders the title and optional description', () => {
    const wrapper = mount(AppStateBanner, {
      props: { title: '刷新失败', description: '当前展示的是上一次成功采集的数据。' },
    });
    expect(wrapper.text()).toContain('刷新失败');
    expect(wrapper.text()).toContain('上一次成功采集的数据');
  });

  it('emits a close event when the close button is clicked', async () => {
    const wrapper = mount(AppStateBanner, {
      props: { title: '提示', closable: true },
    });
    await wrapper.find('[data-testid="app-state-banner-close"]').trigger('click');
    expect(wrapper.emitted('close')).toBeTruthy();
  });

  it('hides the close button when closable=false', () => {
    const wrapper = mount(AppStateBanner, {
      props: { title: '提示', closable: false },
    });
    expect(wrapper.find('[data-testid="app-state-banner-close"]').exists()).toBe(false);
  });
});
