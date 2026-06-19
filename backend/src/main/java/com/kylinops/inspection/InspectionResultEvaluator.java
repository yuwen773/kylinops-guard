package com.kylinops.inspection;

import com.kylinops.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 巡检结果评估器(P1-02 Task 5)。纯 POJO,无状态。
 *
 * <p>输入 {@link ToolResult} 列表 + {@link InspectionTemplateDefinition} 模板
 * 描述 + 阈值表({@code toolName → maxUsagePercent}),输出
 * {@link AbnormalVerdict}。判定规则按任务设计:</p>
 * <ol>
 *   <li><b>关键工具失败</b>(任一 {@code keyToolNames} 内的工具 success=false)→
 *       {@code abnormal=true} + {@code keyToolFailed=true} + 添加 reason</li>
 *   <li><b>阈值突破</b>(工具 success 且其 data 含 {@code usagePercent} ≥ 阈值)→
 *       {@code abnormal=true} + 添加 reason</li>
 *   <li><b>非关键工具失败</b>(其它工具 success=false)→ {@code abnormal=true} +
 *       添加 reason</li>
 * </ol>
 *
 * <p>优先级:关键工具失败 reasons 排最前,其次阈值突破,最后非关键工具失败。
 * 任何 {@code data} 非 {@link Map} 类型的工具对阈值检查安全降级为"不触发"(
 * 不抛异常)。</p>
 */
@Component
public class InspectionResultEvaluator {

    /**
     * 评估一组工具结果是否触发异常。
     *
     * @param results     阶段执行器产出的工具结果列表(可为 null 或空)
     * @param template    巡检模板定义(决定 keyToolNames 集合)
     * @param thresholds  阈值映射 {toolName → maxUsagePercent},可空
     * @return 异常判定结果(永远非 null,空输入返回全 false + 空 reasons)
     */
    public AbnormalVerdict evaluate(List<ToolResult> results,
                                     InspectionTemplateDefinition template,
                                     Map<String, Double> thresholds) {
        if (results == null || results.isEmpty() || template == null) {
            return new AbnormalVerdict(false, List.of(), false);
        }

        List<String> reasons = new ArrayList<>();
        boolean keyToolFailed = false;
        boolean thresholdBreached = false;
        boolean nonKeyToolFailed = false;

        // 1. 关键工具失败(最高优先级,排最前)
        for (ToolResult r : results) {
            if (r == null) continue;
            String name = r.getToolName();
            if (template.keyToolNames().contains(name) && !r.isSuccess()) {
                keyToolFailed = true;
                reasons.add("关键工具失败: " + name
                        + (r.getErrorMessage() != null ? " - " + r.getErrorMessage() : ""));
            }
        }

        // 2. 阈值突破(中间优先级)
        if (thresholds != null && !thresholds.isEmpty()) {
            for (Map.Entry<String, Double> e : thresholds.entrySet()) {
                String toolName = e.getKey();
                Double maxPercent = e.getValue();
                if (toolName == null || maxPercent == null) continue;
                ToolResult r = findResult(results, toolName);
                if (r == null || !r.isSuccess()) continue;
                Double actualPercent = extractUsagePercent(r.getData());
                if (actualPercent != null && actualPercent >= maxPercent) {
                    thresholdBreached = true;
                    reasons.add("阈值突破: " + toolName + " 用量 "
                            + actualPercent + "% ≥ " + maxPercent + "%");
                }
            }
        }

        // 3. 非关键工具失败(最低优先级,排最后)
        for (ToolResult r : results) {
            if (r == null) continue;
            String name = r.getToolName();
            if (template.keyToolNames().contains(name)) continue;
            if (!r.isSuccess()) {
                nonKeyToolFailed = true;
                reasons.add("非关键工具失败: " + name
                        + (r.getErrorMessage() != null ? " - " + r.getErrorMessage() : ""));
            }
        }

        boolean abnormal = keyToolFailed || thresholdBreached || nonKeyToolFailed;
        return new AbnormalVerdict(abnormal, List.copyOf(reasons), keyToolFailed);
    }

    private static ToolResult findResult(List<ToolResult> results, String toolName) {
        for (ToolResult r : results) {
            if (r != null && toolName.equals(r.getToolName())) {
                return r;
            }
        }
        return null;
    }

    /**
     * 从 {@link ToolResult#getData()} 提取 {@code usagePercent} 数值。
     * 仅识别 {@code Map<String, Object>} 内的 {@code Number} 值,其它类型安全降级为 null。
     */
    @SuppressWarnings("unchecked")
    private static Double extractUsagePercent(Object data) {
        if (data instanceof Map<?, ?> m) {
            Object v = m.get("usagePercent");
            if (v instanceof Number n) {
                return n.doubleValue();
            }
        }
        return null;
    }
}
