// Tool Center types — mirror the backend com.kylinops.tool.ToolDefinitionVO.
//
// Hard rules:
//   * The frontend NEVER recomputes safety verdicts. Whatever the backend
//     returns (risk level, permission type, status) is rendered verbatim.
//   * callCount / successRate / lastCalledAt are populated server-side via a
//     single grouped aggregate (findStatsByToolNameIn). The frontend does not
//     fetch call history per tool.
//   * successRate === null means "no terminal calls yet" — the UI must show
//     "—" instead of "0%" or "100%".
//   * inputSchema / outputSchema are backend-declared JSON strings. The UI
//     renders them via text interpolation only — never v-html — because
//     the content is not sanitized for HTML.

/** Mirrors com.kylinops.common.enums.RiskLevel — the backend returns L0..L4. */
export type ToolRiskLevel = 'L0' | 'L1' | 'L2' | 'L3' | 'L4';

/** Mirrors com.kylinops.common.enums.PermissionType. */
export type ToolPermissionType = 'READ' | 'WRITE' | 'EXECUTE' | 'ADMIN';

/** Mirrors com.kylinops.common.enums.ToolStatus. */
export type ToolStatus = 'ENABLED' | 'DISABLED';

/**
 * Mirrors com.kylinops.tool.ToolDefinitionVO.
 * <p>
 * All fields are emitted by the backend. Numeric `callCount` is always
 * non-null; `successRate` and `lastCalledAt` are null when the tool has
 * no terminal calls.
 * </p>
 */
export interface ToolDefinition {
  /** 工具名称（唯一标识） */
  toolName: string;
  /** 工具描述 */
  description: string;
  /** 输入 JSON Schema 字符串 */
  inputSchema: string;
  /** 输出 JSON Schema 字符串 */
  outputSchema: string;
  /** 风险等级 */
  riskLevel: ToolRiskLevel;
  /** 权限类型 */
  permissionType: ToolPermissionType;
  /** 工具启用状态 */
  toolStatus: ToolStatus;
  /** 超时时间（毫秒） */
  timeoutMs: number;
  /** 是否需要审计 */
  auditRequired: boolean;
  // ----- Task 11 调用统计 -----
  /** 调用次数（long；零调用时为 0） */
  callCount: number;
  /** 成功率（0.0 - 1.0）；无 terminal calls 时为 null */
  successRate: number | null;
  /** 最近一次调用时间（ISO-8601）；无记录时为 null */
  lastCalledAt: string | null;
}