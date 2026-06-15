package com.kylinops.agent.intelligence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LlmToolContextPolicyRegistry 单元测试 (P3-T3).
 *
 * <p>覆盖以下契约：</p>
 * <ul>
 *   <li>10 个生产工具都有 policy</li>
 *   <li>getPolicy(unknown_tool) → null（fail-closed 由调用方处理）</li>
 *   <li>注册表覆盖所有 OpsTool bean（启动时校验，缺则 IllegalStateException）</li>
 *   <li>LargeFileScanContextPolicy / JournalLogContextPolicy 标记 sensitive</li>
 *   <li>所有 policy 的 sanitize 方法在非空输入下返回非空字符串</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("LlmToolContextPolicyRegistry — 工具上下文策略注册（fail-closed）")
class LlmToolContextPolicyRegistryTest {

    @Autowired
    private LlmToolContextPolicyRegistry registry;

    private static final List<String> EXPECTED_PRODUCTION_TOOLS = List.of(
            "system_info_tool",
            "cpu_status_tool",
            "memory_status_tool",
            "disk_usage_tool",
            "large_file_scan_tool",
            "process_list_tool",
            "process_detail_tool",
            "network_port_tool",
            "service_status_tool",
            "journal_log_tool"
    );

    // ==================== 注册表 ====================

    @Test
    @DisplayName("注册表包含 10 个生产工具的 policy")
    void registryContainsAllProductionTools() {
        List<String> registered = registry.getRegisteredToolNames();
        for (String expected : EXPECTED_PRODUCTION_TOOLS) {
            assertThat(registered)
                    .as("工具 %s 应有 policy", expected)
                    .contains(expected);
        }
        // 注册表至少包含这 10 个；可能还有 mock 工具，>=10 即可
        assertThat(registered).hasSizeGreaterThanOrEqualTo(10);
    }

    @Test
    @DisplayName("getPolicy(unknown_tool) → null（调用方判 fail-closed）")
    void getPolicy_unknownTool_returnsNull() {
        LlmToolContextPolicy policy = registry.getPolicy("definitely_not_a_real_tool_xyz");
        assertThat(policy).isNull();
    }

    @Test
    @DisplayName("getPolicy(known_tool) → 返回非 null policy")
    void getPolicy_knownTool_returnsPolicy() {
        LlmToolContextPolicy policy = registry.getPolicy("system_info_tool");
        assertThat(policy).isNotNull();
        assertThat(policy.toolName()).isEqualTo("system_info_tool");
    }

    @Test
    @DisplayName("policy 的 isSensitive 标记：large_file_scan + journal_log = true；其他 = false")
    void sensitiveMarkersAreCorrect() {
        assertThat(registry.getPolicy("large_file_scan_tool").isSensitive()).isTrue();
        assertThat(registry.getPolicy("journal_log_tool").isSensitive()).isTrue();

        // 其他工具应为 false（无敏感内容泄漏）
        assertThat(registry.getPolicy("system_info_tool").isSensitive()).isFalse();
        assertThat(registry.getPolicy("cpu_status_tool").isSensitive()).isFalse();
        assertThat(registry.getPolicy("memory_status_tool").isSensitive()).isFalse();
        assertThat(registry.getPolicy("disk_usage_tool").isSensitive()).isFalse();
        assertThat(registry.getPolicy("process_list_tool").isSensitive()).isFalse();
        assertThat(registry.getPolicy("process_detail_tool").isSensitive()).isFalse();
        assertThat(registry.getPolicy("network_port_tool").isSensitive()).isFalse();
        assertThat(registry.getPolicy("service_status_tool").isSensitive()).isFalse();
    }

    @Test
    @DisplayName("所有 policy 的 sanitize 对非空 ToolResult 返回非 null 字符串")
    void allPoliciesSanitizeReturnsNonNull() {
        for (String toolName : EXPECTED_PRODUCTION_TOOLS) {
            LlmToolContextPolicy policy = registry.getPolicy(toolName);
            assertThat(policy)
                    .as("工具 %s 应有 policy", toolName)
                    .isNotNull();

            // 提供最小有效 Map data（policy 默认期望 Map 类型）
            java.util.Map<String, Object> minimalData = new java.util.HashMap<>();
            minimalData.put("placeholder", "value");
            com.kylinops.tool.ToolResult result =
                    com.kylinops.tool.ToolResult.success(toolName, minimalData, "ok summary", 10);
            String sanitized = policy.sanitize(result, 4096);
            assertThat(sanitized)
                    .as("工具 %s sanitize 应返回非 null", toolName)
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("getRegisteredToolNames 返回不可修改副本（immutable）")
    void getRegisteredToolNamesIsImmutable() {
        List<String> names = registry.getRegisteredToolNames();
        assertThatThrownBy(() -> names.add("mutation_attempt"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}