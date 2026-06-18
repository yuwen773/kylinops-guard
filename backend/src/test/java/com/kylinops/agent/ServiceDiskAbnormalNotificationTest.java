package com.kylinops.agent;

import com.kylinops.agent.AgentOrchestrator.AgentRequest;
import com.kylinops.agent.intelligence.HybridIntentService;
import com.kylinops.agent.intelligence.IntentResolution;
import com.kylinops.common.enums.IntentType;
import com.kylinops.notification.NotificationEvent;
import com.kylinops.notification.NotificationEventType;
import com.kylinops.notification.NotificationService;
import com.kylinops.rca.RootCauseAnalyzer;
import com.kylinops.rca.RootCauseChain;
import com.kylinops.tool.ToolExecutor;
import com.kylinops.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AgentOrchestrator 运维类事件通知集成测试 — P1-01 Plan 02 Task 4。
 *
 * <p>本测试只验证关键 wiring:<b>不重测</b>NotificationTriggerEvaluator(单测已覆盖)、
 * 不重测 OS 工具链(DISK_USAGE / SERVICE_STATUS 等实工具测试由 os/ 包覆盖)。
 * 重点验证 AgentOrchestrator 在 Step 7.5 RCA 生成后,能通过 Evaluator 判定并发出
 * SERVICE_ABNORMAL / DISK_RISK 事件。</p>
 *
 * <p><b>Mock 策略</b>(轻量,避免全真实 tool chain 的环境差异):</p>
 * <ul>
 *   <li>{@code @MockBean NotificationService} — 拦截 emit 调用,ArgumentCaptor 捕获</li>
 *   <li>{@code @MockBean HybridIntentService} — 强制 intent(消除分类器差异)</li>
 *   <li>{@code @MockBean ToolExecutor} — 返回受控 ToolResult(不真实执行 OS 命令)</li>
 *   <li>{@code @MockBean RootCauseAnalyzer} — 返回受控 RCA(不依赖具体 analyzer 实现)</li>
 *   <li>真实 {@code NotificationEventFactory} — 验证事件字段正确性</li>
 * </ul>
 *
 * <p><b>环境</b>:test profile 下 {@code notification.enabled=false},但本测试
 * 通过 {@code @MockBean} 替换 NotificationService,使其永远进入 emit 分支
 * (即使 enabled=false,真实 NotificationService 也会短路;mock 不短路)。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("AgentOrchestrator 运维类事件通知集成测试")
class ServiceDiskAbnormalNotificationTest {

    @Autowired
    private AgentOrchestrator orchestrator;

    @MockBean
    private NotificationService notificationService;

    @MockBean
    private HybridIntentService hybridIntentService;

    @MockBean
    private ToolExecutor toolExecutor;

    @MockBean
    private RootCauseAnalyzer rootCauseAnalyzer;

    @BeforeEach
    void setUp() {
        // 显式 reset:防止前一个 test 的 stubbing 残留影响(默认 @MockBean reset 是 AFTER,
        // 但显式 reset 更稳妥)
        Mockito.reset(hybridIntentService, toolExecutor, rootCauseAnalyzer, notificationService);

        // 默认:未知 tool → success(empty data)。关键 — 串行步骤中,若第一个工具失败
        // orchestrator 会 break 后续工具(SERVICE_DIAGNOSIS 3 步 / DISK_DIAGNOSIS 2 步
        // 都是混合 serial+parallel)。返回 success 让 chain 跑完,具体工具由 per-test 覆盖。
        when(toolExecutor.execute(anyString(), any(), any()))
                .thenAnswer(inv -> ToolResult.success(inv.getArgument(0),
                        Map.of(), "mocked-default", 0));

        // 默认:HybridIntentService → UNKNOWN(让 per-test 强制覆盖)
        when(hybridIntentService.resolve(anyString()))
                .thenAnswer(inv -> IntentResolution.ruleHit(IntentType.UNKNOWN));

        // 默认:RootCauseAnalyzer → null(单个测试覆盖)
        when(rootCauseAnalyzer.analyze(any(), any(), any())).thenReturn(null);
    }

