import { afterEach, describe, expect, it, vi } from 'vitest';
import { apiClient, ApiError } from './client';
import {
  createPlan,
  deletePlan,
  disablePlan,
  enablePlan,
  getExecution,
  getPlan,
  getTemplates,
  listExecutions,
  listPlans,
  runPlan,
  updatePlan,
} from './inspection';

// All tests stub the underlying axios instance so we can assert the
// exact wire contract with the backend InspectionController
// (com.kylinops.inspection.api.InspectionController).
//
// The contract is locked by Task 7 of P1-02:
//   GET    /api/inspections/templates                        → InspectionTemplateView[]
//   GET    /api/inspections/plans                            → InspectionPlanSummary[]
//   POST   /api/inspections/plans                            → InspectionPlanDetail
//   GET    /api/inspections/plans/{planId}                   → InspectionPlanDetail
//   PUT    /api/inspections/plans/{planId}                   → InspectionPlanDetail
//   POST   /api/inspections/plans/{planId}/enable            → InspectionPlanDetail
//   POST   /api/inspections/plans/{planId}/disable           → InspectionPlanDetail
//   DELETE /api/inspections/plans/{planId}                   → void
//   POST   /api/inspections/plans/{planId}/run               → RunResponse
//   GET    /api/inspections/executions?planId=&status=&page=&size= → InspectionExecutionSummary[]
//   GET    /api/inspections/executions/{executionId}         → InspectionExecutionDetail
//
// 红线:
//   * runPlan 不带 body — operator 由后端从 session 取,前端绝不传
//   * updatePlan 必须带 version(乐观锁)
//   * deletePlan 无 query string,也无 body
//   * enable/disable/run 无 body

describe('inspection API — getTemplates', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('GETs /api/inspections/templates and returns the unwrapped view array', async () => {
    const payload = [
      {
        templateType: 'HEALTH',
        displayName: '服务健康度巡检',
        fields: [
          { name: 'serviceName', label: '服务名', type: 'string', required: true, constraints: {} },
          { name: 'cpuWarningPercent', label: 'CPU 警告百分比', type: 'number', required: false, defaultValue: '80', constraints: { min: 50, max: 100 } },
        ],
        riskLevels: { system_info_tool: 'L0' },
        keyToolNames: ['system_info_tool'],
      },
    ];
    const spy = vi.spyOn(apiClient, 'request').mockResolvedValueOnce({
      data: { code: 200, message: 'success', data: payload, timestamp: 1 },
      status: 200,
      statusText: 'OK',
      headers: {},
      config: {} as never,
    });

    const result = await getTemplates();

    const call = spy.mock.calls[0]?.[0] as { method?: string; url?: string };
    expect(call.method).toBe('GET');
    expect(call.url).toBe('/api/inspections/templates');
    expect(result).toHaveLength(1);
    expect(result[0]?.templateType).toBe('HEALTH');
    expect(result[0]?.displayName).toBe('服务健康度巡检');
    expect(result[0]?.fields).toHaveLength(2);
  });
});

describe('inspection API — listPlans', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('GETs /api/inspections/plans and returns the summary array', async () => {
    const payload = [
      {
        planId: 'plan-001',
        name: 'nginx 健康巡检',
        description: '每日 03:00 巡检 nginx',
        templateType: 'HEALTH',
        scheduleType: 'DAILY',
        timezone: 'Asia/Shanghai',
        notificationPolicy: 'ON_ABNORMAL',
        enabled: true,
        nextRunAt: '2026-06-20T03:00:00',
        lastRunAt: '2026-06-19T03:00:05',
        version: 3,
      },
    ];
    const spy = vi.spyOn(apiClient, 'request').mockResolvedValueOnce({
      data: { code: 200, message: 'success', data: payload, timestamp: 1 },
      status: 200,
      statusText: 'OK',
      headers: {},
      config: {} as never,
    });

    const result = await listPlans();

    const call = spy.mock.calls[0]?.[0] as { method?: string; url?: string };
    expect(call.method).toBe('GET');
    expect(call.url).toBe('/api/inspections/plans');
    expect(result).toHaveLength(1);
    expect(result[0]?.planId).toBe('plan-001');
    expect(result[0]?.enabled).toBe(true);
    expect(result[0]?.version).toBe(3);
  });
});

