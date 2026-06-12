package com.kylinops.dashboard;

import com.kylinops.audit.AuditLog;
import com.kylinops.audit.AuditLogService;
import com.kylinops.common.enums.AuditStatus;
import com.kylinops.common.enums.PermissionType;
import com.kylinops.common.enums.RiskLevel;
import com.kylinops.common.enums.ToolStatus;
import com.kylinops.tool.ToolDefinition;
import com.kylinops.tool.ToolExecutor;
import com.kylinops.tool.ToolRegistry;
import com.kylinops.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DashboardService 单元测试 — 覆盖以下场景：
 * <ul>
 *   <li>所有工具成功 → score 非空、degraded=false</li>
 *   <li>部分工具失败 → degraded=true、score 基于成功指标、successfulMetricCount 正确</li>
 *   <li>所有工具失败 → score=null、degraded=true、successfulMetricCount=0</li>
 *   <li>每次 refresh 创建 auditId 并共享到所有 ToolExecutor 调用</li>
 *   <li>采集结束审计状态被更新（finalizeCollection 被调用）</li>
 *   <li>只读工具过滤（riskLevel=L0/L1 + permissionType=READ）</li>
 *   <li>不调用 ToolNotRegisteredException 等异常路径</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DashboardService — 系统概览采集")
class DashboardServiceTest {

    @Mock
    private ToolRegistry toolRegistry;

    @Mock
    private ToolExecutor toolExecutor;

    @Mock
    private AuditLogService auditLogService;

    private DashboardService service;

    private final List<ToolDefinition> readOnlyTools = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new DashboardService(toolRegistry, toolExecutor, auditLogService);

