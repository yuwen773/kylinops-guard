import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import ElementPlus from 'element-plus';

const elementPlusMocks = vi.hoisted(() => ({
  elMessageErrorMock: vi.fn(),
}));

vi.mock('element-plus', async () => {
  const actual = await vi.importActual<typeof import('element-plus')>('element-plus');
  return {
    ...actual,
    ElMessage: {
      error: elementPlusMocks.elMessageErrorMock,
      success: vi.fn(),
      info: vi.fn(),
    },
  };
});

import PlanFormDialog from '../components/PlanFormDialog.vue';
import type {
  InspectionTemplateField,
  InspectionTemplateView,
} from '@/types/inspection';

const HEALTH_FIELDS: InspectionTemplateField[] = [
  { name: 'serviceName', label: '服务名', type: 'string', required: true, constraints: {} },
  { name: 'cpuWarningPercent', label: 'CPU 警告百分比', type: 'number', required: false, defaultValue: '80', constraints: { min: 50, max: 100 } },
  { name: 'memoryWarningPercent', label: '内存警告百分比', type: 'number', required: false, defaultValue: '80', constraints: { min: 50, max: 100 } },
  { name: 'diskWarningPercent', label: '磁盘警告百分比', type: 'number', required: false, defaultValue: '85', constraints: { min: 50, max: 100 } },
];

const DISK_FIELDS: InspectionTemplateField[] = [
  { name: 'scanDir', label: '扫描路径', type: 'string', required: true, constraints: {} },
  { name: 'logServiceName', label: '日志服务名(可选)', type: 'string', required: false, constraints: {} },
  { name: 'diskWarningPercent', label: '磁盘警告百分比', type: 'number', required: false, defaultValue: '85', constraints: { min: 50, max: 100 } },
  { name: 'largeFileMinMb', label: '大文件阈值 MB', type: 'number', required: false, defaultValue: '1024', constraints: { min: 100, max: 1048576 } },
];

const SERVICE_FIELDS: InspectionTemplateField[] = [
  { name: 'serviceName', label: '服务名', type: 'string', required: true, constraints: {} },
  { name: 'expectedPort', label: '预期端口(可选)', type: 'number', required: false, constraints: { min: 1, max: 65535 } },
];

const TEMPLATES: InspectionTemplateView[] = [
  { templateType: 'HEALTH', displayName: '服务健康度巡检', fields: HEALTH_FIELDS },
  { templateType: 'DISK', displayName: '磁盘巡检', fields: DISK_FIELDS },
  { templateType: 'SERVICE', displayName: '服务巡检', fields: SERVICE_FIELDS },
];

async function mountDialog(initial?: { templateType?: 'HEALTH' | 'DISK' | 'SERVICE' }) {
  const wrapper = mount(PlanFormDialog, {
    props: { visible: true, templates: TEMPLATES },
    global: { plugins: [ElementPlus] },
  });
  await flushPromises();
  if (initial?.templateType) {
    // el-radio-group 在 jsdom 下 setValue 不友好,改用 expose 的 setTemplateType。
    (wrapper.vm as unknown as { setTemplateType: (v: 'HEALTH' | 'DISK' | 'SERVICE') => void })
      .setTemplateType(initial.templateType);
    await flushPromises();
  }
  return wrapper;
}

interface DialogVm {
  form: {
    name: string;
    description: string;
    templateType: 'HEALTH' | 'DISK' | 'SERVICE';
    templateParams: Record<string, unknown>;
    thresholds: Record<string, unknown>;
    scheduleType: 'DAILY' | 'WEEKLY' | 'MONTHLY';
    localTime: string;
    timezone: string;
    dayOfWeek: 'MONDAY' | 'TUESDAY' | 'WEDNESDAY' | 'THURSDAY' | 'FRIDAY' | 'SATURDAY' | 'SUNDAY' | '';
    dayOfMonth: number | undefined;
    notificationPolicy: 'ALWAYS' | 'ON_ABNORMAL' | 'NEVER';
  };
  errors: Record<string, string>;
}

function setField(wrapper: ReturnType<typeof mount>, field: string, value: unknown): void {
  (wrapper.vm as unknown as DialogVm).form = {
    ...(wrapper.vm as unknown as DialogVm).form,
    [field]: value,
  } as DialogVm['form'];
}

