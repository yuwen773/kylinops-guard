// Notification management API — thin wrappers over the runtime-config
// CRUD endpoints exposed by Task 5 of P1-01.
//
//   com.kylinops.notification.api.NotificationManagementController
//
// Contract (locked):
//   * GET    /api/notification/settings               → NotificationSettings
//   * PUT    /api/notification/settings               → NotificationSettings
//   * POST   /api/notification/channels               → NotificationChannel
//   * PUT    /api/notification/channels/{channelId}   → NotificationChannel
//   * DELETE /api/notification/channels/{channelId}?version=N → void
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
} from '@/types/notification';

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