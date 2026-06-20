package com.kylinops.inspection;

import com.kylinops.common.enums.RiskLevel;
import com.kylinops.tool.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-02 Plan 02 — Task 5 (结果评估器 POJO 测试)。
 *
 * <p>验证 {@link InspectionResultEvaluator} 在纯内存条件下对
 * {@link ToolResult} 列表的异常判定逻辑:</p>
 * <ul>
 *   <li>全部 success → abnormal=false</li>
 *   <li>关键工具失败 → abnormal=true + keyToolFailed=true(最高优先级)</li>
 *   <li>threshold 突破(如磁盘 ≥ 85%)→ abnormal=true</li>
 *   <li>非关键工具失败 → abnormal=true(且 reasons 包含该工具名)</li>
 *   <li>threshold 不突破 → abnormal=false</li>
 * </ul>
 *
 * <p><b>本测试不依赖 Spring 上下文</b>,直接 {@code new} 评估器。ToolResult
 * 由 {@link ToolResult#success} / {@link ToolResult#failed} 静态工厂构造,
 * 模拟真实调用结果(包括 Windows 上 disk_usage_tool 必然 fail 的场景)。</p>
 */
@DisplayName("P1-02 T5 — InspectionResultEvaluator 纯 POJO 评估")
class InspectionResultEvaluatorTest {

    private final InspectionResultEvaluator evaluator = new InspectionResultEvaluator();

    @Test
    @DisplayName("全部 success → abnormal=false + keyToolFailed=false + reasons 为空")
    void allSuccessIsNotAbnormal() {
        InspectionTemplateDefinition tpl = new InspectionTemplateDefinition(
                List.of(List.of("disk_usage_tool")),
                Set.of("disk_usage_tool"),
                Map.of("disk_usage_tool", RiskLevel.L0));
        List<ToolResult> results = List.of(
                successResult("disk_usage_tool", Map.of("usagePercent", 50.0)));

        AbnormalVerdict v = evaluator.evaluate(results, tpl, Map.of());

        assertThat(v.isAbnormal()).isFalse();
        assertThat(v.isKeyToolFailed()).isFalse();
        assertThat(v.getReasons())
                .as("全部 success 不应产出 reason")
                .isEmpty();
    }

    @Test
    @DisplayName("关键工具失败 → abnormal=true + keyToolFailed=true(最高优先级)")
    void keyToolFailureFlagsAbnormalAndKeyToolFailed() {
        InspectionTemplateDefinition tpl = new InspectionTemplateDefinition(
                List.of(List.of("disk_usage_tool", "large_file_scan_tool")),
                Set.of("disk_usage_tool", "large_file_scan_tool"),
                Map.of("disk_usage_tool", RiskLevel.L0,
                        "large_file_scan_tool", RiskLevel.L0));
        List<ToolResult> results = List.of(
                failedResult("disk_usage_tool"),
                successResult("large_file_scan_tool", Map.of("usagePercent", 50.0)));

        AbnormalVerdict v = evaluator.evaluate(results, tpl, Map.of());

        assertThat(v.isAbnormal()).isTrue();
        assertThat(v.isKeyToolFailed())
                .as("关键工具失败必须置 keyToolFailed=true")
                .isTrue();
        assertThat(v.getReasons())
                .as("关键工具失败的 reason 必须出现")
                .anyMatch(r -> r.contains("disk_usage_tool"));
    }

    @Test
    @DisplayName("threshold 突破:磁盘 ≥ 85% → abnormal=true + keyToolFailed=false")
    void thresholdBreachFlagsAbnormal() {
        InspectionTemplateDefinition tpl = new InspectionTemplateDefinition(
                List.of(List.of("disk_usage_tool")),
                Set.of("disk_usage_tool"),
                Map.of("disk_usage_tool", RiskLevel.L0));
        List<ToolResult> results = List.of(
                successResult("disk_usage_tool", Map.of("usagePercent", 92.0)));

        AbnormalVerdict v = evaluator.evaluate(
                results, tpl, Map.of("disk_usage_tool", 85.0));

        assertThat(v.isAbnormal()).isTrue();
        assertThat(v.isKeyToolFailed())
                .as("工具执行本身 success,keyToolFailed 必须为 false")
                .isFalse();
        assertThat(v.getReasons())
                .as("threshold 突破的 reason 必须出现且含关键数字")
                .anyMatch(r -> r.contains("85") || r.contains("92"));
    }

    @Test
    @DisplayName("非关键工具失败 → abnormal=true + keyToolFailed=false + reasons 含工具名")
    void nonKeyToolFailureFlagsAbnormal() {
        InspectionTemplateDefinition tpl = new InspectionTemplateDefinition(
                List.of(List.of("disk_usage_tool"), List.of("large_file_scan_tool")),
                Set.of("disk_usage_tool"),
                Map.of("disk_usage_tool", RiskLevel.L0,
                        "large_file_scan_tool", RiskLevel.L0));
        List<ToolResult> results = List.of(
                successResult("disk_usage_tool", Map.of("usagePercent", 50.0)),
                failedResult("large_file_scan_tool"));

        AbnormalVerdict v = evaluator.evaluate(results, tpl, Map.of());

        assertThat(v.isAbnormal()).isTrue();
        assertThat(v.isKeyToolFailed())
                .as("非关键工具失败,keyToolFailed 必须为 false")
                .isFalse();
        assertThat(v.getReasons())
                .as("reasons 必须含失败的非关键工具名")
                .anyMatch(r -> r.contains("large_file_scan_tool"));
    }

    @Test
    @DisplayName("threshold 不突破 → abnormal=false")
    void thresholdNotBreachedIsNotAbnormal() {
        InspectionTemplateDefinition tpl = new InspectionTemplateDefinition(
                List.of(List.of("disk_usage_tool")),
                Set.of("disk_usage_tool"),
                Map.of("disk_usage_tool", RiskLevel.L0));
        List<ToolResult> results = List.of(
                successResult("disk_usage_tool", Map.of("usagePercent", 50.0)));

        AbnormalVerdict v = evaluator.evaluate(
                results, tpl, Map.of("disk_usage_tool", 85.0));

        assertThat(v.isAbnormal()).isFalse();
        assertThat(v.getReasons()).isEmpty();
    }

    @Test
    @DisplayName("无 threshold 声明时,工具 success 不会触发异常")
    void noThresholdsMeansNoAbnormalOnSuccess() {
        InspectionTemplateDefinition tpl = new InspectionTemplateDefinition(
                List.of(List.of("system_info_tool")),
                Set.of("system_info_tool"),
                Map.of("system_info_tool", RiskLevel.L0));
        List<ToolResult> results = List.of(
                successResult("system_info_tool", Map.of("hostname", "host-1")));

        AbnormalVerdict v = evaluator.evaluate(results, tpl, Map.of());

        assertThat(v.isAbnormal()).isFalse();
    }

    @Test
    @DisplayName("ToolResult.data 不是 Map 时,threshold 检查安全降级为不触发")
    void nonMapDataIsSafelyIgnoredForThresholds() {
        InspectionTemplateDefinition tpl = new InspectionTemplateDefinition(
                List.of(List.of("disk_usage_tool")),
                Set.of("disk_usage_tool"),
                Map.of("disk_usage_tool", RiskLevel.L0));
        // data 是 String,evaluator 必须能容忍(否则就崩)
        List<ToolResult> results = List.of(
                successResult("disk_usage_tool", "not-a-map"));

        AbnormalVerdict v = evaluator.evaluate(
                results, tpl, Map.of("disk_usage_tool", 85.0));

        assertThat(v.isAbnormal())
                .as("非 Map data 不应被误判为突破 threshold")
                .isFalse();
    }

    // ───────── 辅助 ─────────

    private static ToolResult successResult(String toolName, Object data) {
        return ToolResult.success(toolName, data, "ok", 10L);
    }

    private static ToolResult failedResult(String toolName) {
        return ToolResult.failed(toolName, "test failure", 10L);
    }
}
