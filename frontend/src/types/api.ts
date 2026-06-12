/**
 * Unified API envelope shared with the Spring Boot backend
 * (com.kylinops.common.ApiResponse). Field order and types must stay aligned
 * with the Java definition so JSON deserialization is lossless.
 *
 * Backend business convention:
 *   - HTTP 200 with code === 200  => success; `data` is the typed payload.
 *   - HTTP 200 with code !== 200  => business error (e.g. risk BLOCK, validation).
 *   - HTTP 4xx / 5xx              => transport/server error; backend envelope may
 *                                   or may not be present depending on the handler.
 */
export interface ApiResponse<T> {
  /** Business status code. 200 = success, anything else is an error. */
  code: number;

  /** Human-readable (Chinese) message from the backend. */
  message: string;

  /** Typed payload. May be null on error. */
  data: T;

  /** Server epoch millis. */
  timestamp: number;

  /** Optional request trace id, used to correlate with AuditLog records. */
  traceId?: string;
}
