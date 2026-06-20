<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import TemplateFields from './TemplateFields.vue';
import type {
  InspectionNotificationPolicy,
  InspectionPlanRequest,
  InspectionPlanUpdateRequest,
  InspectionScheduleType,
  InspectionTemplateType,
  InspectionTemplateView,
} from '@/types/inspection';

/**
 * PlanFormDialog — 新建 / 编辑巡检计划弹窗。
 *
 * 设计要点:
 *   * 模板字段由 props.templates 动态驱动(单一事实来源);
 *     切换 templateType 时清空 templateParams / thresholds,defaults 重新填充。
 *   * localTime 用 el-time-picker + value-format="HH:mm:ss"(后端 LocalTime 格式)。
 *   * 提交校验错误回显到对应字段(el-form-item 的 error 属性),
 *     testid 形如 plan-form-error-{fieldName}。
 *   * edit 模式必须带 version(乐观锁);通过 props.initialPlan 传入。
 */
type WeekDay = 'MONDAY' | 'TUESDAY' | 'WEDNESDAY' | 'THURSDAY' | 'FRIDAY' | 'SATURDAY' | 'SUNDAY';

const props = defineProps<{
  visible: boolean;
  /** null = create mode,否则进入 edit mode 且需要 version。 */
  initialPlan?: InspectionPlanRequest & { version?: number; planId?: string } | null;
  templates: InspectionTemplateView[];
  submitting?: boolean;
}>();

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void;
  (e: 'submit', payload: InspectionPlanRequest | InspectionPlanUpdateRequest, mode: 'create' | 'update'): void;
}>();

const formRef = ref();
const TEMPLATE_TYPES: { value: InspectionTemplateType; label: string }[] = [
  { value: 'HEALTH', label: '健康' },
  { value: 'DISK', label: '磁盘' },
  { value: 'SERVICE', label: '服务' },
];
const SCHEDULE_TYPES: { value: InspectionScheduleType; label: string }[] = [
  { value: 'DAILY', label: '每日' },
  { value: 'WEEKLY', label: '每周' },
  { value: 'MONTHLY', label: '每月' },
];
const POLICIES: { value: InspectionNotificationPolicy; label: string }[] = [
  { value: 'ALWAYS', label: '始终通知' },
  { value: 'ON_ABNORMAL', label: '异常时通知' },
  { value: 'NEVER', label: '不通知' },
];
const WEEKDAYS: { value: WeekDay; label: string }[] = [
  { value: 'MONDAY', label: '周一' },
  { value: 'TUESDAY', label: '周二' },
  { value: 'WEDNESDAY', label: '周三' },
  { value: 'THURSDAY', label: '周四' },
  { value: 'FRIDAY', label: '周五' },
  { value: 'SATURDAY', label: '周六' },
  { value: 'SUNDAY', label: '周日' },
];

interface FormState {
  name: string;
  description: string;
  templateType: InspectionTemplateType;
  templateParams: Record<string, unknown>;
  thresholds: Record<string, unknown>;
  scheduleType: InspectionScheduleType;
  localTime: string;
  timezone: string;
  dayOfWeek: WeekDay | '';
  dayOfMonth: number | undefined;
  notificationPolicy: InspectionNotificationPolicy;
}

const form = reactive<FormState>({
  name: '',
  description: '',
  templateType: 'HEALTH',
  templateParams: {},
  thresholds: {},
  scheduleType: 'DAILY',
  localTime: '03:00:00',
  timezone: 'Asia/Shanghai',
  dayOfWeek: '',
  dayOfMonth: undefined,
  notificationPolicy: 'ON_ABNORMAL',
});

const errors = reactive<Record<string, string>>({});

// 暴露 form / errors / setTemplateType 给父组件和测试使用。
// el-radio-group 在 jsdom 下 setValue 不友好(渲染成 div),所以测试场景
// 直接通过暴露的 form 状态设值。
defineExpose({
  form,
  errors,
  setTemplateType(value: InspectionTemplateType): void {
    form.templateType = value;
  },
});

const isEdit = computed(() => props.initialPlan != null);

const currentTemplate = computed(() =>
  props.templates.find((t) => t.templateType === form.templateType) ?? null,
);

const currentFields = computed(() => currentTemplate.value?.fields ?? []);