describe('PlanFormDialog — 模板字段动态渲染', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('HEALTH 模板:渲染 serviceName + 三个 number 阈值字段', async () => {
    const wrapper = await mountDialog({ templateType: 'HEALTH' });

    expect(wrapper.find('[data-testid="plan-form-field-serviceName"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="plan-form-field-cpuWarningPercent"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="plan-form-field-memoryWarningPercent"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="plan-form-field-diskWarningPercent"]').exists()).toBe(true);
    // serviceName 必填:label 含 *
    const serviceFormItem = wrapper.find('[data-testid="plan-form-field-serviceName"]');
    expect(serviceFormItem.text()).toContain('*');
  });

  it('DISK 模板:渲染 scanDir(必填) + logServiceName(可选) + 两个 number 字段', async () => {
    const wrapper = await mountDialog({ templateType: 'DISK' });

    expect(wrapper.find('[data-testid="plan-form-field-scanDir"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="plan-form-field-logServiceName"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="plan-form-field-largeFileMinMb"]').exists()).toBe(true);
  });

  it('SERVICE 模板:渲染 serviceName(必填) + expectedPort(可选 number)', async () => {
    const wrapper = await mountDialog({ templateType: 'SERVICE' });

    expect(wrapper.find('[data-testid="plan-form-field-serviceName"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="plan-form-field-expectedPort"]').exists()).toBe(true);
  });

  it('切换模板 → 字段重新渲染', async () => {
    const wrapper = await mountDialog({ templateType: 'HEALTH' });
    expect(wrapper.find('[data-testid="plan-form-field-serviceName"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="plan-form-field-scanDir"]').exists()).toBe(false);

    (wrapper.vm as unknown as { setTemplateType: (v: 'DISK') => void }).setTemplateType('DISK');
    await flushPromises();

    expect(wrapper.find('[data-testid="plan-form-field-serviceName"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="plan-form-field-scanDir"]').exists()).toBe(true);
  });
});

describe('PlanFormDialog — 提交校验', () => {
  beforeEach(() => {
    elementPlusMocks.elMessageErrorMock.mockReset();
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('HEALTH 缺 serviceName → 提交时展示字段级错误,emit submit 不触发', async () => {
    const wrapper = await mountDialog({ templateType: 'HEALTH' });
    const vm = wrapper.vm as unknown as DialogVm;
    vm.form.name = '测试计划';
    vm.form.localTime = '03:00:00';
    await flushPromises();

    await wrapper.find('[data-testid="plan-form-submit"]').trigger('click');
    await flushPromises();

    // 错误信息应包含字段名
    const errEl = wrapper.find('[data-testid="plan-form-error-serviceName"]');
    expect(errEl.exists()).toBe(true);
    expect(errEl.text()).toContain('服务名');
    expect(wrapper.emitted('submit')).toBeFalsy();
  });

  it('填齐必填 → emit submit 携带正确 payload', async () => {
    const wrapper = await mountDialog({ templateType: 'HEALTH' });
    const vm = wrapper.vm as unknown as DialogVm;
    vm.form.name = 'nginx 健康巡检';
    vm.form.description = '测试描述';
    vm.form.templateParams = { serviceName: 'nginx' };
    vm.form.thresholds = { cpuWarningPercent: 85 };
    vm.form.scheduleType = 'DAILY';
    vm.form.localTime = '03:00:00';
    vm.form.timezone = 'Asia/Shanghai';
    vm.form.notificationPolicy = 'ON_ABNORMAL';
    await flushPromises();

    await wrapper.find('[data-testid="plan-form-submit"]').trigger('click');
    await flushPromises();

    expect(wrapper.emitted('submit')).toBeTruthy();
    const payload = wrapper.emitted('submit')![0]![0] as Record<string, unknown>;
    expect(payload.templateType).toBe('HEALTH');
    expect(payload.name).toBe('nginx 健康巡检');
    expect(payload.description).toBe('测试描述');
    expect(payload.scheduleType).toBe('DAILY');
    expect(payload.localTime).toBe('03:00:00');
    expect(payload.timezone).toBe('Asia/Shanghai');
    expect(payload.notificationPolicy).toBe('ON_ABNORMAL');
    expect(payload.templateParams).toEqual({ serviceName: 'nginx' });
    expect(payload.thresholds).toEqual({ cpuWarningPercent: 85 });
  });

  it('WEEKLY schedule → 必填 dayOfWeek,未填时提交失败', async () => {
    const wrapper = await mountDialog({ templateType: 'HEALTH' });
    const vm = wrapper.vm as unknown as DialogVm;
    vm.form.name = '周巡';
    vm.form.templateParams = { serviceName: 'nginx' };
    vm.form.scheduleType = 'WEEKLY';
    vm.form.localTime = '03:00:00';
    vm.form.timezone = 'Asia/Shanghai';
    vm.form.notificationPolicy = 'ON_ABNORMAL';
    await flushPromises();

    await wrapper.find('[data-testid="plan-form-submit"]').trigger('click');
    await flushPromises();

    expect(wrapper.find('[data-testid="plan-form-error-dayOfWeek"]').exists()).toBe(true);
    expect(wrapper.emitted('submit')).toBeFalsy();
  });
});
