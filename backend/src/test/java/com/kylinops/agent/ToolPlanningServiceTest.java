package com.kylinops.agent;

import com.kylinops.agent.ToolPlanningService.ExecutionMode;
import com.kylinops.agent.ToolPlanningService.ToolPlan;
import com.kylinops.agent.ToolPlanningService.ToolStep;
import com.kylinops.common.enums.IntentType;
import com.kylinops.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ToolPlanningService 集成测试
 * <p>
 * 验证各意图类型生成正确的工具调用计划。
 * 使用已注册的 ToolRegistry 校验工具是否存在。
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("ToolPlanningService — 工具规划")
class ToolPlanningServiceTest {

    @Autowired
    private ToolPlanningService planner;

    @Autowired
    private ToolRegistry toolRegistry;

    @Test
    @DisplayName("SYSTEM_CHECK → 6 个工具全部并行")
    void systemCheckPlan() {
        ToolPlan plan = planner.createPlan(IntentType.SYSTEM_CHECK, Map.of());

        assertThat(plan.getSteps()).isNotEmpty();
        assertThat(plan.isRequiresRiskCheck()).isFalse();

        List<String> toolNames = plan.getSteps().stream()
                .map(ToolStep::getToolName)
                .collect(Collectors.toList());

        // 必须包含的 6 个工具
        assertThat(toolNames)
                .contains("system_info_tool")
                .contains("cpu_status_tool")
                .contains("memory_status_tool")
                .contains("disk_usage_tool")
                .contains("network_port_tool")
                .contains("process_list_tool");

        // 所有步骤应为 PARALLEL 模式
        assertThat(plan.getSteps())
                .allMatch(s -> s.getMode() == ExecutionMode.PARALLEL);

        // 所有步骤 order 应为 0（同一执行批次）
        assertThat(plan.getSteps())
                .allMatch(s -> s.getOrder() == 0);
    }

    @Test
    @DisplayName("DISK_DIAGNOSIS → 2 个工具（disk_usage → large_file_scan 串行）")
    void diskDiagnosisPlan() {
        ToolPlan plan = planner.createPlan(IntentType.DISK_DIAGNOSIS, Map.of());

        assertThat(plan.getSteps()).hasSize(2);
        assertThat(plan.isRequiresRiskCheck()).isFalse();

        // 有序: disk_usage_tool (order=0) → large_file_scan_tool (order=1)
        assertThat(plan.getSteps().get(0).getToolName()).isEqualTo("disk_usage_tool");
        assertThat(plan.getSteps().get(0).getOrder()).isEqualTo(0);
        assertThat(plan.getSteps().get(1).getToolName()).isEqualTo("large_file_scan_tool");
        assertThat(plan.getSteps().get(1).getOrder()).isEqualTo(1);
    }

    @Test
    @DisplayName("SERVICE_DIAGNOSIS → 3 个工具（含并行和串行）")
    void serviceDiagnosisPlan() {
        ToolPlan plan = planner.createPlan(IntentType.SERVICE_DIAGNOSIS, Map.of("serviceName", "nginx"));

        assertThat(plan.getSteps()).hasSize(3);
        assertThat(plan.getAction()).isNull();
        assertThat(plan.isRequiresRiskCheck()).isFalse();

        List<String> toolNames = plan.getSteps().stream()
                .map(ToolStep::getToolName)
                .collect(Collectors.toList());

        assertThat(toolNames)
                .contains("service_status_tool")
                .contains("network_port_tool")
                .contains("journal_log_tool");
        assertThat(plan.getSteps())
                .filteredOn(step -> step.getOrder() == 0)
                .extracting(ToolStep::getToolName)
                .containsExactlyInAnyOrder("service_status_tool", "network_port_tool");
        assertThat(plan.getSteps())
                .filteredOn(step -> step.getOrder() == 1)
                .extracting(ToolStep::getToolName)
                .containsExactly("journal_log_tool");
    }

