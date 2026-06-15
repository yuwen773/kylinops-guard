/**
 * Frontend-side mirror of the Spring Boot auth contract
 * (com.kylinops.auth.LoginRequest / AuthSessionResponse).
 *
 * Field order and types must stay aligned with the Java records so JSON
 * deserialization is lossless. Sensitive material (passwords, the session
 * cookie) NEVER ends up here — only the in-memory summary the server returns
 * after a successful authentication.
 */

/** POST /api/auth/login request body. */
export interface LoginRequest {
  /** Administrator account name. */
  username: string;
  /** Administrator password (cleartext over HTTPS; never persisted client-side). */
  password: string;
}

/**
 * Response envelope `data` for POST /api/auth/login and GET /api/auth/session.
 *
 * `csrfToken` is required for all mutating requests; the Axios request
 * interceptor reads it from {@link AuthSessionStore} and attaches it via the
 * `X-CSRF-TOKEN` header. It is NOT persisted to localStorage / sessionStorage
 * — refresh requires re-fetching from `/api/auth/session`.
 */
export interface AuthSession {
  /** Authenticated principal (admin username). */
  username: string;
  /** CSRF token bound to the current HTTP session. */
  csrfToken: string;
  /** ISO-8601 timestamp of login. */
  loginAt: string;
  /** ISO-8601 timestamp at which the session absolutely expires. */
  expiresAt: string;
  /** Idle timeout in seconds. */
  idleTimeout: number;
}

/** Alias kept for readability on the login response site. */
export type LoginResponse = AuthSession;
