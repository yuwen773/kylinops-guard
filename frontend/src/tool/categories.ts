// Tool category mapping — pure frontend convention, not backed by the API.
//
// When a new tool is registered but not yet listed in TOOL_CATEGORIES,
// `getCategoryFor` returns `OTHER_CATEGORY` (其他工具) instead of
// crashing, hiding the row, or returning an empty string.
//
// Add a new entry here whenever a new P0/P1 tool enters the registry.

export type ToolCategory =
  | '系统信息'
  | '资源监控'
  | '磁盘诊断'
  | '服务诊断'
  | '安全治理'
  | '其他工具';

export const OTHER_CATEGORY: ToolCategory = '其他工具';

/** Known tool → category mapping. Tools not listed here get OTHER_CATEGORY. */
export const TOOL_CATEGORIES: Record<string, ToolCategory> = {
  system_info_tool: '系统信息',
  cpu_status_tool: '资源监控',
  memory_status_tool: '资源监控',
  disk_usage_tool: '磁盘诊断',
  large_file_scan_tool: '磁盘诊断',
  service_status_tool: '服务诊断',
  network_port_tool: '服务诊断',
  process_list_tool: '服务诊断',
  process_detail_tool: '服务诊断',
  journal_log_tool: '安全治理',
};

export interface CategoryMeta {
  label: ToolCategory;
  icon: string;
  order: number;
}

export const CATEGORY_META: CategoryMeta[] = [
  { label: '系统信息', icon: 'Monitor', order: 1 },
  { label: '资源监控', icon: 'DataLine', order: 2 },
  { label: '磁盘诊断', icon: 'Files', order: 3 },
  { label: '服务诊断', icon: 'Tools', order: 4 },
  { label: '安全治理', icon: 'Lock', order: 5 },
  { label: '其他工具', icon: 'More', order: 6 },
];

export const ALL_CATEGORIES_FILTER: Array<ToolCategory | '全部'> = [
  '全部',
  '系统信息',
  '资源监控',
  '磁盘诊断',
  '服务诊断',
  '安全治理',
  '其他工具',
];

/** Resolve the category for a tool name. ALWAYS returns a value — unknown
 *  tools get `OTHER_CATEGORY`. Never throws, never returns undefined. */
export function getCategoryFor(toolName: string): ToolCategory {
  return TOOL_CATEGORIES[toolName] ?? OTHER_CATEGORY;
}

/** Filter a list of tools by the selected category. `'全部'` returns the
 *  whole list unchanged. Uses `getCategoryFor` so unknown tools show up
 *  under `其他工具`. */
export function filterToolsByCategory<T extends { toolName: string }>(
  tools: readonly T[],
  selectedCategory: ToolCategory | '全部',
): T[] {
  if (selectedCategory === '全部') {
    return [...tools];
  }
  return tools.filter((t) => getCategoryFor(t.toolName) === selectedCategory);
}
