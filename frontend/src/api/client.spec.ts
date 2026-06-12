import { describe, expect, it, vi } from 'vitest';
import { AxiosError, type InternalAxiosRequestConfig } from 'axios';
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
