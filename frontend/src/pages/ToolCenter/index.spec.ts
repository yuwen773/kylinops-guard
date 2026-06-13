import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mount, flushPromises, enableAutoUnmount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import ToolCenterPage from './index.vue';
import * as toolsApi from '@/api/tools';
import { ApiError } from '@/api/client';
import type { ToolDefinition } from '@/types/tool';
import { resolve } from 'node:path';

enableAutoUnmount(afterEach);

/**
 * Tool Center page contract (Task 11):
 *   - On mount: calls getTools() exactly once.
 *   - Renders ALL tools in a table (≥ 8 rows). Each row shows:
 *       * tool name
 *       * description
 *       * risk level tag (L0/L1/L2/...)
 *       * permission type tag (READ/WRITE/EXECUTE/ADMIN)
 *       * status tag (ENABLED/DISABLED)
 *       * callCount, successRate, lastCalledAt
 *   - successRate === null renders as "—" (never 0%, never 100%).
 *   - lastCalledAt === null renders as "从未调用".
 *   - Each row has an expandable panel showing inputSchema + outputSchema.
 *   - v-html is NEVER used to render schema strings. The rendered tree
 *     contains text interpolation / el-descriptions — not v-html nodes.
 *   - ToolCount >= 8 is the contract (task spec).
 *   - Error state: API failure shows Chinese error message.
 */

function buildTool(overrides: Partial<ToolDefinition> = {}): ToolDefinition {
  return {
    toolName: 'system_info_tool',
    description: '查询系统基本信息',
    inputSchema: '{"type":"object","properties":{}}',
    outputSchema: '{"type":"object"}',
    riskLevel: 'L0',
    permissionType: 'READ',
    toolStatus: 'ENABLED',
    timeoutMs: 3000,
    auditRequired: false,
    callCount: 0,
    successRate: null,
    lastCalledAt: null,
    ...overrides,
  };
}

function buildTenTools(): ToolDefinition[] {
  return [
    buildTool({ toolName: 'system_info_tool', description: '系统信息', callCount: 5, successRate: 1.0, lastCalledAt: '2026-06-12T10:00:00Z' }),
    buildTool({ toolName: 'cpu_status_tool', description: 'CPU 使用率', riskLevel: 'L0', callCount: 3, successRate: 1.0, lastCalledAt: '2026-06-12T09:30:00Z' }),
    buildTool({ toolName: 'memory_status_tool', description: '内存使用率', callCount: 0, successRate: null, lastCalledAt: null }),
    buildTool({ toolName: 'disk_usage_tool', description: '磁盘使用率', callCount: 6, successRate: 4 / 6, lastCalledAt: '2026-06-12T08:00:00Z' }),
    buildTool({ toolName: 'large_file_scan_tool', description: '大文件扫描', callCount: 1, successRate: 1.0, lastCalledAt: '2026-06-11T20:00:00Z' }),
    buildTool({ toolName: 'process_list_tool', description: '进程列表', callCount: 0, successRate: null, lastCalledAt: null }),
    buildTool({ toolName: 'process_detail_tool', description: '进程详情', callCount: 0, successRate: null, lastCalledAt: null }),
    buildTool({ toolName: 'network_port_tool', description: '网络端口', callCount: 2, successRate: 1.0, lastCalledAt: '2026-06-12T07:00:00Z' }),
    buildTool({ toolName: 'service_status_tool', description: '服务状态', callCount: 0, successRate: null, lastCalledAt: null }),
    buildTool({ toolName: 'journal_log_tool', description: '系统日志', callCount: 0, successRate: null, lastCalledAt: null }),
  ];
}

async function mountPage(options: {
  tools?: ToolDefinition[];
  reject?: boolean;
} = {}) {
  const spy = vi.spyOn(toolsApi, 'getTools');
  if (options.reject) {
    spy.mockRejectedValue(
      new ApiError({ code: 500, message: '工具目录加载失败', data: null }),
    );
  } else {
    spy.mockResolvedValue(options.tools ?? buildTenTools());
  }
  const wrapper = mount(ToolCenterPage, {
    global: { plugins: [ElementPlus] },
    attachTo: document.body,
  });
  await flushPromises();
  return { wrapper, spy };
}

describe('Tool Center — initial load', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('calls getTools exactly once on mount', async () => {
    const { spy } = await mountPage();
    expect(spy).toHaveBeenCalledTimes(1);
  });

  it('renders at least 8 tool rows from the API payload', async () => {
    const { wrapper } = await mountPage();
    const rows = wrapper.findAll('[data-testid^="tool-row-"]');
    expect(rows.length).toBeGreaterThanOrEqual(8);
  });

  it('shows Chinese error message when getTools rejects', async () => {
    const { wrapper } = await mountPage({ reject: true });
    const err = wrapper.find('[data-testid="tool-error"]');
    expect(err.exists()).toBe(true);
    expect(wrapper.text()).toContain('工具目录加载失败');
  });

  it('shows empty state when the API returns an empty list', async () => {
    const { wrapper } = await mountPage({ tools: [] });
    expect(wrapper.find('[data-testid="tool-empty"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('暂无注册工具');
  });
});

describe('Tool Center — risk / permission / status tags', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('renders the risk level tag for each tool', async () => {
    const { wrapper } = await mountPage();
    const tag = wrapper.find('[data-testid="tool-risk-level-cpu_status_tool"]');
    expect(tag.exists()).toBe(true);
    expect(tag.text()).toContain('L0');
  });

  it('renders the permission type tag for each tool', async () => {
    const { wrapper } = await mountPage();
    const tag = wrapper.find('[data-testid="tool-permission-cpu_status_tool"]');
    expect(tag.exists()).toBe(true);
    expect(tag.text()).toContain('READ');
  });

  it('renders the status tag (ENABLED / DISABLED) for each tool', async () => {
    const { wrapper } = await mountPage({
      tools: [
        buildTool({ toolName: 't_on', toolStatus: 'ENABLED' }),
        buildTool({ toolName: 't_off', toolStatus: 'DISABLED' }),
        ...buildTenTools().slice(2),
      ],
    });
    expect(wrapper.find('[data-testid="tool-status-t_on"]').text()).toContain('启用');
    expect(wrapper.find('[data-testid="tool-status-t_off"]').text()).toContain('停用');
  });
});