    // ─────────────────────────────────────────────────────────────
    //  SERVICE_ABNORMAL
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("SERVICE_DIAGNOSIS + RCA confidence≥0.7 + serviceName 可提取 → emit SERVICE_ABNORMAL")
    void serviceAbnormal_emits_whenHighConfidenceAndExtractable() {
        // intent 强制
        when(hybridIntentService.resolve(anyString()))
                .thenReturn(intentResolution(IntentType.SERVICE_DIAGNOSIS));
        // service_status_tool 返回 nginx
        when(toolExecutor.execute(eq("service_status_tool"), any(), any()))
                .thenReturn(ToolResult.success("service_status_tool",
                        Map.of("serviceName", "nginx"), "nginx status", 10));
        // RCA confidence=0.85
        when(rootCauseAnalyzer.analyze(eq(IntentType.SERVICE_DIAGNOSIS), any(), any()))
                .thenReturn(RootCauseChain.builder()
                        .confidence(0.85)
                        .conclusion("服务 nginx 异常,推测配置文件损坏")
                        .symptom("nginx 不可用")
                        .build());

        orchestrator.process(buildRequest("检查 nginx 状态"));

        NotificationEvent event = captureSingleEvent(NotificationEventType.SERVICE_ABNORMAL);
        assertThat(event.getServiceName()).isEqualTo("nginx");
        assertThat(event.getRcaConfidence()).isEqualTo(0.85);
        assertThat(event.getTitle()).isNotBlank();
        assertThat(event.getSummary()).isNotBlank();
        assertThat(event.getDetail()).isNotBlank();
        assertThat(event.getEventType()).isEqualTo(NotificationEventType.SERVICE_ABNORMAL);
    }

    // ─────────────────────────────────────────────────────────────
    //  DISK_RISK
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("DISK_DIAGNOSIS + RCA confidence≥0.7 → emit DISK_RISK")
    void diskRisk_emits_whenHighConfidence() {
        when(hybridIntentService.resolve(anyString()))
                .thenReturn(intentResolution(IntentType.DISK_DIAGNOSIS));
        // 单分区,聚焦"高用量 → DISK_RISK"用例(plan 口径:partitions[0].mount)
        when(toolExecutor.execute(eq("disk_usage_tool"), any(), any()))
                .thenReturn(ToolResult.success("disk_usage_tool",
                        Map.of("partitions", List.of(
                                Map.of("usedPercent", 92.0, "mount", "/var")
                        )),
                        "disk status", 10));
        when(rootCauseAnalyzer.analyze(eq(IntentType.DISK_DIAGNOSIS), any(), any()))
                .thenReturn(RootCauseChain.builder()
                        .confidence(0.9)
                        .conclusion("磁盘 /var 使用率 92%,接近耗尽")
                        .symptom("/var 满")
                        .build());

        orchestrator.process(buildRequest("查看 /var 磁盘"));

        NotificationEvent event = captureSingleEvent(NotificationEventType.DISK_RISK);
        assertThat(event.getDiskPath()).isEqualTo("/var");
        assertThat(event.getDiskUsagePercent()).isEqualTo(92.0);
        assertThat(event.getRcaConfidence()).isEqualTo(0.9);
        assertThat(event.getEventType()).isEqualTo(NotificationEventType.DISK_RISK);
        assertThat(event.getTitle()).isNotBlank();
        assertThat(event.getSummary()).isNotBlank();
    }

