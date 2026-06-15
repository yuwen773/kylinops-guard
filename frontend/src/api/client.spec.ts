import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AxiosError, AxiosHeaders, type InternalAxiosRequestConfig } from 'axios';
import {
  ApiError,
  apiClient,
  get,
  isApiResponse,
  post,
  unwrapError,
  unwrapResponse,
} from './client';
import type { ApiResponse } from '@/types/api';
import {
  clearSession,
  getSession,
  setSession,
  setUnauthenticatedRedirect,
} from '@/auth/session';
import type { AuthSession } from '@/types/auth';

describe('isApiResponse', () => {
  it('recognises a well-formed envelope', () => {
    expect(
      isApiResponse({ code: 200, message: 'ok', data: {}, timestamp: 1 }),
    ).toBe(true);
  });

  it('rejects a non-envelope object', () => {
    expect(isApiResponse({ foo: 'bar' })).toBe(false);
    expect(isApiResponse(null)).toBe(false);
    expect(isApiResponse('string')).toBe(false);
    expect(isApiResponse(42)).toBe(false);
  });
});

describe('unwrapResponse', () => {
  it('returns data on HTTP 200 with business code 200', () => {
    const payload: ApiResponse<{ hello: string }> = {
      code: 200,
      message: 'success',
      data: { hello: 'world' },
      timestamp: 1700000000000,
      traceId: 'trace-1',
    };
    const result = unwrapResponse<{ hello: string }>({ data: payload, status: 200 });
    expect(result).toEqual({ hello: 'world' });
  });

  it('throws ApiError on HTTP 200 with non-200 business code, preserving all envelope fields', () => {
    const payload: ApiResponse<null> = {
      code: 403,
      message: '拒绝访问：检测到危险指令',
      data: null,
      timestamp: 1700000000000,
      traceId: 'trace-block',
    };
    expect(() =>
      unwrapResponse<null>({ data: payload, status: 200 }),
    ).toThrowError(ApiError);
    try {
      unwrapResponse<null>({ data: payload, status: 200 });
    } catch (err) {
      const e = err as ApiError;
      expect(e).toBeInstanceOf(ApiError);
      expect(e.code).toBe(403);
      expect(e.message).toBe('拒绝访问：检测到危险指令');
      expect(e.data).toBeNull();
      expect(e.traceId).toBe('trace-block');
      expect(e.httpStatus).toBe(200);
    }
  });

  it('passes through non-envelope responses untouched', () => {
    const result = unwrapResponse<string>({ data: 'plain text', status: 200 });
    expect(result).toBe('plain text');
  });
});

describe('unwrapError', () => {
  function makeAxiosError(opts: {
    status?: number;
    data?: unknown;
    message?: string;
  }): AxiosError {
    const config = { headers: new Headers() } as unknown as InternalAxiosRequestConfig;
    return new AxiosError(
      opts.message ?? 'Request failed',
      'ERR_TEST',
      config,
      null,
      opts.status !== undefined
        ? ({
            status: opts.status,
            data: opts.data,
            statusText: 'ERR',
            headers: {},
            config,
          } as unknown as import('axios').AxiosResponse)
        : undefined,
    );
  }

  it('extracts envelope from a 4xx/5xx axios error', () => {
    const err = makeAxiosError({
      status: 500,
      data: {
        code: 500,
        message: '服务器内部错误',
        data: null,
        timestamp: 1700000000000,
        traceId: 'trace-err',
      },
      message: 'Request failed with status code 500',
    });
    expect(() => unwrapError(err)).toThrowError(ApiError);
    try {
      unwrapError(err);
    } catch (thrown) {
      const e = thrown as ApiError;
      expect(e.code).toBe(500);
      expect(e.message).toBe('服务器内部错误');
      expect(e.traceId).toBe('trace-err');
      expect(e.httpStatus).toBe(500);
    }
  });

  it('normalises a network error (no response) into an ApiError', () => {
    const err = makeAxiosError({ message: 'Network Error' });
    expect(() => unwrapError(err)).toThrowError(ApiError);
    try {
      unwrapError(err);
    } catch (thrown) {
      const e = thrown as ApiError;
      expect(e.code).toBe(0);
      expect(e.message).toBe('Network Error');
      expect(e.httpStatus).toBeUndefined();
    }
  });

  it('re-throws existing ApiError unchanged', () => {
    const original = new ApiError({
      code: 418,
      message: 'teapot',
      data: { foo: 1 },
    });
    expect(() => unwrapError(original)).toThrow(original);
  });
});

