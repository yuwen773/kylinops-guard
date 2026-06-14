import axios, { AxiosError, AxiosHeaders, type AxiosInstance, type AxiosRequestConfig } from 'axios';
import type { ApiResponse } from '@/types/api';
import {
  clearSession,
  getCsrfToken,
  triggerUnauthenticatedRedirect,
} from '@/auth/session';

/**
 * Custom error thrown by the unified API client. The frontend treats every
 * non-success outcome (transport or business) as a single error class so
 * call-sites can do `try { ... } catch (e: ApiError) { ... }` and still
 * surface the backend's structured payload to the user.
 */
export class ApiError extends Error {
  public readonly code: number;
  public readonly traceId?: string;
  public readonly data: unknown;
  public readonly httpStatus?: number;

  constructor(opts: {
    code: number;
    message: string;
    traceId?: string;
    data?: unknown;
    httpStatus?: number;
  }) {
    super(opts.message);
    this.name = 'ApiError';
    this.code = opts.code;
    this.traceId = opts.traceId;
    this.data = opts.data;
    this.httpStatus = opts.httpStatus;
  }
}

/**
 * Best-effort detection of an ApiResponse envelope. The backend wraps every
 * response in {@link ApiResponse}, but transport-layer failures (timeouts,
 * 502 from a proxy, CORS) return HTML or plain text instead, so we cannot
 * rely on `typeof payload === 'object' && 'code' in payload` alone.
 */
export function isApiResponse(value: unknown): value is ApiResponse<unknown> {
  if (!value || typeof value !== 'object') return false;
  const v = value as Record<string, unknown>;
  return (
    typeof v.code === 'number' &&
    typeof v.message === 'string' &&
    'timestamp' in v
  );
}

/**
 * Pure helper that converts an axios response (or error) into either the
 * unwrapped data payload or an ApiError. Exported separately so unit tests
 * can verify the envelope-handling contract without standing up a real
 * axios instance and interceptors.
 */
export function unwrapResponse<T>(response: { data: unknown; status: number }): T {
  const payload = response.data;
  if (!isApiResponse(payload)) {
    // Non-JSON / non-envelope response — pass through as-is so the caller
    // can still surface the raw data when the backend returns plain text.
    return payload as T;
  }
  if (payload.code !== 200) {
    throw new ApiError({
      code: payload.code,
      message: payload.message || '业务错误',
      traceId: payload.traceId,
      data: payload.data ?? null,
      httpStatus: response.status,
    });
  }
  return payload.data as T;
}

export function unwrapError(error: unknown): never {
  if (error instanceof ApiError) throw error;
  if (axios.isAxiosError(error)) {
    const responseData = (error as AxiosError).response?.data;
    if (isApiResponse(responseData)) {
      throw new ApiError({
        code: responseData.code,
        message: responseData.message || error.message,
        traceId: responseData.traceId,
        data: responseData.data ?? null,
        httpStatus: error.response?.status,
      });
    }
    throw new ApiError({
      code: error.response?.status ?? 0,
      message: error.message || '网络错误',
      traceId: undefined,
      data: null,
      httpStatus: error.response?.status,
    });
  }
  // Unknown error type — normalize to ApiError so the public surface is uniform.
  throw new ApiError({
    code: 0,
    message: (error as Error)?.message ?? '未知错误',
    data: null,
  });
}

function buildClient(): AxiosInstance {
  const instance = axios.create({
    baseURL: import.meta.env.VITE_API_BASE_URL ?? '',
    timeout: 15_000,
    headers: { 'Content-Type': 'application/json' },
    // Required: the backend session lives in a JSESSIONID cookie, and the
    // dev server proxies `/api` to a different origin. Without
    // `withCredentials: true` the browser would drop the cookie and the
    // safety/audit loop would lose identity.
    withCredentials: true,
  });

  // ── Request interceptor: CSRF token injection ──────────────────────────
  // Spring Security's default CsrfFilter expects the token in the
  // `X-CSRF-TOKEN` header for any mutating request (POST/PUT/DELETE/PATCH).
  // The token is sourced from the in-memory session (last filled by GET
  // /api/auth/session or POST /api/auth/login). Safe methods (GET/HEAD/
  // OPTIONS) intentionally skip injection — they would only widen the
  // CSRF token's exposure surface.
  instance.interceptors.request.use((config) => {
    const method = (config.method ?? 'get').toUpperCase();
    if (method === 'POST' || method === 'PUT' || method === 'DELETE' || method === 'PATCH') {
      const token = getCsrfToken();
      if (token) {
        // Use AxiosHeaders.set so we preserve interop with the headers
        // object regardless of whether the caller passed plain object or
        // AxiosHeaders.
        const headers = AxiosHeaders.from(config.headers as AxiosHeaders);
        headers.set('X-CSRF-TOKEN', token);
        config.headers = headers;
      }
    }
    return config;
  });

  // ── Response interceptor: 401 → drop session + redirect to /login ─────
  // On any 401 (unauthenticated or session expired) we clear the in-memory
  // auth state and trigger the redirect hook. The error still propagates as
  // ApiError so the caller can render a message; the redirect just makes
  // sure the user lands back on /login.
  instance.interceptors.response.use(
    (response) => response,
    (error) => {
      if (axios.isAxiosError(error) && error.response?.status === 401) {
        clearSession();
        triggerUnauthenticatedRedirect();
      }
      unwrapError(error);
    },
  );

  return instance;
}

export const apiClient: AxiosInstance = buildClient();

/**
 * Execute a request and return the unwrapped `data` field. Throws ApiError
 * for both transport and business failures — the caller does not need to
 * distinguish them.
 */
async function request<T>(config: AxiosRequestConfig): Promise<T> {
  try {
    const response = await apiClient.request<ApiResponse<T>>(config);
    return unwrapResponse<T>({ data: response.data, status: response.status });
  } catch (err) {
    unwrapError(err);
  }
}

export function get<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
  return request<T>({ ...config, url, method: 'GET' });
}

export function post<T>(
  url: string,
  body?: unknown,
  config?: AxiosRequestConfig,
): Promise<T> {
  return request<T>({ ...config, url, method: 'POST', data: body });
}

export function put<T>(
  url: string,
  body?: unknown,
  config?: AxiosRequestConfig,
): Promise<T> {
  return request<T>({ ...config, url, method: 'PUT', data: body });
}

export function del<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
  return request<T>({ ...config, url, method: 'DELETE' });
}
