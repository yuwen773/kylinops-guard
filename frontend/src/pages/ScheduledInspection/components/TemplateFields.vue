<script setup lang="ts">
import type { InspectionTemplateField } from '@/types/inspection';

/**
 * TemplateFields — 根据后端 InspectionTemplateView.fields 动态渲染表单。
 *
 * 设计(来自 Inspection §11.1):
 *   * 字段定义是单一事实来源(后端 fields 数组),前端不硬编码 per-template 分支。
 *   * string 类型字段 → templateParams(必填字段标 *;空串触发校验错误)。
 *   * number 类型字段 → thresholds(应用 constraints.min/max)。
 *
 * 双向绑定:
 *   * `templateParams` / `thresholds` 是父组件传入的对象;v-model 直接修改 key。
 *   * 父组件负责在 templateType 切换时重置两个 map。
 */
const props = defineProps<{
  fields: InspectionTemplateField[];
  templateParams: Record<string, unknown>;
  thresholds: Record<string, unknown>;
  errors: Record<string, string>;
}>();

const emit = defineEmits<{
  (e: 'update:templateParams', value: Record<string, unknown>): void;
  (e: 'update:thresholds', value: Record<string, unknown>): void;
}>();

function isStringField(field: InspectionTemplateField): boolean {
  return field.type === 'string';
}

function getString(name: string): string {
  const value = props.templateParams[name];
  return typeof value === 'string' ? value : '';
}

function setString(name: string, value: string): void {
  emit('update:templateParams', { ...props.templateParams, [name]: value });
}

function getNumber(name: string): number | undefined {
  const value = props.thresholds[name];
  return typeof value === 'number' ? value : undefined;
}

function setNumber(name: string, value: number | undefined): void {
  const next = { ...props.thresholds };
  if (value === undefined || Number.isNaN(value)) {
    delete next[name];
  } else {
    next[name] = value;
  }
  emit('update:thresholds', next);
}

function minFor(field: InspectionTemplateField): number | undefined {
  const min = (field.constraints as Record<string, unknown> | undefined)?.min;
  return typeof min === 'number' ? min : undefined;
}

function maxFor(field: InspectionTemplateField): number | undefined {
  const max = (field.constraints as Record<string, unknown> | undefined)?.max;
  return typeof max === 'number' ? max : undefined;
}
</script>

<template>
  <div class="template-fields" data-testid="template-fields">
    <template v-for="field in fields" :key="field.name">
      <el-form-item
        v-if="isStringField(field)"
        :data-testid="`plan-form-field-${field.name}`"
        :prop="`templateParams.${field.name}`"
        :label="field.label + (field.required ? ' *' : '')"
        :error="errors[field.name] ?? ''"
      >
        <el-input
          :model-value="getString(field.name)"
          :placeholder="field.required ? `请输入 ${field.label}` : `可选`"
          clearable
          @update:model-value="(val: string) => setString(field.name, val)"
        />
      </el-form-item>
      <el-form-item
        v-else
        :data-testid="`plan-form-field-${field.name}`"
        :prop="`thresholds.${field.name}`"
        :label="field.label"
        :error="errors[field.name] ?? ''"
      >
        <el-input-number
          :model-value="getNumber(field.name)"
          :min="minFor(field)"
          :max="maxFor(field)"
          :placeholder="typeof field.defaultValue === 'string' ? `默认 ${field.defaultValue}` : undefined"
          :data-testid="`plan-form-field-${field.name}-input`"
          controls-position="right"
          @update:model-value="(val: number | undefined) => setNumber(field.name, val)"
        />
      </el-form-item>
    </template>
  </div>
</template>

<style scoped>
.template-fields {
  display: flex;
  flex-direction: column;
  gap: var(--kg-space-3, 12px);
}
</style>