// 当 visible 切换为 true 时,根据 mode 初始化 / 填充表单。
watch(
  () => props.visible,
  (next) => {
    if (!next) return;
    errors.name = '';
    errors.templateType = '';
    errors.localTime = '';
    errors.timezone = '';
    errors.notificationPolicy = '';
    errors.dayOfWeek = '';
    errors.dayOfMonth = '';
    errors.scheduleType = '';
    for (const key of Object.keys(errors)) {
      if (!['name', 'templateType', 'localTime', 'timezone', 'notificationPolicy', 'dayOfWeek', 'dayOfMonth', 'scheduleType'].includes(key)) {
        delete errors[key];
      }
    }
    if (props.initialPlan) {
      form.name = props.initialPlan.name;
      form.description = props.initialPlan.description ?? '';
      form.templateType = props.initialPlan.templateType;
      form.templateParams = { ...(props.initialPlan.templateParams ?? {}) };
      form.thresholds = { ...(props.initialPlan.thresholds ?? {}) };
      form.scheduleType = props.initialPlan.scheduleType;
      form.localTime = props.initialPlan.localTime ?? '03:00:00';
      form.timezone = props.initialPlan.timezone ?? 'Asia/Shanghai';
      form.dayOfWeek = (props.initialPlan.dayOfWeek as WeekDay) ?? '';
      form.dayOfMonth = props.initialPlan.dayOfMonth;
      form.notificationPolicy = props.initialPlan.notificationPolicy;
    } else {
      form.name = '';
      form.description = '';
      form.templateType = 'HEALTH';
      form.templateParams = {};
      form.thresholds = {};
      form.scheduleType = 'DAILY';
      form.localTime = '03:00:00';
      form.timezone = 'Asia/Shanghai';
      form.dayOfWeek = '';
      form.dayOfMonth = undefined;
      form.notificationPolicy = 'ON_ABNORMAL';
    }
  },
  { immediate: true },
);

// templateType 切换时清空 templateParams / thresholds(defaults 重新填)。
watch(
  () => form.templateType,
  () => {
    form.templateParams = {};
    form.thresholds = {};
    for (const key of Object.keys(errors)) {
      if (!['name', 'templateType', 'localTime', 'timezone', 'notificationPolicy', 'dayOfWeek', 'dayOfMonth', 'scheduleType'].includes(key)) {
        delete errors[key];
      }
    }
    // 填 defaults
    for (const field of currentFields.value) {
      if (field.defaultValue == null) continue;
      if (field.type === 'number') {
        const num = Number(field.defaultValue);
        if (!Number.isNaN(num)) form.thresholds[field.name] = num;
      } else if (field.type === 'string') {
        form.templateParams[field.name] = field.defaultValue;
      }
    }
  },
);

function close(): void {
  emit('update:visible', false);
}

function validateAndSubmit(): void {
  // 清错
  for (const key of Object.keys(errors)) delete errors[key];

  let ok = true;
  if (!form.name.trim()) {
    errors.name = '[name] 不能为空';
    ok = false;
  }
  if (!form.templateType) {
    errors.templateType = '[templateType] 不能为空';
    ok = false;
  }
  if (!form.localTime) {
    errors.localTime = '[localTime] 不能为空';
    ok = false;
  }
  if (!form.timezone.trim()) {
    errors.timezone = '[timezone] 不能为空';
    ok = false;
  }
  if (!form.notificationPolicy) {
    errors.notificationPolicy = '[notificationPolicy] 不能为空';
    ok = false;
  }
  if (form.scheduleType === 'WEEKLY' && !form.dayOfWeek) {
    errors.dayOfWeek = '[dayOfWeek] 不能为空';
    ok = false;
  }
  if (form.scheduleType === 'MONTHLY' && (form.dayOfMonth == null || form.dayOfMonth < 1 || form.dayOfMonth > 31)) {
    errors.dayOfMonth = '[dayOfMonth] 必须在 1-31 之间';
    ok = false;
  }
  // 模板必填字段
  for (const field of currentFields.value) {
    if (field.required) {
      const value = field.type === 'string'
        ? form.templateParams[field.name]
        : form.thresholds[field.name];
      if (value === undefined || value === null || value === '') {
        errors[field.name] = `[${field.name}] 不能为空`;
        ok = false;
      }
    }
  }
  if (!ok) return;

  if (isEdit.value && props.initialPlan?.version != null) {
    // 部分更新:仅传用户填过的字段;description 一律带上(允许清空传 '' 由后端处理)
    const payload: InspectionPlanUpdateRequest = {
      version: props.initialPlan.version,
      description: form.description,
      templateType: form.templateType,
      templateParams: { ...form.templateParams },
      thresholds: { ...form.thresholds },
      scheduleType: form.scheduleType,
      localTime: form.localTime,
      timezone: form.timezone,
      dayOfWeek: form.dayOfWeek === '' ? undefined : (form.dayOfWeek as WeekDay),
      dayOfMonth: form.dayOfMonth,
      notificationPolicy: form.notificationPolicy,
    };
    emit('submit', payload, 'update');
  } else {
    const payload: InspectionPlanRequest = {
      name: form.name,
      description: form.description || undefined,
      templateType: form.templateType,
      templateParams: { ...form.templateParams },
      thresholds: { ...form.thresholds },
      scheduleType: form.scheduleType,
      localTime: form.localTime,
      timezone: form.timezone,
      dayOfWeek: form.dayOfWeek === '' ? undefined : (form.dayOfWeek as WeekDay),
      dayOfMonth: form.dayOfMonth,
      notificationPolicy: form.notificationPolicy,
    };
    emit('submit', payload, 'create');
  }
}
</script>

