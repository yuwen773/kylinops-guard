package com.kylinops.inspection;

import com.kylinops.tool.OpsTool;
import com.kylinops.tool.ToolExecutor;
import com.kylinops.tool.ToolInput;
import com.kylinops.tool.ToolRegistry;
import com.kylinops.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 巡检阶段执行器(P1-02 Task 5)。按 {@link InspectionTemplateDefinition#stages()}
 * 串行执行每个 stage(stage 内工具按顺序),捕获工具异常并降级为 failed {@link ToolResult},
 * 永不向调用方抛异常。
 *
 * <h3>异常隔离</h3>
 * <p>任何 {@link com.kylinops.tool.ToolNotRegisteredException} /
 * {@link RuntimeException} 都被转为 {@code ToolResult.failed},确保单个工具失败
 * 不会中断整个巡检流程 — 异常判定由 {@link InspectionResultEvaluator} 统一处理。</p>
 */
@Slf4j
@Component
public class InspectionStageExecutor {

    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;

    public InspectionStageExecutor(ToolRegistry toolRegistry, ToolExecutor toolExecutor) {
        this.toolRegistry = toolRegistry;
        this.toolExecutor = toolExecutor;
    }

    /**
     * 按 stages 顺序执行所有工具,产出对应的 {@link ToolResult} 列表(顺序与 stages 一致)。
     *
     * @param template 巡检模板定义
     * @param params   工具入参(透传给所有工具)
     * @param auditId  巡检审计 ID,作为 requestId 贯穿工具调用链
     * @return 工具结果列表(stage 顺序 + 工具顺序)
     */
    public List<ToolResult> executeStages(InspectionTemplateDefinition template,
                                           Map<String, Object> params,
                                           String auditId) {
        List<ToolResult> all = new ArrayList<>();
        if (template == null || template.stages() == null || template.stages().isEmpty()) {
            return all;
        }
        Map<String, Object> safeParams = params == null ? Map.of() : params;

        for (List<String> stage : template.stages()) {
            if (stage == null) continue;
            for (String toolName : stage) {
                if (toolName == null || toolName.isBlank()) continue;
                ToolResult r = executeOne(toolName, safeParams, auditId);
                all.add(r);
            }
        }
        return all;
    }

    /**
     * 执行单个工具,异常隔离。绝不向调用方抛异常。
     */
    private ToolResult executeOne(String toolName, Map<String, Object> params, String auditId) {
        try {
            OpsTool tool = toolRegistry.getTool(toolName);
            ToolInput input = ToolInput.builder()
                    .toolName(toolName)
                    .params(params)
                    .requestId(auditId)
                    .build();
            return toolExecutor.execute(tool, input);
        } catch (Exception e) {
            log.warn("巡检工具 {} 执行异常,降级为 failed ToolResult: {}",
                    toolName, e.getMessage());
            return ToolResult.failed(toolName, "工具执行异常: " + e.getMessage(), 0L);
        }
    }
}