describe('inspection API — getPlan', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('GETs /api/inspections/plans/{planId} and returns the detail view', async () => {
    const payload = {
      planId: 'plan-001',
      name: 'nginx 健康巡检',
      description: '每日 03:00 巡检 nginx',
      templateType: 'HEALTH',
      scheduleType: 'DAILY',
      timezone: 'Asia/Shanghai',
      notificationPolicy: 'ON_ABNORMAL',
      enabled: true,
      nextRunAt: '2026-06-20T03:00:00',
      lastRunAt: '2026-06-19T03:00:05',
      version: 3,
      templateParamsJson: '{"serviceName":"nginx"}',
      thresholdsJson: '{"cpuWarningPercent":80}',
      scheduleConfigJson: '{"localTime":"03:00:00"}',
      createdAt: '2026-06-12T10:00:00',
      updatedAt: '2026-06-15T11:00:00',
    };
    const spy = vi.spyOn(apiClient, 'request').mockResolvedValueOnce({
      data: { code: 200, message: 'success', data: payload, timestamp: 1 },
      status: 200,
      statusText: 'OK',
      headers: {},
      config: {} as never,
    });

    const result = await getPlan('plan-001');

    const call = spy.mock.calls[0]?.[0] as { method?: string; url?: string };
    expect(call.method).toBe('GET');
    expect(call.url).toBe('/api/inspections/plans/plan-001');
    expect(result.planId).toBe('plan-001');
    expect(result.templateParamsJson).toBe('{"serviceName":"nginx"}');
    expect(result.thresholdsJson).toBe('{"cpuWarningPercent":80}');
  });
});

describe('inspection API — createPlan', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('POSTs to /api/inspections/plans with the create-payload body', async () => {
    const payload = {
      planId: 'plan-002',
      name: '磁盘巡检',
      description: '每周日凌晨扫描大文件',
      templateType: 'DISK' as const,
      templateParams: { scanDir: '/tmp' },
      thresholds: { diskWarningPercent: 85 },
      scheduleType: 'WEEKLY' as const,
      localTime: '03:00:00',
      timezone: 'Asia/Shanghai',
      dayOfWeek: 'SUNDAY' as const,
      notificationPolicy: 'ON_ABNORMAL' as const,
    };
    const spy = vi.spyOn(apiClient, 'request').mockResolvedValueOnce({
      data: {
        code: 200,
        message: 'success',
        data: { ...payload, enabled: false, version: 0, nextRunAt: null, lastRunAt: null },
        timestamp: 1,
      },
      status: 200,
      statusText: 'OK',
      headers: {},
      config: {} as never,
    });

    const result = await createPlan(payload);

    const call = spy.mock.calls[0]?.[0] as {
      method?: string;
      url?: string;
      data?: Record<string, unknown>;
    };
    expect(call.method).toBe('POST');
    expect(call.url).toBe('/api/inspections/plans');
    expect(call.data?.templateType).toBe('DISK');
    expect(call.data?.scheduleType).toBe('WEEKLY');
    expect(call.data?.dayOfWeek).toBe('SUNDAY');
    expect(call.data?.templateParams).toEqual({ scanDir: '/tmp' });
    expect(result.planId).toBe('plan-002');
  });
});

describe('inspection API — updatePlan', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('PUTs to /api/inspections/plans/{planId} with version + partial fields', async () => {
    const payload = {
      version: 3,
      enabled: true,
      description: '调整后的描述',
    };
    const spy = vi.spyOn(apiClient, 'request').mockResolvedValueOnce({
      data: {
        code: 200,
        message: 'success',
        data: {
          planId: 'plan-001',
          name: 'nginx 健康巡检',
          templateType: 'HEALTH',
          scheduleType: 'DAILY',
          timezone: 'Asia/Shanghai',
          notificationPolicy: 'ON_ABNORMAL',
          enabled: true,
          version: 4,
        },
        timestamp: 1,
      },
      status: 200,
      statusText: 'OK',
      headers: {},
      config: {} as never,
    });

    await updatePlan('plan-001', payload);

    const call = spy.mock.calls[0]?.[0] as {
      method?: string;
      url?: string;
      data?: Record<string, unknown>;
    };
    expect(call.method).toBe('PUT');
    expect(call.url).toBe('/api/inspections/plans/plan-001');
    expect(call.data?.version).toBe(3);
    expect(call.data?.enabled).toBe(true);
    expect(call.data?.description).toBe('调整后的描述');
    // 关键:update 不允许偷带 enabled 等
    expect(Object.keys(call.data!).sort()).toEqual(['description', 'enabled', 'version']);
  });
});

