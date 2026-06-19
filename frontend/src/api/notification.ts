// Notification management API — thin wrappers over the runtime-config
// CRUD endpoints exposed by Task 5 of P1-01, plus the connection-test
// endpoints added in Task 7.
//
//   com.kylinops.notification.api.NotificationManagementController
//
// Contract (locked):
//   * GET    /api/notification/settings               → NotificationSettings
//   * PUT    /api/notification/settings               → NotificationSettings
//   * POST   /api/notification/channels               → NotificationChannel
//   * PUT    /api/notification/channels/{channelId}   → NotificationChannel
//   * DELETE /api/notification/channels/{channelId}?version=N → void
//   * POST   /api/notification/channels/test          → NotificationTestResult
//   * GET    /api/notification/test-records?limit=N   → NotificationTestRecordSummary[]
//
// SECRET SAFETY — see types/notification.ts:
//   * The server never returns a stored secret; the response carries only
//     `secretConfigured: boolean`. This module therefore NEVER reads or
//     echoes a `secret` field from any GET response.
//   * `secret` is only ever sent when the caller passes it explicitly
//     (the dialog form). `clearSecret` is honored on PUT to wipe a stored
//     secret; undefined values are dropped from the JSON body so the
//     backend's null-secret-means-keep-current semantics stay intact.

import { del, get, post, put } from './client';
import type {
  NotificationChannel,
  NotificationChannelCreatePayload,
  NotificationChannelUpdatePayload,
  NotificationSettings,
  NotificationSettingsUpdatePayload,
  NotificationTestRecordSummary,
  TestChannelRequest,
} from '@/types/notification';

/**
 * Mirrors com.kylinops.notification.NotificationTestResult.
 * External HTTP failures are reported as `status: 'FAILED'` with
 * HTTP 200 envelope; the call only rejects for transport or business errors.
 */
export interface NotificationTestResult {
  recordId: string;
  channelId: string;
  eventType: 'TEST';
  status: 'SENT' | 'FAILED';
  responseCode?: number;
  errorMessage?: string | null;
  sentAt: string;
  durationMs: number;
}

/**
 * GET /api/notification/settings — returns current settings + the full
 * channel list. `secretConfigured` tells the UI whether to render the
 * 「已配置」 tag; the actual secret value is never on the wire.
 */
export function getSettings(): Promise<NotificationSettings> {
  return get<NotificationSettings>('/api/notification/settings');
}

/**
 * PUT /api/notification/settings — optimistic-lock update.
 * Body MUST carry the current `version`; on conflict the backend returns
 * 409 and the caller should re-fetch via getSettings().
 */
export function updateSettings(
  payload: NotificationSettingsUpdatePayload,
): Promise<NotificationSettings> {
  return put<NotificationSettings>('/api/notification/settings', payload);
}

/**
 * POST /api/notification/channels — create a new channel.
 *
 * The backend expects `{ channelId, type, enabled, url, secret?, clearSecret?,
 * timeoutMs }` and uses `channelId` as the stable id (no separate uuid
 * generation server-side). `secret` is optional — leave it out to create
 * a channel with no secret configured.
 */
export function createChannel(
  payload: NotificationChannelCreatePayload,
): Promise<NotificationChannel> {
  return post<NotificationChannel>('/api/notification/channels', payload);
}

/**
 * PUT /api/notification/channels/{channelId} — update an existing channel.
 *
 * Body MUST carry the channel's current `version` (optimistic lock).
 *
 * Secret handling on PUT:
 *   * `secret` undefined + `clearSecret: false` → server keeps the stored secret.
 *   * `secret: '…'` + `clearSecret: false`     → server replaces the stored secret.
 *   * `secret` undefined + `clearSecret: true` → server wipes the stored secret.
 */
export function updateChannel(
  channelId: string,
  payload: NotificationChannelUpdatePayload,
): Promise<NotificationChannel> {
  return put<NotificationChannel>(
    `/api/notification/channels/${channelId}`,
    payload,
  );
}

/**
 * DELETE /api/notification/channels/{channelId}?version=N — soft-delete
 * a channel. `version` is required as a query parameter (not body) and
 * must match the row's current optimistic-lock version; mismatch returns
 * 409 and the caller should re-fetch.
 */
export function deleteChannel(channelId: string, version: number): Promise<void> {
  return del<void>(`/api/notification/channels/${channelId}`, {
    params: { version },
  });
}

// ============================================================
// 连接测试 (P1-01 Plan 01 Task 7)
// ============================================================

/**
 * POST /api/notification/channels/test — trigger a single test send.
 *
 * Two modes (server decides by `channelId` presence):
 *   * saved:  { channelId, message? } — backend resolves the stored
 *             channel and uses its persisted secret/url.
 *   * draft:  { type, enabled, url, secret?, clearSecret?, timeoutMs, message? }
 *             — the form is sent verbatim, no DB persistence.
 *
 * HTTP 200 always means the request was processed; external 4xx/5xx
 * surfaces as `status: 'FAILED'` with `errorMessage`. The promise only
 * rejects on transport failure or a backend business error (e.g. unknown
 * channelId → 400).
 */
export function testChannel(
  payload: TestChannelRequest,
): Promise<NotificationTestResult> {
  return post<NotificationTestResult>('/api/notification/channels/test', payload);
}

/**
 * GET /api/notification/test-records?limit=N — most recent N TEST records
 * across all channels, ordered newest-first.
 *
 * `limit` is clamped server-side to [1, 20]; `undefined` (or omitted)
 * leaves the server default (20) in effect.
 */
export function getRecentTestRecords(
  limit?: number,
): Promise<NotificationTestRecordSummary[]> {
  const params: Record<string, number> = {};
  if (limit !== undefined) {
    params.limit = limit;
  }
  return get<NotificationTestRecordSummary[]>('/api/notification/test-records', {
    params,
  });
}