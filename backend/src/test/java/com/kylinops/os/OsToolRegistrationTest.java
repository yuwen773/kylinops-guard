package com.kylinops.os;

import com.kylinops.common.enums.PermissionType;
import com.kylinops.common.enums.RiskLevel;
import com.kylinops.tool.OpsTool;
import com.kylinops.tool.ToolDefinition;
import com.kylinops.tool.ToolInput;
import com.kylinops.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OS 工具注册与基础功能测试
 * <p>
 * 验证 8 个 OS 工具是否已全部注册到 ToolRegistry，
 * 以及各工具的 ToolDefinition 元数据是否完整。
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Task 05 — OS 工具注册与定义完整性")
class OsToolRegistrationTest {

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private List<OpsTool> allTools;

    private static final List<String> EXPECTED_OS_TOOLS = List.of(
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

    @Test
    @DisplayName("ToolRegistry 注册了 ≥ 12 个工具（含 Mock + FailingMock + 10 OS 工具）")
    void allToolsRegistered() {
        assertThat(toolRegistry.count()).isGreaterThanOrEqualTo(12);
    }

    @Test
    @DisplayName("所有 8 个 OS 工具均已在 ToolRegistry 中注册")
    void allOsToolsPresent() {
        List<String> registeredNames = toolRegistry.getToolNames();
        for (String expected : EXPECTED_OS_TOOLS) {
            assertThat(registeredNames)
                    .as("工具 %s 应已注册", expected)
                    .contains(expected);
        }
    }

    @Test
    @DisplayName("每个 OS 工具的 ToolDefinition 字段完整")
    void allOsToolsHaveCompleteDefinition() {
        for (String toolName : EXPECTED_OS_TOOLS) {
            OpsTool tool = toolRegistry.getTool(toolName);
            ToolDefinition def = tool.definition();

            assertThat(def.getToolName()).as("%s 的 toolName").isEqualTo(toolName);
            assertThat(def.getDescription()).as("%s 的 description").isNotBlank();
            assertThat(def.getRiskLevel()).as("%s 应为 L0").isEqualTo(RiskLevel.L0);
            assertThat(def.getPermissionType()).as("%s 应为 READ").isEqualTo(PermissionType.READ);
            assertThat(def.getToolStatus()).as("%s 应为 ENABLED").isNotNull();
            assertThat(def.getTimeoutMs()).as("%s 的 timeoutMs > 0").isPositive();
            assertThat(def.getInputSchema()).as("%s 的 inputSchema").isNotBlank();
            assertThat(def.getOutputSchema()).as("%s 的 outputSchema").isNotBlank();
        }
    }

    @Test
    @DisplayName("disk_usage_tool 的 ToolDefinition 风险等级为 L0")
    void diskUsageRiskLevel() {
        OpsTool tool = toolRegistry.getTool("disk_usage_tool");
        assertThat(tool.definition().getRiskLevel()).isEqualTo(RiskLevel.L0);
    }

    @Test
    @DisplayName("large_file_scan_tool 的 timeoutMs 为 5000")
    void largeFileScanTimeout() {
        OpsTool tool = toolRegistry.getTool("large_file_scan_tool");
        assertThat(tool.definition().getTimeoutMs()).isEqualTo(5000L);
    }

    @Test
    @DisplayName("没有 OS 工具处于 DISABLED 状态")
    void noOsToolIsDisabled() {
        for (String toolName : EXPECTED_OS_TOOLS) {
            OpsTool tool = toolRegistry.getTool(toolName);
            assertThat(tool.definition().getToolStatus())
                    .as("%s 不应为 DISABLED", toolName)
                    .isNotEqualTo(com.kylinops.common.enums.ToolStatus.DISABLED);
        }
    }
}
