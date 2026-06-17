// IntentType 兼容映射（前后端枚举不一致 → 统一到后端命名）。
// 不修改任何现有枚举值；只做兼容层。

const LEGACY_TO_CANONICAL: Readonly<Record<string, string>> = {
  HEALTH_CHECK: 'SYSTEM_CHECK',
  PROCESS_INQUIRY: 'PROCESS_QUERY',
  NETWORK_INQUIRY: 'NETWORK_QUERY',
  LOG_INQUIRY: 'LOG_QUERY',
  SERVICE_OPERATION: 'SERVICE_DIAGNOSIS',
};

export function normalizeIntentType(raw: string | undefined): string {
  if (!raw) return 'UNKNOWN';
  return LEGACY_TO_CANONICAL[raw] ?? raw;
}

const RCA_TITLES: Readonly<Record<string, string>> = {
  DISK_DIAGNOSIS: '根因分析链',
  SYSTEM_CHECK: '健康评估链',
  SERVICE_DIAGNOSIS: '服务诊断链',
  COMMAND_EXECUTION: '安全决策链',
};

export function rcaTitleFor(rawIntent: string | undefined): string | null {
  const canonical = normalizeIntentType(rawIntent);
  return RCA_TITLES[canonical] ?? null;
}