    @Test
    void serviceRestartPlan() {
        ToolPlan plan = planner.createPlan(IntentType.SERVICE_DIAGNOSIS,
                Map.of("serviceName", "nginx", "operation", "restart"));

        assertThat(plan.getSteps()).isEmpty();
        assertThat(plan.isRequiresRiskCheck()).isTrue();
        assertThat(plan.getAction()).isNotNull();
        assertThat(plan.getAction().getActionType()).isEqualTo("safe_service_restart");
        assertThat(plan.getAction().getTarget()).isEqualTo("nginx");
        assertThat(plan.getAction().getParams()).containsEntry("serviceName", "nginx");
    }

    @Test
    @DisplayName("PROCESS_QUERY（无 PID）→ process_list_tool")
    void processQueryWithoutPid() {
        ToolPlan plan = planner.createPlan(IntentType.PROCESS_QUERY, Map.of());

        assertThat(plan.getSteps()).hasSize(1);
        assertThat(plan.isRequiresRiskCheck()).isFalse();
        assertThat(plan.getSteps().get(0).getToolName()).isEqualTo("process_list_tool");
    }

    @Test
    @DisplayName("PROCESS_QUERY（有 PID）→ process_detail_tool")
    void processQueryWithPid() {
        ToolPlan plan = planner.createPlan(IntentType.PROCESS_QUERY, Map.of("pid", 1234));

        assertThat(plan.getSteps()).hasSize(1);
        assertThat(plan.getSteps().get(0).getToolName()).isEqualTo("process_detail_tool");
        assertThat(plan.getSteps().get(0).getParams())
                .containsEntry("pid", 1234);
    }

    @Test
    @DisplayName("NETWORK_QUERY → network_port_tool")
    void networkQueryPlan() {
        ToolPlan plan = planner.createPlan(IntentType.NETWORK_QUERY, Map.of());

        assertThat(plan.getSteps()).hasSize(1);
        assertThat(plan.isRequiresRiskCheck()).isFalse();
        assertThat(plan.getSteps().get(0).getToolName()).isEqualTo("network_port_tool");
    }

    @Test
    @DisplayName("LOG_QUERY → journal_log_tool")
    void logQueryPlan() {
        ToolPlan plan = planner.createPlan(IntentType.LOG_QUERY, Map.of());

        assertThat(plan.getSteps()).hasSize(1);
        assertThat(plan.getSteps().get(0).getToolName()).isEqualTo("journal_log_tool");
    }

    @Test
    @DisplayName("FILE_OPERATION → large_file_scan_tool, 需风险校验")
    void fileOperationPlan() {
        ToolPlan plan = planner.createPlan(IntentType.FILE_OPERATION, Map.of());

        assertThat(plan.getSteps()).hasSize(1);
        assertThat(plan.isRequiresRiskCheck()).isTrue();
        assertThat(plan.getSteps().get(0).getToolName()).isEqualTo("large_file_scan_tool");
    }

    @Test
    @DisplayName("COMMAND_EXECUTION → 空执行计划，仅评估原始内容")
    void commandExecutionPlan() {
        ToolPlan plan = planner.createPlan(IntentType.COMMAND_EXECUTION, Map.of());

        assertThat(plan.getSteps()).isEmpty();
        assertThat(plan.isRequiresRiskCheck()).isTrue();
        assertThat(plan.getAction()).isNull();
    }

    @Test
    @DisplayName("GENERAL_CHAT → 空计划")
    void generalChatPlan() {
        ToolPlan plan = planner.createPlan(IntentType.GENERAL_CHAT, Map.of());

        assertThat(plan.getSteps()).isEmpty();
        assertThat(plan.isRequiresRiskCheck()).isFalse();
    }

    @Test
    @DisplayName("UNKNOWN → 空计划")
    void unknownPlan() {
        ToolPlan plan = planner.createPlan(IntentType.UNKNOWN, Map.of());

        assertThat(plan.getSteps()).isEmpty();
        assertThat(plan.isRequiresRiskCheck()).isFalse();
    }

    @Test
    @DisplayName("不存在的工具名不会破坏计划生成")
    void missingToolGraceful() {
        // service_status_tool 可能未注册，但规划器不应因此崩溃
        ToolPlan plan = planner.createPlan(IntentType.SERVICE_DIAGNOSIS, Map.of("serviceName", "nginx"));
        assertThat(plan.getSteps()).isNotNull();
        assertThat(plan.getIntent()).isEqualTo(IntentType.SERVICE_DIAGNOSIS);
    }
}