describe('get / post (integration through the public client)', () => {
  // The public client wraps an axios instance whose interceptors delegate
  // to unwrapError. We assert the request() call is forwarded with the
  // expected shape and that a successful envelope reaches the caller.
  it('get() forwards the call to axios and returns unwrapped data', async () => {
    const spy = vi
      .spyOn(apiClient, 'request')
      .mockResolvedValueOnce({
        data: {
          code: 200,
          message: 'success',
          data: { value: 42 },
          timestamp: 1,
        },
        status: 200,
        statusText: 'OK',
        headers: {},
        config: {} as never,
      });

    const result = await get<{ value: number }>('/api/health');
    expect(result).toEqual({ value: 42 });
    expect(spy).toHaveBeenCalledWith(
      expect.objectContaining({ url: '/api/health', method: 'GET' }),
    );
    spy.mockRestore();
  });

  it('post() rejects with ApiError when the envelope is non-200', async () => {
    const spy = vi.spyOn(apiClient, 'request').mockResolvedValueOnce({
      data: {
        code: 403,
        message: '拒绝访问',
        data: null,
        timestamp: 1,
        traceId: 't1',
      },
      status: 200,
      statusText: 'OK',
      headers: {},
      config: {} as never,
    });

    await expect(
      post<null>('/api/chat/send', { content: 'rm -rf /' }),
    ).rejects.toBeInstanceOf(ApiError);
    spy.mockRestore();
  });
});

// ─────────────────────────────────────────────────────────────────────────
// Auth integration (P2-T5): withCredentials, CSRF, 401 → redirect
// ─────────────────────────────────────────────────────────────────────────

/**
 * Pull the single fulfilled request interceptor that the auth wiring
 * installs (X-CSRF-TOKEN injection). Test-only — uses the documented but
 * untyped `handlers` field on the interceptor manager.
 */
function takeRequestInterceptor(): (
  cfg: InternalAxiosRequestConfig,
) => InternalAxiosRequestConfig | Promise<InternalAxiosRequestConfig> {
  const handlers = (
    apiClient.interceptors.request as unknown as {
      handlers: Array<{
        fulfilled?: (cfg: InternalAxiosRequestConfig) => InternalAxiosRequestConfig | Promise<InternalAxiosRequestConfig>;
      } | null>;
    }
  ).handlers;
  const handler = handlers.find((h) => h && h.fulfilled);
  if (!handler || !handler.fulfilled) {
    throw new Error('expected at least one fulfilled request interceptor');
  }
  return handler.fulfilled;
}

/** Pull every rejected response interceptor. The client wires a single one
 *  that performs both 401 handling and envelope unwrapping. */
function takeResponseInterceptor(): (err: unknown) => unknown {
  const handlers = (
    apiClient.interceptors.response as unknown as {
      handlers: Array<{ rejected?: (err: unknown) => unknown } | null>;
    }
  ).handlers;
  const handler = handlers.find((h) => h && h.rejected);
  if (!handler || !handler.rejected) {
    throw new Error('expected at least one rejected response interceptor');
  }
  return handler.rejected;
}

function buildAuthSession(overrides?: Partial<AuthSession>): AuthSession {
  return {
    username: 'admin',
    csrfToken: 'csrf-abc-123',
    loginAt: '2026-06-14T00:00:00Z',
    expiresAt: '2026-06-14T12:00:00Z',
    idleTimeout: 1800,
    ...overrides,
  };
}

function baseConfig(method: 'get' | 'post' | 'put' | 'delete' | 'patch'): InternalAxiosRequestConfig {
  return {
    url: '/api/whatever',
    method,
    headers: new AxiosHeaders(),
  } as InternalAxiosRequestConfig;
}

describe('apiClient — withCredentials default', () => {
  it('axios instance is configured with withCredentials: true', () => {
    // Required so the browser sends the JSESSIONID cookie on cross-origin
    // dev requests. Without this the backend treats every request as a new
    // session and the safety / audit loop loses identity.
    expect(apiClient.defaults.withCredentials).toBe(true);
  });
});

