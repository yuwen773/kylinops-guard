import { afterEach, describe, expect, it, vi } from 'vitest';
import { apiClient, ApiError } from './client';
import {
  createChannel,
  deleteChannel,
  getSettings,
  updateChannel,
  updateSettings,
} from './notification';

// All tests stub the underlying axios instance so we can assert the
// exact wire contract with the backend NotificationManagementController
// (com.kylinops.notification.api.NotificationManagementController).
//
// The contract is locked by Task 5 of P1-01:
//   * GET    /api/notification/settings              → NotificationSettings
//   * PUT    /api/notification/settings              → NotificationSettings
//   * POST   /api/notification/channels              → NotificationChannel
//   * PUT    /api/notification/channels/{channelId}  → NotificationChannel
//   * DELETE /api/notification/channels/{channelId}?version=N → void
//
// The frontend NEVER sends the existing secret back to the server (no
// GET response carries it). Editing a channel always re-supplies the
// secret, otherwise `clearSecret: true` is used to remove it.

describe('notification API — getSettings', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('GETs /api/notification/settings and returns the unwrapped view', async () => {
    const payload = {
      enabled: true,
      dryRun: false,
      version: 3,
      channels: [
        {
          id: 'ops-default',
          type: 'WEBHOOK',
          enabled: true,
          url: 'https://example.com/hook',
          secretConfigured: true,
          timeoutMs: 3000,
          version: 7,
          createdAt: '2026-06-12T10:00:00',
          updatedAt: '2026-06-15T11:00:00',
        },
      ],
    };
    const spy = vi.spyOn(apiClient, 'request').mockResolvedValueOnce({
      data: { code: 200, message: 'success', data: payload, timestamp: 1 },
      status: 200,
      statusText: 'OK',
      headers: {},
      config: {} as never,
    });

    const result = await getSettings();

    const call = spy.mock.calls[0]?.[0] as { method?: string; url?: string };
    expect(call.method).toBe('GET');
    expect(call.url).toBe('/api/notification/settings');
    expect(result.enabled).toBe(true);
    expect(result.dryRun).toBe(false);
    expect(result.version).toBe(3);
    expect(result.channels).toHaveLength(1);
    expect(result.channels[0]?.id).toBe('ops-default');
    expect(result.channels[0]?.secretConfigured).toBe(true);
  });

  it('rejects with ApiError on backend business error', async () => {
    vi.spyOn(apiClient, 'request').mockResolvedValueOnce({
      data: {
        code: 500,
        message: '通知设置加载失败',
        data: null,
        timestamp: 1,
        traceId: 't-1',
      },
      status: 500,
      statusText: 'ERROR',
      headers: {},
      config: {} as never,
    });

    await expect(getSettings()).rejects.toBeInstanceOf(ApiError);
  });
});

describe('notification API — updateSettings', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('PUTs to /api/notification/settings with the version-locked body', async () => {
    const spy = vi.spyOn(apiClient, 'request').mockResolvedValueOnce({
      data: {
        code: 200,
        message: 'success',
        data: { enabled: false, dryRun: true, version: 4, channels: [] },
        timestamp: 1,
      },
      status: 200,
      statusText: 'OK',
      headers: {},
      config: {} as never,
    });

    const result = await updateSettings({ enabled: false, dryRun: true, version: 3 });

    const call = spy.mock.calls[0]?.[0] as {
      method?: string;
      url?: string;
      data?: Record<string, unknown>;
    };
    expect(call.method).toBe('PUT');
    expect(call.url).toBe('/api/notification/settings');
    expect(call.data).toEqual({ enabled: false, dryRun: true, version: 3 });
    // No extra fields smuggled in.
    expect(Object.keys(call.data!).sort()).toEqual(['dryRun', 'enabled', 'version']);

    expect(result.enabled).toBe(false);
    expect(result.dryRun).toBe(true);
    expect(result.version).toBe(4);
  });
});

