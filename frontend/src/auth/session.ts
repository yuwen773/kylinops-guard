/**
 * In-memory auth session store.
 *
 * The store deliberately holds ONLY the summary the backend returns after a
 * successful login — username, CSRF token, and lifecycle timestamps. It does
 * NOT hold passwords, session cookies, or anything that would let a page
 * refresh skip re-validation against `/api/auth/session`.
 *
 * Storage rules (security):
 *   - Module-level singleton (no `localStorage` / `sessionStorage`).
 *   - Cleared on logout, on 401 responses, and on explicit `clearSession()`.
 *   - The CSRF token is read by the Axios request interceptor only; it never
 *     escapes the module via the URL, the document, or postMessage.
 *
 * Storage rules (semantics):
 *   - `setUnauthenticatedRedirect` registers ONE hook (last writer wins) that
 *     the response interceptor invokes when a 401 is observed. The default
 *     implementation in `main.ts` does `router.push('/login')`.
 *   - `triggerUnauthenticatedRedirect` is idempotent: calling it without a
 *     handler is a no-op (e.g. during unit tests that do not mount the app).
 */

import type { AuthSession } from '@/types/auth';

// ─────────────────────────────────────────────────────────────────────────
// Internal state (singleton, NOT exported)
// ─────────────────────────────────────────────────────────────────────────

let currentSession: AuthSession | null = null;
let onUnauthenticated: (() => void) | null = null;

// ─────────────────────────────────────────────────────────────────────────
// Public API
// ─────────────────────────────────────────────────────────────────────────

/**
 * Replace the in-memory session. Pass `null` to clear.
 *
 * Defensive copy — caller cannot mutate the stored object by mutating its
 * input afterwards.
 */
export function setSession(next: AuthSession | null): void {
  currentSession = next === null ? null : { ...next };
}

/** Read the current session snapshot, or `null` when unauthenticated. */
export function getSession(): AuthSession | null {
  return currentSession === null ? null : { ...currentSession };
}

/** Convenience accessor for the request interceptor. */
export function getCsrfToken(): string | null {
  return currentSession?.csrfToken ?? null;
}

/** Drop the in-memory session. Safe to call when already cleared. */
export function clearSession(): void {
  currentSession = null;
}

/**
 * Register the function to invoke when a 401 is observed.
 *
 * Wired in `main.ts` to push the router to `/login`. Tests register a `vi.fn()`.
 */
export function setUnauthenticatedRedirect(handler: (() => void) | null): void {
  onUnauthenticated = handler;
}

/**
 * Invoke the registered redirect handler, if any. Used by the Axios response
 * interceptor on 401 responses.
 */
export function triggerUnauthenticatedRedirect(): void {
  if (onUnauthenticated !== null) {
    onUnauthenticated();
  }
}