describe('apiClient — CSRF request interceptor', () => {
  afterEach(() => {
    clearSession();
  });

  it('adds the X-CSRF-TOKEN header to POST requests when a session is present', async () => {
    setSession(buildAuthSession({ csrfToken: 'token-post' }));
    const interceptor = takeRequestInterceptor();
    const cfg = await interceptor(baseConfig('post'));
    // AxiosHeaders supports `get(name)` which returns the header value.
    const headers = AxiosHeaders.from(cfg.headers as AxiosHeaders);
    expect(headers.get('X-CSRF-TOKEN')).toBe('token-post');
  });

  it('adds the X-CSRF-TOKEN header to PUT requests when a session is present', async () => {
    setSession(buildAuthSession({ csrfToken: 'token-put' }));
    const interceptor = takeRequestInterceptor();
    const cfg = await interceptor(baseConfig('put'));
    const headers = AxiosHeaders.from(cfg.headers as AxiosHeaders);
    expect(headers.get('X-CSRF-TOKEN')).toBe('token-put');
  });

  it('adds the X-CSRF-TOKEN header to DELETE requests when a session is present', async () => {
    setSession(buildAuthSession({ csrfToken: 'token-del' }));
    const interceptor = takeRequestInterceptor();
    const cfg = await interceptor(baseConfig('delete'));
    const headers = AxiosHeaders.from(cfg.headers as AxiosHeaders);
    expect(headers.get('X-CSRF-TOKEN')).toBe('token-del');
  });

  it('does NOT add the X-CSRF-TOKEN header to safe GET requests', async () => {
    setSession(buildAuthSession({ csrfToken: 'token-get' }));
    const interceptor = takeRequestInterceptor();
    const cfg = await interceptor(baseConfig('get'));
    const headers = AxiosHeaders.from(cfg.headers as AxiosHeaders);
    expect(headers.get('X-CSRF-TOKEN')).toBeUndefined();
  });

  it('does NOT add the X-CSRF-TOKEN header when there is no session', async () => {
    clearSession();
    const interceptor = takeRequestInterceptor();
    const cfg = await interceptor(baseConfig('post'));
    const headers = AxiosHeaders.from(cfg.headers as AxiosHeaders);
    expect(headers.get('X-CSRF-TOKEN')).toBeUndefined();
  });
});

describe('apiClient — 401 response interceptor', () => {
  beforeEach(() => {
    setSession(buildAuthSession());
  });
  afterEach(() => {
    clearSession();
    setUnauthenticatedRedirect(null);
  });

  function makeAxiosErrorWithStatus(status: number): AxiosError {
    const config = { headers: new AxiosHeaders() } as unknown as InternalAxiosRequestConfig;
    return new AxiosError(
      `Request failed with status code ${status}`,
      'ERR_HTTP',
      config,
      null,
      {
        status,
        data: {
          code: status,
          message: status === 401 ? '未认证，请先登录' : '错误',
          data: null,
          timestamp: 1,
          traceId: 't-401',
        },
        statusText: 'ERR',
        headers: {},
        config,
      } as unknown as import('axios').AxiosResponse,
    );
  }

  it('on 401: clears the in-memory session and invokes the redirect hook', async () => {
    const redirect = vi.fn();
    setUnauthenticatedRedirect(redirect);
    expect(getSession()).not.toBeNull();

    const interceptor = takeResponseInterceptor();
    const err = makeAxiosErrorWithStatus(401);

    // The interceptor still throws (it must propagate through to call sites
    // as ApiError) — we only care about the side effects.
    let thrown: unknown = null;
    try {
      await interceptor(err);
    } catch (e) {
      thrown = e;
    }

    expect(thrown).toBeInstanceOf(ApiError);
    expect(getSession()).toBeNull();
    expect(redirect).toHaveBeenCalledTimes(1);
  });

  it('on non-401 errors: leaves the session intact and does NOT redirect', async () => {
    const redirect = vi.fn();
    setUnauthenticatedRedirect(redirect);

    const interceptor = takeResponseInterceptor();
    const err = makeAxiosErrorWithStatus(500);

    try {
      await interceptor(err);
    } catch (_e) {
      // Expected — unwrapError throws ApiError.
    }

    expect(getSession()).not.toBeNull();
    expect(redirect).not.toHaveBeenCalled();
  });
});
