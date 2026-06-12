import { afterEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from './client';
import { ApiError } from './client';
import { sendChat } from './chat';
import type { AgentResult } from '@/types/agent';

// All tests stub the underlying axios instance so we can assert:
//   1. The HTTP method, URL and body shape are exactly right.
//   2. ApiResponse unwrapping happens correctly (no envelope leak).
//   3. Network / business errors propagate as ApiError.

describe('sendChat', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('POSTs to /api/chat/send with { content } and no message field', async () => {
    const spy = vi.spyOn(apiClient, 'request').mockResolvedValueOnce({
      data: {
        code: 200,
        message: 'success',
        data: {
          sessionId: 's-1',
          answer: 'ok',
          intentType: 'UNKNOWN',
          toolCalls: [],
          riskLevel: 'L0',
          riskDecision: 'ALLOW',
          needConfirmation: false,
          auditId: 'a-1',
        },
        timestamp: 1,
      },
      status: 200,
      statusText: 'OK',
      headers: {},
      config: {} as never,
    });

    const result = await sendChat({ content: '帮我检查当前系统健康状态' });

    expect(spy).toHaveBeenCalledTimes(1);
    const call = spy.mock.calls[0]?.[0] as { method?: string; url?: string; data?: unknown };
    expect(call.method).toBe('POST');
    expect(call.url).toBe('/api/chat/send');

    // The body MUST be exactly { content }. No `message` field, no extras.
    expect(call.data).toEqual({ content: '帮我检查当前系统健康状态' });
    expect(call.data).not.toHaveProperty('message');

    expect(result.sessionId).toBe('s-1');
    expect(result.riskLevel).toBe('L0');
  });

  it('includes sessionId in the body when provided (re-use across turns)', async () => {
    const spy = vi.spyOn(apiClient, 'request').mockResolvedValueOnce({
      data: {
        code: 200,
        message: 'success',
        data: { sessionId: 's-keep' },
        timestamp: 1,
      },
      status: 200,
      statusText: 'OK',
      headers: {},
      config: {} as never,
    });

    await sendChat({ content: '下一条', sessionId: 's-keep' });

    const call = spy.mock.calls[0]?.[0] as { data?: unknown };
    expect(call.data).toEqual({ content: '下一条', sessionId: 's-keep' });
  });

  it('omits sessionId entirely when not provided (no undefined field)', async () => {
    const spy = vi.spyOn(apiClient, 'request').mockResolvedValueOnce({
      data: { code: 200, message: 'success', data: {}, timestamp: 1 },
      status: 200,
      statusText: 'OK',
      headers: {},
      config: {} as never,
    });

    await sendChat({ content: '你好' });

    const call = spy.mock.calls[0]?.[0] as { data?: Record<string, unknown> };
    // The body must not carry `sessionId: undefined` — it should be absent.
    expect('sessionId' in call.data!).toBe(false);
    expect(Object.keys(call.data!)).toEqual(['content']);
  });

  it('unwraps the ApiResponse envelope and returns AgentResult from data', async () => {
    const agentResult: AgentResult = {
      sessionId: 's-2',
      answer: 'blocked',
      intentType: 'UNKNOWN',
      toolCalls: [
        { toolName: 'service_status_tool', status: 'blocked', summary: 'blocked' },
      ],
      riskLevel: 'L4',
      riskDecision: 'BLOCK',
      needConfirmation: false,
      auditId: 'a-block',
    };
    vi.spyOn(apiClient, 'request').mockResolvedValueOnce({
      data: {
        code: 200,
        message: 'success',
        data: agentResult,
        timestamp: 1,
        traceId: 't-1',
      },
      status: 200,
      statusText: 'OK',
      headers: {},
      config: {} as never,
    });

    const result = await sendChat({ content: 'rm -rf /' });

    expect(result).toEqual(agentResult);
    expect(result.auditId).toBe('a-block');
    expect(result.riskDecision).toBe('BLOCK');
  });

  it('rejects with ApiError on transport / business failure', async () => {
    vi.spyOn(apiClient, 'request').mockResolvedValueOnce({
      data: {
        code: 500,
        message: '服务器内部错误',
        data: null,
        timestamp: 1,
        traceId: 't-err',
      },
      status: 500,
      statusText: 'ERROR',
      headers: {},
      config: {} as never,
    });

    await expect(sendChat({ content: 'x' })).rejects.toBeInstanceOf(ApiError);
  });

  it('sends the full ChatRequest body verbatim (no extras added)', async () => {
    const spy = vi.spyOn(apiClient, 'request').mockResolvedValueOnce({
      data: { code: 200, message: 'success', data: {}, timestamp: 1 },
      status: 200,
      statusText: 'OK',
      headers: {},
      config: {} as never,
    });

    await sendChat({ content: 'ping', sessionId: 's-ping' });

    const call = spy.mock.calls[0]?.[0] as { data?: Record<string, unknown> };
    // The body must contain EXACTLY content + sessionId — no other fields.
    expect(Object.keys(call.data!).sort()).toEqual(['content', 'sessionId']);
  });
});