describe('notification API — createChannel', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('POSTs to /api/notification/channels with channelId, type, enabled, url, secret, clearSecret, timeoutMs', async () => {
    const spy = vi.spyOn(apiClient, 'request').mockResolvedValueOnce({
      data: {
        code: 200,
        message: 'success',
        data: {
          id: 'feishu-oncall',
          type: 'FEISHU',
          enabled: true,
          url: 'https://open.feishu.cn/hook/abc',
          secretConfigured: true,
          timeoutMs: 5000,
          version: 0,
          createdAt: '2026-06-15T10:00:00',
          updatedAt: '2026-06-15T10:00:00',
        },
        timestamp: 1,
      },
      status: 200,
      statusText: 'OK',
      headers: {},
      config: {} as never,
    });

    const result = await createChannel({
      channelId: 'feishu-oncall',
      type: 'FEISHU',
      enabled: true,
      url: 'https://open.feishu.cn/hook/abc',
      secret: 'sec_abc',
      clearSecret: false,
      timeoutMs: 5000,
    });

    const call = spy.mock.calls[0]?.[0] as {
      method?: string;
      url?: string;
      data?: Record<string, unknown>;
    };
    expect(call.method).toBe('POST');
    expect(call.url).toBe('/api/notification/channels');
    expect(call.data).toEqual({
      channelId: 'feishu-oncall',
      type: 'FEISHU',
      enabled: true,
      url: 'https://open.feishu.cn/hook/abc',
      secret: 'sec_abc',
      clearSecret: false,
      timeoutMs: 5000,
    });

    expect(result.id).toBe('feishu-oncall');
    expect(result.type).toBe('FEISHU');
    expect(result.secretConfigured).toBe(true);
  });

  it('omits undefined secret and clearSecret when not provided', async () => {
    const spy = vi.spyOn(apiClient, 'request').mockResolvedValueOnce({
      data: {
        code: 200,
        message: 'success',
        data: {
          id: 'wh-1',
          type: 'WEBHOOK',
          enabled: true,
          url: 'https://example.com/hook',
          secretConfigured: false,
          timeoutMs: 3000,
          version: 0,
          createdAt: '2026-06-15T10:00:00',
          updatedAt: '2026-06-15T10:00:00',
        },
        timestamp: 1,
      },
      status: 200,
      statusText: 'OK',
      headers: {},
      config: {} as never,
    });

    await createChannel({
      channelId: 'wh-1',
      type: 'WEBHOOK',
      enabled: true,
      url: 'https://example.com/hook',
      clearSecret: false,
      timeoutMs: 3000,
    });

    const call = spy.mock.calls[0]?.[0] as { data?: Record<string, unknown> };
    // undefined secret MUST NOT be serialized as a key.
    expect('secret' in call.data!).toBe(false);
    expect(Object.keys(call.data!).sort()).toEqual([
      'channelId',
      'clearSecret',
      'enabled',
      'timeoutMs',
      'type',
      'url',
    ]);
  });
});

describe('notification API — updateChannel', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('PUTs to /api/notification/channels/{id} with type, enabled, url, secret, clearSecret, timeoutMs, version', async () => {
    const spy = vi.spyOn(apiClient, 'request').mockResolvedValueOnce({
      data: {
        code: 200,
        message: 'success',
        data: {
          id: 'feishu-oncall',
          type: 'FEISHU',
          enabled: false,
          url: 'https://open.feishu.cn/hook/abc',
          secretConfigured: true,
          timeoutMs: 6000,
          version: 2,
          createdAt: '2026-06-15T10:00:00',
          updatedAt: '2026-06-16T10:00:00',
        },
        timestamp: 1,
      },
      status: 200,
      statusText: 'OK',
      headers: {},
      config: {} as never,
    });

    const result = await updateChannel('feishu-oncall', {
      type: 'FEISHU',
      enabled: false,
      url: 'https://open.feishu.cn/hook/abc',
      secret: 'sec_xyz',
      clearSecret: false,
      timeoutMs: 6000,
      version: 1,
    });

    const call = spy.mock.calls[0]?.[0] as {
      method?: string;
      url?: string;
      data?: Record<string, unknown>;
    };
    expect(call.method).toBe('PUT');
    expect(call.url).toBe('/api/notification/channels/feishu-oncall');
    expect(call.data).toEqual({
      type: 'FEISHU',
      enabled: false,
      url: 'https://open.feishu.cn/hook/abc',
      secret: 'sec_xyz',
      clearSecret: false,
      timeoutMs: 6000,
      version: 1,
    });

    expect(result.version).toBe(2);
  });

  it('supports clearSecret=true without leaking secret (secret omitted when undefined)', async () => {
    const spy = vi.spyOn(apiClient, 'request').mockResolvedValueOnce({
      data: {
        code: 200,
        message: 'success',
        data: {
          id: 'wh-1',
          type: 'WEBHOOK',
          enabled: true,
          url: 'https://example.com/hook',
          secretConfigured: false,
          timeoutMs: 3000,
          version: 5,
          createdAt: '2026-06-12T10:00:00',
          updatedAt: '2026-06-16T10:00:00',
        },
        timestamp: 1,
      },
      status: 200,
      statusText: 'OK',
      headers: {},
      config: {} as never,
    });

    await updateChannel('wh-1', {
      type: 'WEBHOOK',
      enabled: true,
      url: 'https://example.com/hook',
      clearSecret: true,
      timeoutMs: 3000,
      version: 4,
    });

    const call = spy.mock.calls[0]?.[0] as { data?: Record<string, unknown> };
    expect('secret' in call.data!).toBe(false);
    expect(call.data!.clearSecret).toBe(true);
  });
});

describe('notification API — deleteChannel', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('DELETEs /api/notification/channels/{id}?version=N (version in query string)', async () => {
    const spy = vi.spyOn(apiClient, 'request').mockResolvedValueOnce({
      data: { code: 200, message: 'success', data: null, timestamp: 1 },
      status: 200,
      statusText: 'OK',
      headers: {},
      config: {} as never,
    });

    await deleteChannel('feishu-oncall', 3);

    const call = spy.mock.calls[0]?.[0] as {
      method?: string;
      url?: string;
      params?: Record<string, unknown>;
      data?: unknown;
    };
    expect(call.method).toBe('DELETE');
    expect(call.url).toBe('/api/notification/channels/feishu-oncall');
    // Backend expects the version as a request param (DELETE has no body).
    expect(call.params).toEqual({ version: 3 });
    // No JSON body on DELETE — axios serialises undefined as undefined.
    expect(call.data).toBeUndefined();
  });
});