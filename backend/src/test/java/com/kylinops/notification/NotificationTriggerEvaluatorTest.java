package com.kylinops.notification;

import com.kylinops.common.enums.IntentType;
import com.kylinops.rca.RootCauseChain;
import com.kylinops.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NotificationTriggerEvaluator 纯单测 — P1-01 Plan 02 Task 1。
 *
 * <p>覆盖运维类事件触发判定:<b>不依赖 Spring 上下文</b>,直接 new 出 Evaluator 跑条件分支。</p>
 *
 * <p>口径(Plan 02 §关键不变量 / 最终口径):</p>
 * <ul>
 *   <li>RCA 为 null → SERVICE_ABNORMAL / DISK_RISK 都不触发</li>
 *   <li>SERVICE_ABNORMAL: intent=SERVICE_DIAGNOSIS 且 rca.confidence ≥ 0.7 且 serviceName 可提取</li>
 *   <li>DISK_RISK: intent=DISK_DIAGNOSIS 且 rca 存在,且(rca.confidence ≥ 0.7 或 diskUsagePercent ≥ 85.0)</li>
 * </ul>
 */
@DisplayName("NotificationTriggerEvaluator — 运维事件触发判定")
class NotificationTriggerEvaluatorTest {

    private static final String TOOL_DISK = "disk_usage_tool";
    private static final String TOOL_SERVICE = "service_status_tool";