describe('Tool Center — call statistics', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('renders callCount for every tool', async () => {
    const { wrapper } = await mountPage();
    expect(wrapper.find('[data-testid="tool-call-count-cpu_status_tool"]').text()).toContain('3');
    expect(wrapper.find('[data-testid="tool-call-count-disk_usage_tool"]').text()).toContain('6');
  });

  it('renders successRate as percentage when not null', async () => {
    const { wrapper } = await mountPage();
    const rate = wrapper.find('[data-testid="tool-success-rate-cpu_status_tool"]');
    expect(rate.text()).toMatch(/100(?:\.0+)?\s*%/);
  });

  it('renders successRate=null as "—" (never 0%, never 100%)', async () => {
    const { wrapper } = await mountPage();
    const rate = wrapper.find('[data-testid="tool-success-rate-memory_status_tool"]');
    expect(rate.text()).toContain('—');
    expect(rate.text()).not.toMatch(/0\s*%/);
    expect(rate.text()).not.toMatch(/100\s*%/);
  });

  it('renders partial-success successRate (e.g. 66.67% for 4/6)', async () => {
    const { wrapper } = await mountPage();
    const rate = wrapper.find('[data-testid="tool-success-rate-disk_usage_tool"]');
    expect(rate.text()).toMatch(/66\.7\s*%|66\.67\s*%/);
  });

  it('renders lastCalledAt as "从未调用" when null', async () => {
    const { wrapper } = await mountPage();
    expect(wrapper.find('[data-testid="tool-last-called-memory_status_tool"]').text())
      .toContain('从未调用');
  });

  it('renders lastCalledAt as formatted timestamp when non-null', async () => {
    const { wrapper } = await mountPage();
    expect(wrapper.find('[data-testid="tool-last-called-cpu_status_tool"]').text())
      .toMatch(/2026/);
  });
});

describe('Tool Center — expandable schema panel', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('renders the inputSchema in the expandable panel', async () => {
    const { wrapper } = await mountPage();
    await wrapper.find('.el-table__expand-icon').trigger('click');
    await flushPromises();
    const panel = wrapper.find('[data-testid="tool-schema-system_info_tool"]');
    expect(panel.exists()).toBe(true);
    expect(panel.text()).toContain('object');
  });

  it('renders the outputSchema in the expandable panel', async () => {
    const { wrapper } = await mountPage();
    await wrapper.find('.el-table__expand-icon').trigger('click');
    await flushPromises();
    const panel = wrapper.find('[data-testid="tool-schema-system_info_tool"]');
    expect(panel.text()).toContain('object');
  });

  it('does NOT use v-html to render schema strings (defense in depth)', async () => {
    // Build a malicious-looking schema string. Even if the backend ever
    // emits HTML in inputSchema, the page MUST NOT inject it via v-html.
    const malicious = '<img src=x onerror=alert(1)>';
    const { wrapper } = await mountPage({
      tools: [
        buildTool({
          toolName: 'malicious_tool',
          inputSchema: malicious,
          outputSchema: malicious,
        }),
        ...buildTenTools().slice(1),
      ],
    });
    await wrapper.find('.el-table__expand-icon').trigger('click');
    await flushPromises();
    // The raw "<img>" substring must appear as text, not as a DOM <img> node.
    // Find an <img> element matching the malicious payload — there must be
    // none. We accept that Element Plus might render other imgs (icons) but
    // the malicious src must not be present.
    const allImgs = wrapper.findAll('img');
    for (const img of allImgs) {
      expect(img.attributes('src')).not.toBe('x');
      const onerror = img.attributes('onerror');
      expect(onerror ?? '').not.toMatch(/alert/i);
    }
    // Sanity: the malicious substring is present in the DOM as text.
    expect(wrapper.find('[data-testid="tool-input-schema-malicious_tool"]').text())
      .toContain('<img');
  });
});

describe('Tool Center — no v-html anywhere', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('the page source does not contain v-html directives', async () => {
    // Source-level guard: index.vue must not contain v-html. We re-read
    // the file because v-html would not be detectable via runtime DOM in
    // jsdom (Element Plus renders inputs differently).
    // vitest's environment is jsdom — the page's compiled render function
    // is what we'd inspect; instead we use a static-text grep on the file
    // (vite resolves the SFC at runtime).
    // This is the contract: the page source MUST NOT contain "v-html".
    // If it does, this test fails and forces a refactor.
    const source = await import('node:fs').then((m) =>
      m.promises.readFile(resolve(process.cwd(), 'src/pages/ToolCenter/index.vue'), 'utf8'),
    );
    expect(source).not.toMatch(/\bv-html\s*=/);
  });
});