<template>
  <el-dialog
    :model-value="visible"
    :title="isEdit ? '编辑巡检计划' : '新建巡检计划'"
    width="640"
    :close-on-click-modal="false"
    align-center
    @update:model-value="(v: boolean) => emit('update:visible', v)"
    @close="close"
  >
    <el-form
      ref="formRef"
      label-position="top"
      :model="form"
      data-testid="plan-form-dialog"
    >
      <el-form-item label="计划名称 *" :error="errors.name ?? ''" data-testid="plan-form-name" prop="name">
        <el-input v-model="form.name" placeholder="请输入计划名称" maxlength="100" show-word-limit />
      </el-form-item>

      <el-form-item label="描述" data-testid="plan-form-description" prop="description">
        <el-input
          v-model="form.description"
          type="textarea"
          :rows="2"
          placeholder="可选,描述计划用途"
          maxlength="200"
          show-word-limit
        />
      </el-form-item>

      <el-form-item label="巡检模板 *" :error="errors.templateType ?? ''" prop="templateType">
        <el-radio-group
          v-model="form.templateType"
          data-testid="plan-form-template-type"
        >
          <el-radio-button
            v-for="t in TEMPLATE_TYPES"
            :key="t.value"
            :value="t.value"
          >
            {{ t.label }}
          </el-radio-button>
        </el-radio-group>
      </el-form-item>

      <el-divider content-position="left">模板参数</el-divider>
      <TemplateFields
        :fields="currentFields"
        v-model:template-params="form.templateParams"
        v-model:thresholds="form.thresholds"
        :errors="errors"
      />
      <div
        v-for="field in currentFields"
        :key="`err-${field.name}`"
        :data-testid="`plan-form-error-${field.name}`"
        class="plan-form-error-slot"
      >
        {{ errors[field.name] ? `${field.label} ${errors[field.name]}` : '' }}
      </div>

      <el-divider content-position="left">调度配置</el-divider>

      <el-form-item label="调度类型 *" :error="errors.scheduleType ?? ''" prop="scheduleType">
        <el-radio-group v-model="form.scheduleType" data-testid="plan-form-schedule-type">
          <el-radio-button
            v-for="s in SCHEDULE_TYPES"
            :key="s.value"
            :value="s.value"
          >
            {{ s.label }}
          </el-radio-button>
        </el-radio-group>
      </el-form-item>

      <el-form-item label="执行时间 *" :error="errors.localTime ?? ''" prop="localTime">
        <el-time-picker
          v-model="form.localTime"
          data-testid="plan-form-local-time"
          format="HH:mm:ss"
          value-format="HH:mm:ss"
          placeholder="选择执行时间"
          style="width: 100%"
        />
      </el-form-item>

      <el-form-item
        v-if="form.scheduleType === 'WEEKLY'"
        label="星期 *"
        :error="errors.dayOfWeek ?? ''"
        data-testid="plan-form-day-of-week"
        prop="dayOfWeek"
      >
        <el-select v-model="form.dayOfWeek" placeholder="选择星期" style="width: 100%">
          <el-option v-for="d in WEEKDAYS" :key="d.value" :value="d.value" :label="d.label" />
        </el-select>
      </el-form-item>
      <div
        v-if="errors.dayOfWeek"
        :data-testid="`plan-form-error-dayOfWeek`"
        class="plan-form-error-slot"
      >
        星期 {{ errors.dayOfWeek }}
      </div>

      <el-form-item
        v-if="form.scheduleType === 'MONTHLY'"
        label="日期 *"
        :error="errors.dayOfMonth ?? ''"
        data-testid="plan-form-day-of-month"
        prop="dayOfMonth"
      >
        <el-input-number
          v-model="form.dayOfMonth"
          :min="1"
          :max="31"
          controls-position="right"
        />
      </el-form-item>

      <el-form-item label="时区 *" :error="errors.timezone ?? ''" data-testid="plan-form-timezone" prop="timezone">
        <el-input v-model="form.timezone" placeholder="如 Asia/Shanghai" />
      </el-form-item>

      <el-form-item label="通知策略 *" :error="errors.notificationPolicy ?? ''" data-testid="plan-form-notification-policy" prop="notificationPolicy">
        <el-radio-group v-model="form.notificationPolicy">
          <el-radio
            v-for="p in POLICIES"
            :key="p.value"
            :value="p.value"
            :label="p.value"
          >
            {{ p.label }}
          </el-radio>
        </el-radio-group>
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="close">取消</el-button>
      <el-button
        type="primary"
        :loading="submitting"
        data-testid="plan-form-submit"
        @click="validateAndSubmit"
      >
        {{ isEdit ? '保存修改' : '创建计划' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.plan-form-error-slot {
  display: none;
}
</style>
