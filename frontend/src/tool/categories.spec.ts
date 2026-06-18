import { describe, expect, it } from 'vitest';
import {
  ALL_CATEGORIES_FILTER,
  CATEGORY_META,
  OTHER_CATEGORY,
  TOOL_CATEGORIES,
  filterToolsByCategory,
  getCategoryFor,
} from './categories';
import type { ToolDefinition } from '@/types/tool';

describe('getCategoryFor', () => {
  it('returns known category for system_info_tool', () => {
    expect(getCategoryFor('system_info_tool')).toBe('系统信息');
  });

  it('returns known category for cpu_status_tool', () => {
    expect(getCategoryFor('cpu_status_tool')).toBe('资源监控');
  });

  it('returns known category for journal_log_tool', () => {
    expect(getCategoryFor('journal_log_tool')).toBe('安全治理');
  });

  it('returns OTHER_CATEGORY for unknown tool', () => {
    expect(getCategoryFor('unknown_tool')).toBe(OTHER_CATEGORY);
  });

  it('returns OTHER_CATEGORY for empty string', () => {
    expect(getCategoryFor('')).toBe(OTHER_CATEGORY);
  });

  it('never returns undefined', () => {
    const names = ['', 'nonexistent', 'something_else', 'new_tool_v2'];
    for (const n of names) {
      expect(getCategoryFor(n)).toBeDefined();
    }
  });
});

describe('TOOL_CATEGORIES', () => {
  it('contains all 10 P0 tools', () => {
    const p0 = [
      'system_info_tool',
      'cpu_status_tool',
      'memory_status_tool',
      'disk_usage_tool',
      'large_file_scan_tool',
      'process_list_tool',
      'process_detail_tool',
      'network_port_tool',
      'service_status_tool',
      'journal_log_tool',
    ];
    for (const name of p0) {
      expect(TOOL_CATEGORIES[name]).toBeDefined();
    }
  });

  it('has no empty values', () => {
    for (const [name, cat] of Object.entries(TOOL_CATEGORIES)) {
      expect(cat).toBeTruthy();
      expect(typeof cat).toBe('string');
      expect(cat.length).toBeGreaterThan(0);
      expect(name.length).toBeGreaterThan(0);
    }
  });
});

describe('CATEGORY_META', () => {
  it('includes OTHER_CATEGORY', () => {
    const meta = CATEGORY_META.find((m) => m.label === OTHER_CATEGORY);
    expect(meta).toBeDefined();
    expect(meta!.icon).toBeTruthy();
  });

  it('has unique order values', () => {
    const orders = CATEGORY_META.map((m) => m.order);
    expect(new Set(orders).size).toBe(orders.length);
  });

  it('has unique labels', () => {
    const labels = CATEGORY_META.map((m) => m.label);
    expect(new Set(labels).size).toBe(labels.length);
  });
});

describe('ALL_CATEGORIES_FILTER', () => {
  it('starts with 全部', () => {
    expect(ALL_CATEGORIES_FILTER[0]).toBe('全部');
  });

  it('includes OTHER_CATEGORY', () => {
    expect(ALL_CATEGORIES_FILTER).toContain(OTHER_CATEGORY);
  });
});

describe('filterToolsByCategory', () => {
  const makeTool = (toolName: string): ToolDefinition =>
    ({ toolName } as ToolDefinition);

  const tools: ToolDefinition[] = [
    makeTool('system_info_tool'),
    makeTool('cpu_status_tool'),
    makeTool('memory_status_tool'),
    makeTool('unknown_new_tool'),
  ];

  it('returns all when category is 全部', () => {
    const result = filterToolsByCategory(tools, '全部');
    expect(result).toHaveLength(4);
  });

  it('filters by 系统信息', () => {
    const result = filterToolsByCategory(tools, '系统信息');
    expect(result).toHaveLength(1);
    expect(result[0].toolName).toBe('system_info_tool');
  });

  it('filters by 资源监控', () => {
    const result = filterToolsByCategory(tools, '资源监控');
    expect(result).toHaveLength(2);
    expect(result.map((t) => t.toolName).sort()).toEqual([
      'cpu_status_tool',
      'memory_status_tool',
    ]);
  });

  it('unknown tools fall back to 其他工具', () => {
    const result = filterToolsByCategory(tools, '其他工具');
    expect(result).toHaveLength(1);
    expect(result[0].toolName).toBe('unknown_new_tool');
  });

  it('returns empty for non-matching category', () => {
    const result = filterToolsByCategory(tools, '安全治理');
    expect(result).toHaveLength(0);
  });
});
