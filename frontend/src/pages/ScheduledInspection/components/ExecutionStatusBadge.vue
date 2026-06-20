<script setup lang="ts">
import { computed } from 'vue';
import type { InspectionExecutionStatus } from '@/types/inspection';

const props = defineProps<{ status: InspectionExecutionStatus }>();

// Element Plus el-tag type 映射:
//   RUNNING          → primary(蓝,执行中)
//   SUCCESS          → success(绿,成功)
//   PARTIAL_SUCCESS  → warning(黄,部分成功)
//   FAILED           → danger(红,失败)
//   SKIPPED          → info(灰,跳过)
const tagType = computed(() => {
  switch (props.status) {
    case 'RUNNING':
      return 'primary';
    case 'SUCCESS':
      return 'success';
    case 'PARTIAL_SUCCESS':
      return 'warning';
    case 'FAILED':
      return 'danger';
    case 'SKIPPED':
      return 'info';
    default:
      return 'info';
  }
});

const label = computed(() => {
  switch (props.status) {
    case 'RUNNING':
      return '执行中';
    case 'SUCCESS':
      return '成功';
    case 'PARTIAL_SUCCESS':
      return '部分成功';
    case 'FAILED':
      return '失败';
    case 'SKIPPED':
      return '已跳过';
    default:
      return props.status;
  }
});
</script>

<template>
  <el-tag :type="tagType" size="small" effect="light" round>
    {{ label }}
  </el-tag>
</template>
