package com.kylinops.agent;

import com.kylinops.common.enums.IntentType;
import com.kylinops.tool.ToolRegistry;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工具规划器
 * <p>
 * 根据 {@link IntentType} 生成具体的工具调用计划 {@link ToolPlan}，
 * 包含工具名称列表、执行模式（串行/并行）、参数模板。
 * </p>
 *
 * <h3>意图 → 工具链映射</h3>
 * <pre>
 * SYSTEM_CHECK      → system_info + cpu + memory + disk + network + process_list (PARALLEL)
 * DISK_DIAGNOSIS    → disk_usage → large_file_scan (SEQUENTIAL)
 * SERVICE_DIAGNOSIS → service_status + network_port + journal_log (PARALLEL + SEQUENTIAL)
 * PROCESS_QUERY     → process_list / process_detail (SEQUENTIAL)
 * NETWORK_QUERY     → network_port (单一)
 * LOG_QUERY         → journal_log (单一)
 * FILE_OPERATION    → large_file_scan (单一, 需 RiskCheck)
 * COMMAND_EXECUTION → command_risk_check (单一, 需 RiskCheck)
 * GENERAL_CHAT      → 空计划
 * UNKNOWN           → 空计划
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolPlanningService {

    private final ToolRegistry toolRegistry;

    /**
     * 根据意图类型和参数生成工具调用计划。
     *
     * @param intent 意图类型
     * @param params 从用户输入中提取的参数（如 PID、服务名等）
     * @return 工具调用计划（永远不会返回 null）
     */
    public ToolPlan createPlan(IntentType intent, Map<String, Object> params) {
        List<ToolStep> steps = new ArrayList<>();
        ActionPlan action = null;
        boolean requiresRiskCheck = false;

        switch (intent) {
            case SYSTEM_CHECK:
                steps.addAll(createSystemCheckSteps());
                break;

            case DISK_DIAGNOSIS:
                steps.addAll(createDiskDiagnosisSteps());
                break;

            case SERVICE_DIAGNOSIS:
                if (params != null && "restart".equals(params.get("operation"))) {
                    String serviceName = (String) params.get("serviceName");
                    action = ActionPlan.builder()
                            .actionType("safe_service_restart")
                            .target(serviceName)
                            .params(serviceName != null
                                    ? Map.of("serviceName", serviceName)
                                    : Map.of())
                            .build();
                    requiresRiskCheck = true;
                } else {
                    steps.addAll(createServiceDiagnosisSteps(params));
                }
                break;

            case PROCESS_QUERY:
                steps.addAll(createProcessQuerySteps(params));
                break;

            case NETWORK_QUERY:
                steps.add(createStep("network_port_tool", params, ExecutionMode.PARALLEL, 0));
                break;

            case LOG_QUERY:
                steps.add(createStep("journal_log_tool", params, ExecutionMode.PARALLEL, 0));
                break;

            case FILE_OPERATION:
                steps.add(createStep("large_file_scan_tool", params, ExecutionMode.PARALLEL, 0));
                requiresRiskCheck = true;
                break;

            case COMMAND_EXECUTION:
                requiresRiskCheck = true;
                break;

            case GENERAL_CHAT:
            case UNKNOWN:
                // 空计划 — 无工具调用
                break;
        }

        ToolPlan plan = ToolPlan.builder()
                .intent(intent)
                .steps(steps)
                .action(action)
                .requiresRiskCheck(requiresRiskCheck)
                .build();

        log.debug("生成工具计划: intent={}, steps={}, requiresRiskCheck={}",
                intent, steps.size(), requiresRiskCheck);
        return plan;
    }

    /**
     * 系统健康检查工具链：6 个工具全部并行执行（同 order=0）。
     */
    private List<ToolStep> createSystemCheckSteps() {
        List<ToolStep> steps = new ArrayList<>();
        steps.add(createStep("system_info_tool", Map.of(), ExecutionMode.PARALLEL, 0));
        steps.add(createStep("cpu_status_tool", Map.of(), ExecutionMode.PARALLEL, 0));
        steps.add(createStep("memory_status_tool", Map.of(), ExecutionMode.PARALLEL, 0));
        steps.add(createStep("disk_usage_tool", Map.of(), ExecutionMode.PARALLEL, 0));
        steps.add(createStep("network_port_tool", Map.of(), ExecutionMode.PARALLEL, 0));
        steps.add(createStep("process_list_tool", Map.of(), ExecutionMode.PARALLEL, 0));
        return steps;
    }

    /**
     * 磁盘诊断：disk_usage 先执行，large_file_scan 随后。
     */
    private List<ToolStep> createDiskDiagnosisSteps() {
        List<ToolStep> steps = new ArrayList<>();
        steps.add(createStep("disk_usage_tool", Map.of(), ExecutionMode.SEQUENTIAL, 0));
        steps.add(createStep("large_file_scan_tool", Map.of(), ExecutionMode.SEQUENTIAL, 1));
        return steps;
    }

    /**
     * 服务诊断：service_status + network_port 并行，journal_log 随后。
     */
    private List<ToolStep> createServiceDiagnosisSteps(Map<String, Object> params) {
        List<ToolStep> steps = new ArrayList<>();
        String serviceName = params != null ? (String) params.get("serviceName") : null;
        Map<String, Object> serviceParams = serviceName != null
                ? Map.of("serviceName", serviceName)
                : Map.of();

        // 服务状态和端口检查并行
        steps.add(createStep("service_status_tool", serviceParams, ExecutionMode.PARALLEL, 0));
        steps.add(createStep("network_port_tool", params, ExecutionMode.PARALLEL, 0));
        // 日志查询随后
        steps.add(createStep("journal_log_tool", serviceParams, ExecutionMode.SEQUENTIAL, 1));
        return steps;
    }

    /**
     * 进程查询：有 PID 时查详情，否则查列表。
     */
    private List<ToolStep> createProcessQuerySteps(Map<String, Object> params) {
        List<ToolStep> steps = new ArrayList<>();
        if (params != null && params.containsKey("pid")) {
            steps.add(createStep("process_detail_tool", params, ExecutionMode.SEQUENTIAL, 0));
        } else {
            steps.add(createStep("process_list_tool", params, ExecutionMode.SEQUENTIAL, 0));
        }
        return steps;
    }

    /**
     * 创建单个工具步骤，同时检查工具是否已注册。
     */
    private ToolStep createStep(String toolName, Map<String, Object> params, ExecutionMode mode, int order) {
        if (!toolRegistry.contains(toolName)) {
            log.warn("工具 [{}] 未注册，将跳过此步骤", toolName);
        }
        return ToolStep.builder()
                .toolName(toolName)
                .params(params != null ? params : Map.of())
                .mode(mode)
                .order(order)
                .build();
    }

    // ==================== 内部类型 ====================

    /**
     * 工具调用计划
     */
    @Data
    @Builder
    public static class ToolPlan {
        /** 意图类型 */
        private final IntentType intent;

        /** 有序的步骤列表 */
        private final List<ToolStep> steps;

        /** Optional controlled action. Actions are not OpsTool calls. */
        private final ActionPlan action;

        /** 是否需要风险校验 */
        private final boolean requiresRiskCheck;
    }

    @Data
    @Builder
    public static class ActionPlan {
        private final String actionType;
        private final String target;
        private final Map<String, Object> params;
    }

    /**
     * 单步工具调用
     */
    @Data
    @Builder
    public static class ToolStep {
        /** 工具名称 */
        private String toolName;

        /** 调用参数 */
        private Map<String, Object> params;

        /** 执行模式 */
        private ExecutionMode mode;

        /** 执行顺序 */
        private int order;
    }

    /**
     * 工具执行模式
     */
    public enum ExecutionMode {
        /** 可并发执行（同 order 的工具可并行） */
        PARALLEL,

        /** 需等待上一步完成后执行 */
        SEQUENTIAL
    }
}