describe('inspection API — enablePlan / disablePlan', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('enablePlan POSTs to /api/inspections/plans/{planId}/enable (no body)', async () => {
    const spy = vi.spyOn(apiClient, 'request').mockResolvedValueOnce({
      data: {
        code: 200,
        message: 'success',
        data: { planId: 'plan-001', enabled: true, version: 4 },
        timestamp: 1,
      },
      status: 200,
      statusText: 'OK',
      headers: {},
      config: {} as never,
    });

    await enablePlan('plan-001');

    const call = spy.mock.calls[0]?.[0] as { method?: string; url?: string; data?: unknown };
    expect(call.method).toBe('POST');
    expect(call.url).toBe('/api/inspections/plans/plan-001/enable');
    expect(call.data).toBeUndefined();
  });

  it('disablePlan POSTs to /api/inspections/plans/{planId}/disable (no body)', async () => {
    const spy = vi.spyOn(apiClient, 'request').mockResolvedValueOnce({
      data: {
        code: 200,
        message: 'success',
        data: { planId: 'plan-001', enabled: false, version: 5 },
        timestamp: 1,
      },
      status: 200,
      statusText: 'OK',
      headers: {},
      config: {} as never,
    });

    await disablePlan('plan-001');

    const call = spy.mock.calls[0]?.[0] as { method?: string; url?: string; data?: unknown };
    expect(call.method).toBe('POST');
    expect(call.url).toBe('/api/inspections/plans/plan-001/disable');
    expect(call.data).toBeUndefined();
  });
});

describe('inspection API — deletePlan', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('DELETEs /api/inspections/plans/{planId} (no body, no query string)', async () => {
    const spy = vi.spyOn(apiClient, 'request').mockResolvedValueOnce({
      data: { code: 200, message: 'success', data: null, timestamp: 1 },
      status: 200,
      statusText: 'OK',
      headers: {},
      config: {} as never,
    });

    await deletePlan('plan-001');

    const call = spy.mock.calls[0]?.[0] as {
      method?: string;
      url?: string;
      params?: Record<string, unknown>;
      data?: unknown;
    };
    expect(call.method).toBe('DELETE');
    expect(call.url).toBe('/api/inspections/plans/plan-001');
    expect(call.params ?? {}).toEqual({});
    expect(call.data).toBeUndefined();
  });
});

describe('inspection API — runPlan (红线:无 body,operator 由后端取)', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('POSTs to /api/inspections/plans/{planId}/run with no body and returns executionId', async () => {
    const spy = vi.spyOn(apiClient, 'request').mockResolvedValueOnce({
      data: {
        code: 200,
        message: 'success',
        data: { executionId: 'exec-001', status: 'RUNNING' },
        timestamp: 1,
      },
      status: 200,
      statusText: 'OK',
      headers: {},
      config: {} as never,
    });

    const result = await runPlan('plan-001');

    const call = spy.mock.calls[0]?.[0] as {
      method?: string;
      url?: string;
      data?: unknown;
    };
    expect(call.method).toBe('POST');
    expect(call.url).toBe('/api/inspections/plans/plan-001/run');
    // 关键红线:不能传 operator / 任何 body
    expect(call.data).toBeUndefined();
    expect(result.executionId).toBe('exec-001');
    expect(result.status).toBe('RUNNING');
  });
});

