package com.kylinops.tool;

import com.kylinops.common.enums.PermissionType;
import com.kylinops.common.enums.RiskLevel;
import com.kylinops.common.enums.ToolStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.time.Month;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ToolController 单元测试 — 验证工具目录 API 与调用统计聚合。
 * <p>
 * 关键约束（Task 11）：
 * <ul>
 *   <li>GET /api/tools 返回 ToolDefinitionVO 列表，新增统计字段
 *       callCount / successRate / lastCalledAt 默认存在</li>
 *   <li>一次 aggregate 查询（findStatsByToolNameIn）覆盖全部工具 — 禁止 N+1</li>
 *   <li>successRate = SUCCESS / terminal calls（SUCCESS + FAILED + TIMEOUT + BLOCKED），
 *       无 terminal calls 时返回 null（不是 0、不是 100）</li>
 *   <li>DTO 兼容原字段：toolName / riskLevel / permissionType / toolStatus 等不可重命名</li>
 * </ul>
 */
@WebMvcTest(ToolController.class)
@WithMockUser
@DisplayName("ToolController — 工具目录与调用统计")
class ToolControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ToolRegistry toolRegistry;

    @MockBean
    private ToolCallRecordRepository toolCallRecordRepository;

    @BeforeEach
    void setUp() {
        // 默认：10 个注册工具（≥ 8 OS 工具）
        List<ToolDefinition> defs = List.of(
                defOf("system_info_tool"),
                defOf("cpu_status_tool"),
                defOf("memory_status_tool"),
                defOf("disk_usage_tool"),
                defOf("large_file_scan_tool"),
                defOf("process_list_tool"),
                defOf("process_detail_tool"),
                defOf("network_port_tool"),
                defOf("service_status_tool"),
                defOf("journal_log_tool")
        );
        when(toolRegistry.getAllToolDefinitions()).thenReturn(defs);
        // 默认：没有任何工具产生过统计 — 验证 aggregate 入参被传入
        when(toolCallRecordRepository.findStatsByToolNameIn(any()))
                .thenReturn(List.of());
    }

    private ToolDefinition defOf(String name) {
        ToolDefinition d = new ToolDefinition();
        d.setToolName(name);
        d.setDescription(name + " description");
        d.setInputSchema("{\"type\":\"object\"}");
        d.setOutputSchema("{\"type\":\"object\"}");
        d.setRiskLevel(RiskLevel.L0);
        d.setPermissionType(PermissionType.READ);
        d.setToolStatus(ToolStatus.ENABLED);
        d.setTimeoutMs(3000L);
        d.setAuditRequired(false);
        return d;
    }

    /**
     * Build a ToolStatsProjection via Mockito (interface projection).
     * {@code terminalCount} is the sum of SUCCESS/FAILED/TIMEOUT/BLOCKED;
     * {@code successCount} is subset.
     */
    private ToolCallRecordRepository.ToolStatsProjection agg(
            String toolName, long callCount, long terminalCount, long successCount,
            LocalDateTime lastCalledAt) {
        ToolCallRecordRepository.ToolStatsProjection p =
                Mockito.mock(ToolCallRecordRepository.ToolStatsProjection.class);
        when(p.getToolName()).thenReturn(toolName);
        when(p.getCallCount()).thenReturn(callCount);
        when(p.getTerminalCount()).thenReturn(terminalCount);
        when(p.getSuccessCount()).thenReturn(successCount);
        when(p.getLastCalledAt()).thenReturn(lastCalledAt);
        return p;
    }

    @Test
    @DisplayName("GET /api/tools → 返回 10 个工具 DTO + 新增统计字段默认存在")
    void listReturnsAllToolsWithStatFields() throws Exception {
        MvcResult res = mockMvc.perform(get("/api/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data", org.hamcrest.Matchers.hasSize(greaterThanOrEqualTo(8))))
                .andExpect(jsonPath("$.data[0].toolName").exists())
                .andExpect(jsonPath("$.data[0].riskLevel").exists())
                .andExpect(jsonPath("$.data[0].permissionType").exists())
                .andExpect(jsonPath("$.data[0].toolStatus").exists())
                // 新增的统计字段必须存在，调用统计为 0 时也是显式 0
                .andExpect(jsonPath("$.data[0].callCount").value(0))
                // successRate / lastCalledAt 在 0 调用时为 null，不序列化
                .andExpect(jsonPath("$.data[0].successRate").doesNotExist())
                .andExpect(jsonPath("$.data[0].lastCalledAt").doesNotExist())
                .andReturn();

        String body = res.getResponse().getContentAsString();
        assertThat(body).contains("callCount");
        assertThat(body).contains("system_info_tool");
        assertThat(body).contains("cpu_status_tool");
        assertThat(body).contains("disk_usage_tool");
    }

    @Test
    @DisplayName("GET /api/tools → 调用 findStatsByToolNameIn 恰好 1 次（非 N+1）")
    @SuppressWarnings("unchecked")
    void listCallsAggregateOnce() throws Exception {
        mockMvc.perform(get("/api/tools"))
                .andExpect(status().isOk());

        verify(toolCallRecordRepository, times(1))
                .findStatsByToolNameIn(any(Collection.class));

        ArgumentCaptor<Collection> captor = ArgumentCaptor.forClass(Collection.class);
        verify(toolCallRecordRepository).findStatsByToolNameIn(captor.capture());
        Collection<String> captured = captor.getValue();
        assertThat(captured)
                .as("aggregate 入参应包含所有注册工具名")
                .contains("system_info_tool", "cpu_status_tool", "disk_usage_tool",
                        "service_status_tool");
    }

    @Test
    @DisplayName("无 terminal calls 的工具 → successRate=null 且 callCount=0")
    void zeroCallsToolsHaveNullSuccessRate() throws Exception {
        when(toolCallRecordRepository.findStatsByToolNameIn(any()))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].callCount").value(0))
                .andExpect(jsonPath("$.data[0].successRate").doesNotExist())
                .andExpect(jsonPath("$.data[0].lastCalledAt").doesNotExist());
    }

    @Test
    @DisplayName("部分成功 → successRate = SUCCESS / (SUCCESS+FAILED+TIMEOUT+BLOCKED)")
    void partialSuccessComputesSuccessRateCorrectly() throws Exception {
        // disk_usage_tool: 4 SUCCESS + 1 FAILED + 1 TIMEOUT + 0 BLOCKED = 6 terminal
        // successRate = 4/6 = 0.6666666666666666
        LocalDateTime last = LocalDateTime.of(2026, Month.JUNE, 12, 10, 0, 0);
        java.util.List<ToolCallRecordRepository.ToolStatsProjection> partialAggs = List.of(
                agg("disk_usage_tool", 6L, 6L, 4L, last),
                agg("cpu_status_tool", 3L, 3L, 3L, last)
        );
        when(toolCallRecordRepository.findStatsByToolNameIn(any()))
                .thenReturn(partialAggs);

        MvcResult res = mockMvc.perform(get("/api/tools"))
                .andExpect(status().isOk())
                .andReturn();

        String body = res.getResponse().getContentAsString();
        assertThat(body)
                .as("successRate 必须显式存在且约等于 0.6667")
                .contains("\"successRate\":0.6666666666666666");
        assertThat(body)
                .as("disk_usage_tool callCount=6")
                .contains("\"toolName\":\"disk_usage_tool\"")
                .contains("\"callCount\":6");
        // lastCalledAt 必须存在（ISO-8601 Instant 序列化为 Z 结尾）
        assertThat(body).containsPattern(java.util.regex.Pattern.compile(
                "\"lastCalledAt\":\"\\d{4}-\\d{2}-\\d{2}T.*Z\""));
    }

    @Test
    @DisplayName("仅 PENDING/RUNNING 的工具 → successRate=null")
    void onlyNonTerminalCallsYieldsNull() throws Exception {
        // callCount=5 表示全部是非 terminal（PENDING/RUNNING），
        // terminalCount=0 → successRate=null
        LocalDateTime last = LocalDateTime.of(2026, Month.JUNE, 12, 10, 0, 0);
        java.util.List<ToolCallRecordRepository.ToolStatsProjection> onlyNonTerminalAggs = List.of(
                agg("journal_log_tool", 5L, 0L, 0L, last)
        );
        when(toolCallRecordRepository.findStatsByToolNameIn(any()))
                .thenReturn(onlyNonTerminalAggs);

        mockMvc.perform(get("/api/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.data[?(@.toolName=='journal_log_tool')].callCount").value(5))
                .andExpect(jsonPath(
                        "$.data[?(@.toolName=='journal_log_tool')].successRate").doesNotExist());
    }

    @Test
    @DisplayName("aggregate 缺失的工具（未被记录过）→ 默认 callCount=0, successRate=null")
    void missingInAggregateDefaultsToZero() throws Exception {
        java.util.List<ToolCallRecordRepository.ToolStatsProjection> missingAggs = List.of(
                agg("cpu_status_tool", 2L, 2L, 2L,
                        LocalDateTime.of(2026, Month.JUNE, 12, 9, 0, 0))
        );
        when(toolCallRecordRepository.findStatsByToolNameIn(any()))
                .thenReturn(missingAggs);

        mockMvc.perform(get("/api/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.data[?(@.toolName=='system_info_tool')].callCount").value(0))
                .andExpect(jsonPath(
                        "$.data[?(@.toolName=='system_info_tool')].successRate").doesNotExist())
                .andExpect(jsonPath(
                        "$.data[?(@.toolName=='cpu_status_tool')].callCount").value(2))
                .andExpect(jsonPath(
                        "$.data[?(@.toolName=='cpu_status_tool')].successRate").value(1.0));
    }

    @Test
    @DisplayName("GET /api/tools → 不调用任何 ToolCallRecord 全表读取方法")
    void noFullTableLoad() throws Exception {
        mockMvc.perform(get("/api/tools"))
                .andExpect(status().isOk());

        verify(toolCallRecordRepository, never()).findAll();
        verify(toolCallRecordRepository, never()).findByToolNameOrderByCreatedAtDesc(any());
    }

    @Test
    @DisplayName("GET /api/tools/{toolName} → 200 + 完整统计字段")
    void getSingleToolReturnsStats() throws Exception {
        LocalDateTime last = LocalDateTime.of(2026, Month.JUNE, 12, 10, 0, 0);
        OpsTool opsTool = Mockito.mock(OpsTool.class);
        ToolDefinition def = defOf("cpu_status_tool");
        when(opsTool.definition()).thenReturn(def);
        when(toolRegistry.getTool("cpu_status_tool")).thenReturn(opsTool);
        java.util.List<ToolCallRecordRepository.ToolStatsProjection> singleAggs = List.of(agg("cpu_status_tool", 4L, 4L, 4L, last));
        when(toolCallRecordRepository.findStatsByToolNameIn(any()))
                .thenReturn(singleAggs);

        mockMvc.perform(get("/api/tools/cpu_status_tool"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.toolName").value("cpu_status_tool"))
                .andExpect(jsonPath("$.data.callCount").value(4))
                .andExpect(jsonPath("$.data.successRate").value(1.0))
                .andExpect(jsonPath("$.data.lastCalledAt").exists());
    }

    @Test
    @DisplayName("DTO 原字段兼容 — riskLevel/permissionType/toolStatus/inputSchema 全部保留")
    void existingFieldsPreserved() throws Exception {
        mockMvc.perform(get("/api/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].toolName").exists())
                .andExpect(jsonPath("$.data[0].description").exists())
                .andExpect(jsonPath("$.data[0].inputSchema").exists())
                .andExpect(jsonPath("$.data[0].outputSchema").exists())
                .andExpect(jsonPath("$.data[0].riskLevel").value("L0"))
                .andExpect(jsonPath("$.data[0].permissionType").value("READ"))
                .andExpect(jsonPath("$.data[0].toolStatus").value("ENABLED"))
                .andExpect(jsonPath("$.data[0].timeoutMs").exists())
                .andExpect(jsonPath("$.data[0].auditRequired").exists())
                .andExpect(jsonPath("$.data[0].callCount").exists())
                .andExpect(jsonPath("$.data[0].successRate").doesNotExist())
                .andExpect(jsonPath("$.data[0].lastCalledAt").doesNotExist());
    }
}