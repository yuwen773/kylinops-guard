import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount, enableAutoUnmount } from '@vue/test-utils';
import {
  createRouter,
  createMemoryHistory,
  type Router,
} from 'vue-router';
import ElementPlus from 'element-plus';
import Login from './index.vue';
import { ApiError } from '@/api/client';
import * as authApi from '@/api/auth';
import { clearSession, getSession } from '@/auth/session';

function buildRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div data-testid="home" />' } },
      { path: '/login', component: { template: '<div />' } },
    ],
  });
}

async function mountLogin(router: Router) {
  router.push('/login');
  await router.isReady();
  const wrapper = mount(Login, {
    global: { plugins: [router, ElementPlus] },
  });
  await flushPromises();
  return wrapper;
}

describe('Login page', () => {
  enableAutoUnmount(afterEach);

  beforeEach(() => {
    clearSession();
  });

  afterEach(() => {
    vi.restoreAllMocks();
    clearSession();
  });

  it('renders username field, password field, and login button without registration link', async () => {
    const wrapper = await mountLogin(buildRouter());
    expect(wrapper.find('[data-testid="login-username"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="login-password"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="login-submit"]').exists()).toBe(true);
    // No registration link / "create account" link.
    expect(wrapper.text()).not.toContain('注册');
    expect(wrapper.text()).not.toContain('忘记密码');
  });

  it('calls auth.login(...) with the entered credentials and navigates to /chat on success', async () => {
    // P1-03: successful login now navigates to /chat directly (the product
    // surface) instead of relying on the `/` redirect, which points to the
    // public `/landing` marketing page for unauthenticated visitors.
    const router = buildRouter();
    const pushSpy = vi.spyOn(router, 'replace');
    const loginSpy = vi.spyOn(authApi, 'login').mockResolvedValue({
      username: 'admin',
      csrfToken: 'csrf-1',
      loginAt: '2026-06-14T00:00:00Z',
      expiresAt: '2026-06-14T12:00:00Z',
      idleTimeout: 1800,
    });

    const wrapper = await mountLogin(router);
    await wrapper.find('[data-testid="login-username"]').setValue('admin');
    await wrapper.find('[data-testid="login-password"]').setValue('topsecret');
    await wrapper.find('[data-testid="login-submit"]').trigger('click');
    await flushPromises();

    expect(loginSpy).toHaveBeenCalledWith({ username: 'admin', password: 'topsecret' });
    expect(pushSpy).toHaveBeenCalledWith('/chat');
  });

  it('shows a generic error message on 401 and clears the password input', async () => {
    const router = buildRouter();
    vi.spyOn(authApi, 'login').mockRejectedValue(
      new ApiError({ code: 401, message: '用户名或密码错误', httpStatus: 401 }),
    );

    const wrapper = await mountLogin(router);
    await wrapper.find('[data-testid="login-username"]').setValue('admin');
    await wrapper.find('[data-testid="login-password"]').setValue('wrong');
    await wrapper.find('[data-testid="login-submit"]').trigger('click');
    await flushPromises();

    const alert = wrapper.find('[data-testid="login-error"]');
    expect(alert.exists()).toBe(true);
    expect(alert.text()).toContain('用户名或密码错误');
    // The session was never populated.
    expect(getSession()).toBeNull();
    // Password input has been cleared by the page.
    const pwInput = wrapper.find('[data-testid="login-password"]').element as HTMLInputElement;
    expect(pwInput.value).toBe('');
  });

  it('surfaces the backend message on 429 (rate limited)', async () => {
    const router = buildRouter();
    vi.spyOn(authApi, 'login').mockRejectedValue(
      new ApiError({ code: 429, message: '请求过于频繁，请稍后再试', httpStatus: 429 }),
    );

    const wrapper = await mountLogin(router);
    await wrapper.find('[data-testid="login-username"]').setValue('admin');
    await wrapper.find('[data-testid="login-password"]').setValue('whatever');
    await wrapper.find('[data-testid="login-submit"]').trigger('click');
    await flushPromises();

    expect(wrapper.find('[data-testid="login-error"]').text()).toContain('请求过于频繁');
  });

  it('surfaces the backend message on 423 (locked)', async () => {
    const router = buildRouter();
    vi.spyOn(authApi, 'login').mockRejectedValue(
      new ApiError({ code: 423, message: '账户已锁定，请稍后再试', httpStatus: 423 }),
    );

    const wrapper = await mountLogin(router);
    await wrapper.find('[data-testid="login-username"]').setValue('admin');
    await wrapper.find('[data-testid="login-password"]').setValue('whatever');
    await wrapper.find('[data-testid="login-submit"]').trigger('click');
    await flushPromises();

    expect(wrapper.find('[data-testid="login-error"]').text()).toContain('已锁定');
  });
});