    private NotificationTriggerEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new NotificationTriggerEvaluator();
    }

    // ─────────────────────────────────────────────────────────────────
    //  shouldEmitServiceAbnormal
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("shouldEmitServiceAbnormal")
    class ServiceAbnormal {

        @Test
        @DisplayName("intent 非 SERVICE_DIAGNOSIS → 不触发")
        void notServiceDiagnosisIntent() {
            RootCauseChain rca = rca(0.8);
            assertThat(evaluator.shouldEmitServiceAbnormal(IntentType.DISK_DIAGNOSIS, rca, List.of())).isFalse();
            assertThat(evaluator.shouldEmitServiceAbnormal(IntentType.SYSTEM_CHECK, rca, List.of())).isFalse();
            assertThat(evaluator.shouldEmitServiceAbnormal(IntentType.GENERAL_CHAT, rca, List.of())).isFalse();
        }

        @Test
        @DisplayName("rca 为 null → 不触发")
        void rcaNull() {
            assertThat(evaluator.shouldEmitServiceAbnormal(IntentType.SERVICE_DIAGNOSIS, null, List.of())).isFalse();
        }

        @Test
        @DisplayName("rca.confidence < 0.7 → 不触发(即便有 serviceName)")
        void lowConfidence() {
            ToolResult sr = ToolResult.success(TOOL_SERVICE,
                    Map.of("serviceName", "nginx"), "ok", 10);
            assertThat(evaluator.shouldEmitServiceAbnormal(
                    IntentType.SERVICE_DIAGNOSIS, rca(0.5), List.of(sr))).isFalse();
            assertThat(evaluator.shouldEmitServiceAbnormal(
                    IntentType.SERVICE_DIAGNOSIS, rca(0.69), List.of(sr))).isFalse();
        }

        @Test
        @DisplayName("rca.confidence ≥ 0.7 但 serviceName 不可提取 → 不触发")
        void serviceNameMissing() {
            assertThat(evaluator.shouldEmitServiceAbnormal(
                    IntentType.SERVICE_DIAGNOSIS, rca(0.8), Collections.emptyList())).isFalse();
        }

        @Test
        @DisplayName("rca.confidence ≥ 0.7 且 serviceName 可提取 → 触发")
        void happyPath() {
            ToolResult sr = ToolResult.success(TOOL_SERVICE,
                    Map.of("serviceName", "nginx"), "ok", 10);
            assertThat(evaluator.shouldEmitServiceAbnormal(
                    IntentType.SERVICE_DIAGNOSIS, rca(0.7), List.of(sr))).isTrue();
            assertThat(evaluator.shouldEmitServiceAbnormal(
                    IntentType.SERVICE_DIAGNOSIS, rca(0.95), List.of(sr))).isTrue();
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  shouldEmitDiskRisk
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("shouldEmitDiskRisk")
    class DiskRisk {

        @Test
        @DisplayName("intent 非 DISK_DIAGNOSIS → 不触发")
        void notDiskDiagnosisIntent() {
            RootCauseChain rca = rca(0.8);
            assertThat(evaluator.shouldEmitDiskRisk(IntentType.SERVICE_DIAGNOSIS, rca, List.of())).isFalse();
            assertThat(evaluator.shouldEmitDiskRisk(IntentType.SYSTEM_CHECK, rca, List.of())).isFalse();
        }

        @Test
        @DisplayName("rca 为 null → 不触发(即便磁盘 ≥ 85%)")
        void rcaNull() {
            ToolResult disk = diskResult(90.0, "/var");
            assertThat(evaluator.shouldEmitDiskRisk(IntentType.DISK_DIAGNOSIS, null, List.of(disk))).isFalse();
        }

        @Test
        @DisplayName("rca.confidence < 0.7 且磁盘 < 85% → 不触发")
        void lowConfidenceAndLowUsage() {
            ToolResult disk = diskResult(50.0, "/");
            assertThat(evaluator.shouldEmitDiskRisk(
                    IntentType.DISK_DIAGNOSIS, rca(0.5), List.of(disk))).isFalse();
        }

        @Test
        @DisplayName("rca.confidence ≥ 0.7 → 触发(磁盘用量无所谓)")
        void highConfidenceTriggers() {
            assertThat(evaluator.shouldEmitDiskRisk(
                    IntentType.DISK_DIAGNOSIS, rca(0.7), Collections.emptyList())).isTrue();
            assertThat(evaluator.shouldEmitDiskRisk(
                    IntentType.DISK_DIAGNOSIS, rca(0.95), Collections.emptyList())).isTrue();
        }

        @Test
        @DisplayName("rca.confidence < 0.7 但磁盘 ≥ 85% → 仍触发(关键正向用例)")
        void lowConfidenceButHighUsage() {
            ToolResult disk = diskResult(90.0, "/var");
            assertThat(evaluator.shouldEmitDiskRisk(
                    IntentType.DISK_DIAGNOSIS, rca(0.5), List.of(disk))).isTrue();
        }

        @Test
        @DisplayName("磁盘恰好 85.0 触发(边界)")
        void exactThreshold() {
            ToolResult disk = diskResult(85.0, "/");
            assertThat(evaluator.shouldEmitDiskRisk(
                    IntentType.DISK_DIAGNOSIS, rca(0.5), List.of(disk))).isTrue();
        }

        @Test
        @DisplayName("磁盘 84.9 不触发(无 rca 高 confidence)")
        void justBelowThreshold() {
            ToolResult disk = diskResult(84.9, "/");
            assertThat(evaluator.shouldEmitDiskRisk(
                    IntentType.DISK_DIAGNOSIS, rca(0.5), List.of(disk))).isFalse();
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  extractDiskUsagePercent
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("extractDiskUsagePercent")
    class DiskUsagePercent {

        @Test
        @DisplayName("空 toolResults → empty")
        void emptyResults() {
            assertThat(evaluator.extractDiskUsagePercent(Collections.emptyList())).isEmpty();
        }

        @Test
        @DisplayName("无 disk_usage_tool 结果 → empty")
        void noDiskUsageTool() {
            ToolResult cpu = ToolResult.success("cpu_status_tool",
                    Map.of("usage", 10.0), "ok", 10);
            assertThat(evaluator.extractDiskUsagePercent(List.of(cpu))).isEmpty();
        }

        @Test
        @DisplayName("非 success 结果忽略 → empty")
        void nonSuccessIgnored() {
            ToolResult disk = ToolResult.failed(TOOL_DISK, "boom", 10);
            assertThat(evaluator.extractDiskUsagePercent(List.of(disk))).isEmpty();
        }

        @Test
        @DisplayName("partitions 缺 usedPercent 字段 → empty")
        void missingFieldIgnored() {
            ToolResult disk = ToolResult.success(TOOL_DISK,
                    List.of(Map.of("mount", "/")), "ok", 10);
            assertThat(evaluator.extractDiskUsagePercent(List.of(disk))).isEmpty();
        }

        @Test
        @DisplayName("取多分区中最大 usedPercent")
        void extractsMaxUsedPercent() {
            ToolResult disk = ToolResult.success(TOOL_DISK,
                    Map.of("partitions", List.of(
                            Map.of("usedPercent", 50.0, "mount", "/"),
                            Map.of("usedPercent", 92.0, "mount", "/var"),
                            Map.of("usedPercent", 30.0, "mount", "/tmp")
                    )),
                    "ok", 10);
            assertThat(evaluator.extractDiskUsagePercent(List.of(disk))).contains(92.0);
        }

        @Test
        @DisplayName("data 非 Map → empty(防御)")
        void dataNotMapIgnored() {
            ToolResult disk = ToolResult.success(TOOL_DISK,
                    "this is not a map", "ok", 10);
            assertThat(evaluator.extractDiskUsagePercent(List.of(disk))).isEmpty();
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  extractDiskPath
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("extractDiskPath")
    class DiskPath {

        @Test
        @DisplayName("rca 为 null → empty")
        void rcaNull() {
            assertThat(evaluator.extractDiskPath(null, Collections.emptyList())).isEmpty();
        }

        @Test
        @DisplayName("partitions 中有挂载点 → 返回该挂载点")
        void extractsFromPartitions() {
            ToolResult disk = diskResult(92.0, "/var");
            RootCauseChain rca = RootCauseChain.builder().confidence(0.5).build();
            assertThat(evaluator.extractDiskPath(rca, List.of(disk))).contains("/var");
        }

        @Test
        @DisplayName("rca 与 toolResults 都拿不到 → empty")
        void noSourceReturnsEmpty() {
            RootCauseChain rca = RootCauseChain.builder().confidence(0.5).build();
            assertThat(evaluator.extractDiskPath(rca, Collections.emptyList())).isEmpty();
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  extractServiceName
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("extractServiceName")
    class ServiceName {

        @Test
        @DisplayName("从 service_status_tool.data.serviceName 提取")
        void fromServiceStatusTool() {
            ToolResult sr = ToolResult.success(TOOL_SERVICE,
                    Map.of("serviceName", "nginx"), "ok", 10);
            assertThat(evaluator.extractServiceName(null, List.of(sr))).contains("nginx");
        }

        @Test
        @DisplayName("空 toolResults → empty")
        void emptyResults() {
            assertThat(evaluator.extractServiceName(null, Collections.emptyList())).isEmpty();
        }

        @Test
        @DisplayName("忽略非 service_status_tool 的结果")
        void ignoresOtherTools() {
            ToolResult cpu = ToolResult.success("cpu_status_tool",
                    Map.of("serviceName", "should-not-be-used"), "ok", 10);
            assertThat(evaluator.extractServiceName(null, List.of(cpu))).isEmpty();
        }

        @Test
        @DisplayName("多个结果取首个 service_status_tool 的 serviceName")
        void firstMatchWins() {
            ToolResult first = ToolResult.success(TOOL_SERVICE,
                    Map.of("serviceName", "nginx"), "ok", 10);
            ToolResult second = ToolResult.success(TOOL_SERVICE,
                    Map.of("serviceName", "redis"), "ok", 10);
            Optional<String> name = evaluator.extractServiceName(null, List.of(first, second));
            assertThat(name).isPresent();
            assertThat(name.get()).isIn("nginx", "redis");
        }

        @Test
        @DisplayName("非 success 状态忽略")
        void nonSuccessIgnored() {
            ToolResult failed = ToolResult.failed(TOOL_SERVICE, "boom", 10);
            assertThat(evaluator.extractServiceName(null, List.of(failed))).isEmpty();
        }

        @Test
        @DisplayName("data 缺 serviceName 字段 → empty")
        void missingFieldIgnored() {
            ToolResult sr = ToolResult.success(TOOL_SERVICE,
                    Map.of("status", "active"), "ok", 10);
            assertThat(evaluator.extractServiceName(null, List.of(sr))).isEmpty();
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  工具
    // ─────────────────────────────────────────────────────────────────

    private static RootCauseChain rca(double confidence) {
        return RootCauseChain.builder()
                .confidence(confidence)
                .conclusion("test conclusion for confidence " + confidence)
                .build();
    }

    private static ToolResult diskResult(double usedPercent, String mount) {
        return ToolResult.success(TOOL_DISK,
                Map.of("partitions", List.of(
                        Map.of("usedPercent", usedPercent, "mount", mount))),
                "ok", 10);
    }
}