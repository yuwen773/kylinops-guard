/**
 * Auth API wrappers.
 *
 * Thin façade over the Spring Boot auth controller — no client-side
 * validation, no caching, no retries. The page component owns UX state
 * (loading, error messages) and the {@link AuthSessionStore} owns the
 * in-memory session.
 *
 * Wire contracts (`com.kylinops.auth.AuthController`):
 *   - POST /api/auth/login     → AuthSession (CSRF-exempt)
 *   - GET  /api/auth/session   → AuthSession (401 when unauthenticated)
 *   - POST /api/auth/logout    → no body (204)
 *
 * Sensitive material rule: this module forwards the password to the
 * backend exactly once on login and never stores it. Callers MUST drop the
 * password reference from their component state immediately after calling
 * {@link login}.
 */

import { apiClient, ApiError, unwrapError, unwrapResponse } from './client';
import type { ApiResponse } from '@/types/api';
import { clearSession, setSession } from '@/auth/session';
import type { AuthSession, LoginRequest, LoginResponse } from '@/types/auth';

/**
 * Submit credentials and, on success, populate the in-memory session.
 *
 * Throws ApiError for both 401 (wrong credentials) and 423 / 429
 * (locked / rate-limited). The caller surfaces the generic "用户名或密码错误"
 * for 401 and the specific backend message otherwise — but never reveals
 * whether the username exists.
 */
export async function login(payload: LoginRequest): Promise<AuthSession> {
  try {
    const response = await apiClient.request<ApiResponse<LoginResponse>>({
      url: '/api/auth/login',
      method: 'POST',
      data: payload,
    });
    const data = unwrapResponse<LoginResponse>({
      data: response.data,
      status: response.status,
    });
    setSession(data);
    return data;
  } catch (err) {
    // Defensive: make sure a failed login never leaves stale state.
    clearSession();
    if (err instanceof ApiError) throw err;
    unwrapError(err);
  }
}

/**
 * Fetch the current session summary. Used by the router guard and by
 * `main.ts` on initial mount.
 *
 * Returns `null` on 401 instead of throwing — the router guard treats
 * "no session" as the normal unauthenticated path and only callers that
 * really care about the failure (e.g. logout flow) need the raw error.
 */
export async function fetchSession(): Promise<AuthSession | null> {
  try {
    const response = await apiClient.request<ApiResponse<AuthSession>>({
      url: '/api/auth/session',
      method: 'GET',
    });
    const data = unwrapResponse<AuthSession>({
      data: response.data,
      status: response.status,
    });
    setSession(data);
    return data;
  } catch (err) {
    if (err instanceof ApiError && (err.httpStatus === 401 || err.code === 401)) {
      clearSession();
      return null;
    }
    throw err;
  }
}

/**
 * End the current session. Best-effort — even if the backend call fails,
 * the local store is cleared so the user is signed out from this tab.
 */
export async function logout(): Promise<void> {
  try {
    await apiClient.request({
      url: '/api/auth/logout',
      method: 'POST',
    });
  } catch {
    // Swallow — the user has explicitly asked to sign out; we should not
    // keep them logged in just because the server is unreachable.
  } finally {
    clearSession();
  }
}
