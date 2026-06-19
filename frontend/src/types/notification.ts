// Notification Center DTOs — mirrors the backend
//   com.kylinops.notification.api.NotificationSettingsView
//   com.kylinops.notification.api.NotificationChannelView
//   com.kylinops.notification.api.NotificationChannelUpsertRequest
//   com.kylinops.notification.api.NotificationSettingsUpdateRequest
//   com.kylinops.notification.api.NotificationChannelTestRequest
//   com.kylinops.notification.api.NotificationTestRecordSummary
//   com.kylinops.notification.NotificationTestResult
//
// SAFETY CONTRACT — secret handling:
//   * The backend NEVER echoes the stored secret back in any response.
//     Instead, NotificationChannelView exposes a boolean `secretConfigured`
//     and `NotificationChannel` carries that flag forward to the UI.
//   * When editing a channel the user must re-supply the secret; the
//     frontend never tries to display the existing value.
//   * To wipe the stored secret the frontend sends `clearSecret: true`
//     without `secret` (and vice versa).
//   * The GET response carries no secret field at all — any TS code that
//     tries to render `channel.secret` would be reading from a field
//     that does not exist on the wire shape.

/** Mirrors com.kylinops.notification.ChannelType. */
export type ChannelType = 'WEBHOOK' | 'FEISHU';

/** Mirrors com.kylinops.notification.NotificationEventType. */
export type NotificationEventType =
  | 'L4_BLOCK'
  | 'PROMPT_INJECTION_BLOCK'
  | 'L2_CONFIRM_REQUIRED'
  | 'SERVICE_ABNORMAL'
  | 'DISK_RISK'
  | 'TEST';

/** Mirrors com.kylinops.notification.NotificationStatus. */
export type NotificationStatus = 'PENDING' | 'SENT' | 'FAILED' | 'SKIPPED';

/**
 * Mirrors
 *   com.kylinops.notification.api.NotificationTestRecordSummary.
 *
 * Used for both the per-channel `lastTestResult` field on
 * {@link NotificationChannel} and the list returned by
 * {@link getRecentTestRecords}.
 */
export interface NotificationTestRecordSummary {
  recordId: string;
  channelId: string;
  eventType: NotificationEventType;
  status: NotificationStatus;
  responseCode?: number;
  errorMessage?: string | null;
  sentAt: string;
  durationMs: number;
}

/** Mirrors com.kylinops.notification.api.NotificationChannelView. */
export interface NotificationChannel {
  /** Stable channel identifier; used in URL path for update/delete. */
  id: string;
  type: ChannelType;
  enabled: boolean;
  url: string;
  /** True iff the server has a non-null secret stored for this channel. */
  secretConfigured: boolean;
  timeoutMs: number;
  /** Optimistic-lock version. Must be sent back for update/delete. */
  version: number;
  /** ISO-8601 timestamp; backend serialises LocalDateTime as a string. */
  createdAt: string;
  /** ISO-8601 timestamp. */
  updatedAt: string;
  /**
   * Last connection-test result for this channel.
   * null when the channel has never been tested.
   * Wire-shape: NotificationTestRecordSummary (P1-01 Task 7).
   */
  lastTestResult?: NotificationTestRecordSummary | null;
}

/** Mirrors com.kylinops.notification.api.NotificationSettingsView. */
export interface NotificationSettings {
  enabled: boolean;
  dryRun: boolean;
  /** Optimistic-lock version; PUT /api/notification/settings must echo it. */
  version: number;
  channels: NotificationChannel[];
}

/**
 * Editable form for the channel create/update dialog.
 *
 * Differences from {@link NotificationChannel}:
 *   * `secret` is a write-only input — typed by the user, sent on save,
 *     never read back from the server.
 *   * `channelId` is required for POST, derived from path on PUT.
 *   * `version` is null on POST and required on PUT.
 *   * `clearSecret` is honored only on PUT (backend uses null `secret`
 *     to keep the stored value).
 */
export interface ChannelForm {
  /** New channel id; required for POST. Ignored on PUT. */
  channelId?: string;
  type: ChannelType;
  enabled: boolean;
  url: string;
  /** Optional. Empty string is treated as "no change". */
  secret?: string;
  /** PUT only. When true, the server wipes the stored secret. */
  clearSecret: boolean;
  timeoutMs: number;
  /** Required on PUT; null on POST (server initializes at 0). */
  version?: number | null;
}

/** Body shape for PUT /api/notification/settings. */
export interface NotificationSettingsUpdatePayload {
  enabled: boolean;
  dryRun: boolean;
  version: number;
}

/** Body shape for POST /api/notification/channels (channelId required). */
export interface NotificationChannelCreatePayload {
  channelId: string;
  type: ChannelType;
  enabled: boolean;
  url: string;
  secret?: string;
  clearSecret?: boolean;
  timeoutMs: number;
}

/** Body shape for PUT /api/notification/channels/{id} (version required). */
export interface NotificationChannelUpdatePayload {
  type: ChannelType;
  enabled: boolean;
  url: string;
  secret?: string;
  clearSecret?: boolean;
  timeoutMs: number;
  version: number;
}

/**
 * Body shape for POST /api/notification/channels/test.
 *
 * Two modes (decided server-side by `channelId` presence):
 *   * saved:  { channelId, message? } — the channel is already stored; the
 *             server uses the persisted secret/url.
 *   * draft:  { type, enabled, url, secret?, clearSecret?, timeoutMs, message? }
 *             — the form values are sent verbatim; no DB persistence.
 */
export interface TestChannelRequest {
  channelId?: string;
  type?: ChannelType;
  enabled?: boolean;
  url?: string;
  secret?: string;
  clearSecret?: boolean;
  timeoutMs?: number;
  message?: string;
}