        // 构造 5 个只读 L0 工具定义（覆盖健康检查核心维度）
        readOnlyTools.add(buildDef("system_info_tool"));
        readOnlyTools.add(buildDef("cpu_status_tool"));
        readOnlyTools.add(buildDef("memory_status_tool"));
        readOnlyTools.add(buildDef("disk_usage_tool"));
        readOnlyTools.add(buildDef("network_port_tool"));
    }

    private ToolDefinition buildDef(String name) {
        ToolDefinition def = new ToolDefinition();
        def.setToolName(name);
        def.setRiskLevel(RiskLevel.L0);
        def.setPermissionType(PermissionType.READ);
        def.setToolStatus(ToolStatus.ENABLED);
        def.setTimeoutMs(3000L);
        return def;
    }

    private ToolResult successResult(String toolName, Object data) {
        return ToolResult.success(toolName, data, "ok", 10L);
    }

    private ToolResult failedResult(String toolName, String err) {
        return ToolResult.failed(toolName, err, 5L);
    }

    @Test
    @DisplayName("全部成功 → score 非空、degraded=false、successfulMetricCount=total")
    void allSuccess() {
        when(toolRegistry.getEnabledToolDefinitions()).thenReturn(readOnlyTools);

        AuditLog auditLog = new AuditLog();
        auditLog.setAuditId("audit-all-ok");
        when(auditLogService.startCollection(any())).thenReturn(auditLog);

        when(toolExecutor.execute(eq("system_info_tool"), any(), eq("audit-all-ok")))
                .thenReturn(successResult("system_info_tool", Map.of("hostname", "demo-host")));
        when(toolExecutor.execute(eq("cpu_status_tool"), any(), eq("audit-all-ok")))
                .thenReturn(successResult("cpu_status_tool", Map.of("usagePercent", 30.0)));
        when(toolExecutor.execute(eq("memory_status_tool"), any(), eq("audit-all-ok")))
                .thenReturn(successResult("memory_status_tool", Map.of("usedPercent", 45.0)));
        when(toolExecutor.execute(eq("disk_usage_tool"), any(), eq("audit-all-ok")))
                .thenReturn(successResult("disk_usage_tool", Map.of("note", "ok")));
        when(toolExecutor.execute(eq("network_port_tool"), any(), eq("audit-all-ok")))
                .thenReturn(successResult("network_port_tool", List.of()));

        DashboardOverview overview = service.refresh();

        assertThat(overview).isNotNull();
        assertThat(overview.getAuditId()).isEqualTo("audit-all-ok");
        assertThat(overview.getTotalMetricCount()).isEqualTo(5);
        assertThat(overview.getSuccessfulMetricCount()).isEqualTo(5);
        assertThat(overview.isDegraded()).isFalse();
        assertThat(overview.getScore()).isNotNull().isBetween(0, 100);
        assertThat(overview.getMetrics()).hasSize(5);
        assertThat(overview.getMetrics())
                .allMatch(m -> "success".equals(m.getStatus()));
    }

    @Test
    @DisplayName("部分失败 → degraded=true、score 基于成功指标、successfulMetricCount=4")
    void partialFailure() {
        when(toolRegistry.getEnabledToolDefinitions()).thenReturn(readOnlyTools);

        AuditLog auditLog = new AuditLog();
        auditLog.setAuditId("audit-partial");
        when(auditLogService.startCollection(any())).thenReturn(auditLog);

        when(toolExecutor.execute(eq("system_info_tool"), any(), anyString()))
                .thenReturn(successResult("system_info_tool", Map.of("hostname", "demo")));
        when(toolExecutor.execute(eq("cpu_status_tool"), any(), anyString()))
                .thenReturn(successResult("cpu_status_tool", Map.of("usagePercent", 50.0)));
        when(toolExecutor.execute(eq("memory_status_tool"), any(), anyString()))
                .thenReturn(failedResult("memory_status_tool", "memory probe failed"));
        when(toolExecutor.execute(eq("disk_usage_tool"), any(), anyString()))
                .thenReturn(successResult("disk_usage_tool", Map.of("note", "ok")));
        when(toolExecutor.execute(eq("network_port_tool"), any(), anyString()))
                .thenReturn(successResult("network_port_tool", List.of()));

        DashboardOverview overview = service.refresh();

        assertThat(overview.isDegraded()).isTrue();
        assertThat(overview.getTotalMetricCount()).isEqualTo(5);
        assertThat(overview.getSuccessfulMetricCount()).isEqualTo(4);
        assertThat(overview.getScore()).isNotNull().isBetween(0, 100);

        DashboardMetric memMetric = overview.getMetrics().stream()
                .filter(m -> "memory_status_tool".equals(m.getToolName()))
                .findFirst().orElseThrow();
        assertThat(memMetric.getStatus()).isEqualTo("failed");
        assertThat(memMetric.getErrorMessage()).contains("memory probe failed");
    }

    @Test
    @DisplayName("全部失败 → score=null、degraded=true、successfulMetricCount=0")
    void allFailed() {
        when(toolRegistry.getEnabledToolDefinitions()).thenReturn(readOnlyTools);

        AuditLog auditLog = new AuditLog();
        auditLog.setAuditId("audit-all-fail");
        when(auditLogService.startCollection(any())).thenReturn(auditLog);

        when(toolExecutor.execute(anyString(), any(), anyString()))
                .thenAnswer(inv -> failedResult(inv.getArgument(0), "boom"));

        DashboardOverview overview = service.refresh();

        assertThat(overview.isDegraded()).isTrue();
        assertThat(overview.getTotalMetricCount()).isEqualTo(5);
        assertThat(overview.getSuccessfulMetricCount()).isEqualTo(0);
        assertThat(overview.getScore()).isNull();
    }

    @Test
    @DisplayName("同一 auditId 必须传递到所有 ToolExecutor 调用（共享链路）")
    void auditIdSharedAcrossToolCalls() {
        when(toolRegistry.getEnabledToolDefinitions()).thenReturn(readOnlyTools);

        AuditLog auditLog = new AuditLog();
        auditLog.setAuditId("audit-shared");
        when(auditLogService.startCollection(any())).thenReturn(auditLog);

        when(toolExecutor.execute(anyString(), any(), anyString()))
                .thenAnswer(inv -> successResult(inv.getArgument(0), Map.of()));

        service.refresh();

        for (ToolDefinition def : readOnlyTools) {
            verify(toolExecutor, times(1))
                    .execute(eq(def.getToolName()), any(), eq("audit-shared"));
        }
    }

    @Test
    @DisplayName("采集结束必须调用 auditLogService.finalizeCollection 并更新覆盖率")
    void finalizeCollectionCalledOnCompletion() {
        when(toolRegistry.getEnabledToolDefinitions()).thenReturn(readOnlyTools);

        AuditLog auditLog = new AuditLog();
        auditLog.setAuditId("audit-finalize");
        when(auditLogService.startCollection(any())).thenReturn(auditLog);

        when(toolExecutor.execute(anyString(), any(), anyString()))
                .thenAnswer(inv -> successResult(inv.getArgument(0), Map.of()));

        service.refresh();

        ArgumentCaptor<String> auditIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Double> coverageCaptor = ArgumentCaptor.forClass(Double.class);
        ArgumentCaptor<AuditStatus> statusCaptor = ArgumentCaptor.forClass(AuditStatus.class);

        verify(auditLogService, times(1))
                .finalizeCollection(auditIdCaptor.capture(),
                        coverageCaptor.capture(),
                        statusCaptor.capture());

        assertThat(auditIdCaptor.getValue()).isEqualTo("audit-finalize");
        assertThat(coverageCaptor.getValue()).isEqualTo(1.0);
        assertThat(statusCaptor.getValue()).isEqualTo(AuditStatus.SUCCESS);
    }

    @Test
    @DisplayName("非只读工具（WRITE/EXECUTE/ADMIN）不得被采集")
    void nonReadOnlyToolsAreFilteredOut() {
        List<ToolDefinition> mixedTools = new ArrayList<>(readOnlyTools);

        ToolDefinition writeTool = new ToolDefinition();
        writeTool.setToolName("safe_temp_clean");
        writeTool.setRiskLevel(RiskLevel.L2);
        writeTool.setPermissionType(PermissionType.WRITE);
        writeTool.setToolStatus(ToolStatus.ENABLED);
        mixedTools.add(writeTool);

        ToolDefinition execTool = new ToolDefinition();
        execTool.setToolName("safe_service_restart");
        execTool.setRiskLevel(RiskLevel.L2);
        execTool.setPermissionType(PermissionType.EXECUTE);
        execTool.setToolStatus(ToolStatus.ENABLED);
        mixedTools.add(execTool);

        // L3 read-only（远超 L1 上限）应被过滤
        ToolDefinition l3Read = new ToolDefinition();
        l3Read.setToolName("secret_reader");
        l3Read.setRiskLevel(RiskLevel.L3);
        l3Read.setPermissionType(PermissionType.READ);
        l3Read.setToolStatus(ToolStatus.ENABLED);
        mixedTools.add(l3Read);

        when(toolRegistry.getEnabledToolDefinitions()).thenReturn(mixedTools);

        AuditLog auditLog = new AuditLog();
        auditLog.setAuditId("audit-filtered");
        when(auditLogService.startCollection(any())).thenReturn(auditLog);

        when(toolExecutor.execute(anyString(), any(), anyString()))
                .thenAnswer(inv -> successResult(inv.getArgument(0), Map.of()));

        DashboardOverview overview = service.refresh();

        // 期望仅 5 个 L0/L1 + READ 工具进入采集
        assertThat(overview.getTotalMetricCount()).isEqualTo(5);
        assertThat(overview.getSuccessfulMetricCount()).isEqualTo(5);

        // 验证从未调用 write/exec/L3 工具
        verify(toolExecutor, never()).execute(eq("safe_temp_clean"), any(), anyString());
        verify(toolExecutor, never()).execute(eq("safe_service_restart"), any(), anyString());
        verify(toolExecutor, never()).execute(eq("secret_reader"), any(), anyString());
    }

    @Test
    @DisplayName("采集未注册工具抛异常时，整体仍 HTTP 200 级别且该指标失败")
    void registryExceptionBecomesFailedMetric() {
        when(toolRegistry.getEnabledToolDefinitions()).thenReturn(readOnlyTools);

        AuditLog auditLog = new AuditLog();
        auditLog.setAuditId("audit-exc");
        when(auditLogService.startCollection(any())).thenReturn(auditLog);

        // system_info 模拟抛异常；其余成功
        when(toolExecutor.execute(eq("system_info_tool"), any(), anyString()))
                .thenThrow(new RuntimeException("simulated tool executor failure"));
        when(toolExecutor.execute(eq("cpu_status_tool"), any(), anyString()))
                .thenReturn(successResult("cpu_status_tool", Map.of()));
        when(toolExecutor.execute(eq("memory_status_tool"), any(), anyString()))
                .thenReturn(successResult("memory_status_tool", Map.of()));
        when(toolExecutor.execute(eq("disk_usage_tool"), any(), anyString()))
                .thenReturn(successResult("disk_usage_tool", Map.of()));
        when(toolExecutor.execute(eq("network_port_tool"), any(), anyString()))
                .thenReturn(successResult("network_port_tool", Map.of()));

        DashboardOverview overview = service.refresh();

        assertThat(overview.isDegraded()).isTrue();
        assertThat(overview.getSuccessfulMetricCount()).isEqualTo(4);
        DashboardMetric sys = overview.getMetrics().stream()
                .filter(m -> "system_info_tool".equals(m.getToolName()))
                .findFirst().orElseThrow();
        assertThat(sys.getStatus()).isEqualTo("failed");
        assertThat(sys.getErrorMessage()).contains("simulated tool executor failure");
    }

    @Test
    @DisplayName("refresh 不应在采集前漏掉 audit 创建（startCollection 必须先调用）")
    void startCollectionCalledBeforeAnyToolExecution() {
        when(toolRegistry.getEnabledToolDefinitions()).thenReturn(readOnlyTools);

        AuditLog auditLog = new AuditLog();
        auditLog.setAuditId("audit-order");
        when(auditLogService.startCollection(any())).thenReturn(auditLog);

        when(toolExecutor.execute(anyString(), any(), anyString()))
                .thenAnswer(inv -> successResult(inv.getArgument(0), Map.of()));

        service.refresh();

        // startCollection 与 5 次 ToolExecutor.execute 都至少各发生 1 次
        verify(auditLogService, times(1)).startCollection(any());
        verify(toolExecutor, times(5)).execute(anyString(), any(), anyString());
        verify(auditLogService, times(1))
                .finalizeCollection(anyString(), any(Double.class), any(AuditStatus.class));
    }
}