describe('inspection API — listExecutions', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('GETs /api/inspections/executions (no params when none provided)', async () => {
    const spy = vi.spyOn(apiClient, 'request').mockResolvedValueOnce({
      data: {
        code: 200,
        message: 'success',
        data: [
          {
            planId: 'plan-001',
            executionId: 'exec-001',
            status: 'SUCCESS',
            triggerType: 'SCHEDULED',
            operator: 'admin',
            startedAt: '2026-06-19T03:00:00',
            finishedAt: '2026-06-19T03:00:05',
            abnormal: false,
            summary: '巡检完成',
          },
        ],
        timestamp: 1,
      },
      status: 200,
      statusText: 'OK',
      headers: {},
      config: {} as never,
    });

    const result = await listExecutions();

    const call = spy.mock.calls[0]?.[0] as {
      method?: string;
      url?: string;
      params?: Record<string, unknown>;
    };
    expect(call.method).toBe('GET');
    expect(call.url).toBe('/api/inspections/executions');
    expect(call.params ?? {}).toEqual({});
    expect(result).toHaveLength(1);
    expect(result[0]?.abnormal).toBe(false);
  });

  it('passes planId / status / page / size as query params when supplied', async () => {
    const spy = vi.spyOn(apiClient, 'request').mockResolvedValueOnce({
      data: { code: 200, message: 'success', data: [], timestamp: 1 },
      status: 200,
      statusText: 'OK',
      headers: {},
      config: {} as never,
    });

    await listExecutions({ planId: 'plan-001', status: 'FAILED', page: 0, size: 20 });

    const call = spy.mock.calls[0]?.[0] as { params?: Record<string, unknown> };
    expect(call.params).toEqual({ planId: 'plan-001', status: 'FAILED', page: 0, size: 20 });
  });
});

describe('inspection API — getExecution', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('GETs /api/inspections/executions/{executionId} and returns the detail view', async () => {
    const payload = {
      planId: 'plan-001',
      executionId: 'exec-001',
      status: 'FAILED' as const,
      triggerType: 'MANUAL' as const,
      operator: 'admin',
      startedAt: '2026-06-19T03:00:00',
      finishedAt: '2026-06-19T03:00:05',
      abnormal: true,
      summary: '巡检 FAILED: 磁盘使用率 95% 超过阈值',
      planSnapshotJson: '{}',
      auditId: 'audit-001',
      reportId: 'report-001',
      errorMessage: '巡检 FAILED: 磁盘使用率 95% 超过阈值',
    };
    const spy = vi.spyOn(apiClient, 'request').mockResolvedValueOnce({
      data: { code: 200, message: 'success', data: payload, timestamp: 1 },
      status: 200,
      statusText: 'OK',
      headers: {},
      config: {} as never,
    });

    const result = await getExecution('exec-001');

    const call = spy.mock.calls[0]?.[0] as { method?: string; url?: string };
    expect(call.method).toBe('GET');
    expect(call.url).toBe('/api/inspections/executions/exec-001');
    expect(result.executionId).toBe('exec-001');
    expect(result.abnormal).toBe(true);
    expect(result.auditId).toBe('audit-001');
    expect(result.errorMessage).toContain('磁盘使用率');
  });
});

describe('inspection API — ApiError contract', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('rejects with ApiError carrying backend code/message on 业务错误 (e.g. 404 计划不存在)', async () => {
    vi.spyOn(apiClient, 'request').mockResolvedValueOnce({
      data: {
        code: 404,
        message: '[plan] 不存在: plan-missing',
        data: null,
        timestamp: 1,
        traceId: 't-404',
      },
      status: 404,
      statusText: 'NOT FOUND',
      headers: {},
      config: {} as never,
    });

    try {
      await getPlan('plan-missing');
      throw new Error('expected to throw');
    } catch (err) {
      expect(err).toBeInstanceOf(ApiError);
      // ApiError 透传后端 code 与 message
      const apiErr = err as ApiError;
      expect(apiErr.code).toBe(404);
      expect(apiErr.message).toBe('[plan] 不存在: plan-missing');
      expect(apiErr.httpStatus).toBe(404);
    }
  });

  it('rejects with ApiError on 400 校验失败 (e.g. 缺 serviceName)', async () => {
    vi.spyOn(apiClient, 'request').mockResolvedValueOnce({
      data: {
        code: 400,
        message: '[serviceName] 不能为空',
        data: null,
        timestamp: 1,
      },
      status: 400,
      statusText: 'BAD REQUEST',
      headers: {},
      config: {} as never,
    });

    await expect(
      createPlan({
        name: 'bad',
        templateType: 'HEALTH',
        templateParams: {},
        thresholds: {},
        scheduleType: 'DAILY',
        localTime: '03:00:00',
        timezone: 'Asia/Shanghai',
        notificationPolicy: 'ON_ABNORMAL',
      } as never),
    ).rejects.toBeInstanceOf(ApiError);
  });
});