    @Test
    @DisplayName("DISK_DIAGNOSIS + RCA confidence<0.7 + 磁盘≥85% → 仍 emit DISK_RISK(关键正向用例)")
    void diskRisk_emits_whenLowConfidenceButHighUsage() {
        when(hybridIntentService.resolve(anyString()))
                .thenReturn(intentResolution(IntentType.DISK_DIAGNOSIS));
        when(toolExecutor.execute(eq("disk_usage_tool"), any(), any()))
                .thenReturn(ToolResult.success("disk_usage_tool",
                        Map.of("partitions", List.of(
                                Map.of("usedPercent", 90.0, "mount", "/var")
                        )),
                        "disk status", 10));
        // RCA confidence=0.5(<0.7),但磁盘 90% → 仍应触发
        when(rootCauseAnalyzer.analyze(eq(IntentType.DISK_DIAGNOSIS), any(), any()))
                .thenReturn(RootCauseChain.builder()
                        .confidence(0.5)
                        .conclusion("磁盘使用率偏高,但 RCA 置信度不足")
                        .symptom("/var 满")
                        .build());

        orchestrator.process(buildRequest("查看 /var 磁盘"));

        NotificationEvent event = captureSingleEvent(NotificationEventType.DISK_RISK);
        assertThat(event.getDiskPath()).isEqualTo("/var");
        assertThat(event.getDiskUsagePercent()).isEqualTo(90.0);
        assertThat(event.getRcaConfidence()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("DISK_DIAGNOSIS + diskPath 不可提取 → 仍 emit DISK_RISK,diskPath=\"/\"")
    void diskRisk_emits_withDefaultPathWhenPathMissing() {
        when(hybridIntentService.resolve(anyString()))
                .thenReturn(intentResolution(IntentType.DISK_DIAGNOSIS));
        // disk_usage_tool 数据不含 mount 字段 → extractDiskPath fallback 到 rca.conclusion
        // rca.conclusion 也无路径 → fallback empty,emit 时 diskPath="/"
        when(toolExecutor.execute(eq("disk_usage_tool"), any(), any()))
                .thenReturn(ToolResult.success("disk_usage_tool",
                        Map.of("partitions", List.of(
                                Map.of("usedPercent", 90.0)
                                // 故意不提供 mount
                        )),
                        "disk status", 10));
        when(rootCauseAnalyzer.analyze(eq(IntentType.DISK_DIAGNOSIS), any(), any()))
                .thenReturn(RootCauseChain.builder()
                        .confidence(0.5)
                        .conclusion("磁盘使用率异常") // 无路径
                        .symptom("磁盘满")
                        .build());

        orchestrator.process(buildRequest("查看磁盘"));

        NotificationEvent event = captureSingleEvent(NotificationEventType.DISK_RISK);
        assertThat(event.getDiskPath()).isEqualTo("/");
        assertThat(event.getDiskUsagePercent()).isEqualTo(90.0);
    }

    // ─────────────────────────────────────────────────────────────
    //  反向:RCA null → 不 emit
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("RCA 为 null → 不触发任何运维类 emit")
    void noEmit_whenRcaNull() {
        when(hybridIntentService.resolve(anyString()))
                .thenReturn(intentResolution(IntentType.SERVICE_DIAGNOSIS));
        when(toolExecutor.execute(eq("service_status_tool"), any(), any()))
                .thenReturn(ToolResult.success("service_status_tool",
                        Map.of("serviceName", "nginx"), "ok", 10));
        // setUp 默认: RCA 返回 null

        orchestrator.process(buildRequest("检查 nginx"));

        // 即使 service_status_tool 有 serviceName,RCA null 时 SERVICE_ABNORMAL 不触发
        verify(notificationService, never()).emit(any());
    }

    // ─────────────────────────────────────────────────────────────
    //  helpers
    // ─────────────────────────────────────────────────────────────

    private AgentRequest buildRequest(String userInput) {
        return AgentRequest.builder()
                .sessionId(UUID.randomUUID().toString())
                .userInput(userInput)
                .requestId(UUID.randomUUID().toString())
                .build();
    }

    private static IntentResolution intentResolution(IntentType intent) {
        return IntentResolution.ruleHit(intent);
    }

    /**
     * 从所有捕获的 emit 中筛出指定 type 的事件;若不存在则断言失败并打印全部。
     */
    private NotificationEvent captureSingleEvent(NotificationEventType expectedType) {
        ArgumentCaptor<NotificationEvent> captor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(notificationService, atLeastOnce()).emit(captor.capture());
        List<NotificationEvent> all = captor.getAllValues();
        List<NotificationEvent> matched = all.stream()
                .filter(e -> e != null && e.getEventType() == expectedType)
                .toList();
        assertThat(matched)
                .as("expected at least one %s event among %s", expectedType,
                        all.stream().map(NotificationEvent::getEventType).toList())
                .isNotEmpty();
        // 校验非 L4 / 非 L2 / 非 prompt inject(防止安全类事件混淆)
        long safetyCount = all.stream()
                .filter(e -> e.getEventType() == NotificationEventType.L4_BLOCK
                        || e.getEventType() == NotificationEventType.L2_CONFIRM_REQUIRED
                        || e.getEventType() == NotificationEventType.PROMPT_INJECTION_BLOCK)
                .count();
        assertThat(safetyCount)
                .as("本测试不应触发安全类事件,但出现 %s 个", safetyCount)
                .isZero();
        return matched.get(0);
    }
}