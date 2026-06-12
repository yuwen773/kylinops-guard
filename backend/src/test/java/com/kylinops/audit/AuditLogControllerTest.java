package com.kylinops.audit;

import com.kylinops.common.enums.AuditStatus;
import com.kylinops.common.enums.IntentType;
import com.kylinops.common.enums.RiskDecision;
import com.kylinops.common.enums.RiskLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AuditLogController 单元测试
 */
@WebMvcTest(AuditLogController.class)
@DisplayName("AuditLogController — 审计日志 API")
class AuditLogControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuditLogService auditLogService;

    @Test
    @DisplayName("GET /api/audit/logs → 返回分页列表")
    void listLogs() throws Exception {
        AuditLogSummary summary = AuditLogSummary.builder()
                .auditId(UUID.randomUUID().toString())
                .sessionId("session-1")
                .userInput("查看系统状态")
                .intentType(IntentType.SYSTEM_CHECK)
                .riskLevel(RiskLevel.L0)
                .riskDecision(RiskDecision.ALLOW)
                .status(AuditStatus.SUCCESS)
                .createdAt(LocalDateTime.now())
                .build();

        Page<AuditLogSummary> page = new PageImpl<>(
                List.of(summary), PageRequest.of(0, 20), 1);

        when(auditLogService.queryLogs(any(), any(), any(), any(), any(),
                anyInt(), anyInt())).thenReturn(page);

        mockMvc.perform(get("/api/audit/logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.content[0].auditId").isString())
                .andExpect(jsonPath("$.data.content[0].userInput").value("查看系统状态"))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    @DisplayName("GET /api/audit/logs?riskLevel=L4&status=BLOCKED → 筛选结果")
    void listLogsWithFilters() throws Exception {
        AuditLogSummary summary = AuditLogSummary.builder()
                .auditId(UUID.randomUUID().toString())
                .userInput("rm -rf /")
                .intentType(IntentType.COMMAND_EXECUTION)
                .riskLevel(RiskLevel.L4)
                .riskDecision(RiskDecision.BLOCK)
                .status(AuditStatus.BLOCKED)
                .createdAt(LocalDateTime.now())
                .build();

        Page<AuditLogSummary> page = new PageImpl<>(
                List.of(summary), PageRequest.of(0, 20), 1);

        when(auditLogService.queryLogs(eq(RiskLevel.L4), eq(AuditStatus.BLOCKED),
                any(), any(), any(), anyInt(), anyInt())).thenReturn(page);

        mockMvc.perform(get("/api/audit/logs")
                        .param("riskLevel", "L4")
                        .param("status", "BLOCKED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].riskLevel").value("L4"))
                .andExpect(jsonPath("$.data.content[0].status").value("BLOCKED"));
    }

    @Test
    @DisplayName("GET /api/audit/logs/{auditId} → 返回详情")
    void getLogDetail() throws Exception {
        String auditId = UUID.randomUUID().toString();
        AuditLogDetail detail = AuditLogDetail.builder()
                .auditId(auditId)
                .sessionId("session-1")
                .userInput("检查磁盘")
                .intentType(IntentType.DISK_DIAGNOSIS)
                .riskLevel(RiskLevel.L0)
                .riskDecision(RiskDecision.ALLOW)
                .status(AuditStatus.SUCCESS)
                .toolCalls(List.of())
                .riskChecks(List.of())
                .createdAt(LocalDateTime.now())
                .build();

        when(auditLogService.getDetail(auditId)).thenReturn(Optional.of(detail));

        mockMvc.perform(get("/api/audit/logs/{auditId}", auditId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.auditId").value(auditId))
                .andExpect(jsonPath("$.data.toolCalls").isArray());
    }

    @Test
    @DisplayName("GET /api/audit/logs/{auditId} → 不存在的 ID 返回 404")
    void getLogDetailNotFound() throws Exception {
        String auditId = "nonexistent-id";
        when(auditLogService.getDetail(auditId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/audit/logs/{auditId}", auditId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("GET /api/audit/logs → 每条 summary 含 toolCallCount 字段")
    void listLogsExposesToolCallCount() throws Exception {
        AuditLogSummary summary = AuditLogSummary.builder()
                .auditId(UUID.randomUUID().toString())
                .userInput("健康巡检")
                .riskLevel(RiskLevel.L0)
                .status(AuditStatus.SUCCESS)
                .toolCallCount(6L)
                .createdAt(LocalDateTime.now())
                .build();
        Page<AuditLogSummary> page = new PageImpl<>(List.of(summary), PageRequest.of(0, 20), 1);
        when(auditLogService.queryLogs(any(), any(), any(), any(), any(),
                anyInt(), anyInt())).thenReturn(page);

        mockMvc.perform(get("/api/audit/logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].toolCallCount").value(6));
    }
}
