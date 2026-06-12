package com.kylinops.dashboard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DashboardController 单元测试 — 验证 API 入口与 ApiResponse 包装。
 */
@WebMvcTest(DashboardController.class)
@DisplayName("DashboardController — 系统概览 API")
class DashboardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DashboardService dashboardService;

    @Test
    @DisplayName("GET /api/dashboard/overview → 200 + 完整字段")
    void getOverviewSuccess() throws Exception {
        DashboardMetric metric = DashboardMetric.builder()
                .toolName("system_info_tool")
                .status("success")
                .data(java.util.Map.of("hostname", "demo"))
                .durationMs(15L)
                .build();

        DashboardOverview overview = DashboardOverview.builder()
                .score(85)
                .successfulMetricCount(4)
                .totalMetricCount(5)
                .degraded(true)
                .auditId("audit-ctrl-1")
                .collectedAt(Instant.parse("2026-06-12T00:00:00Z"))
                .metrics(List.of(metric))
                .build();

        when(dashboardService.refresh()).thenReturn(overview);

        mockMvc.perform(get("/api/dashboard/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.score").value(85))
                .andExpect(jsonPath("$.data.successfulMetricCount").value(4))
                .andExpect(jsonPath("$.data.totalMetricCount").value(5))
                .andExpect(jsonPath("$.data.degraded").value(true))
                .andExpect(jsonPath("$.data.auditId").value("audit-ctrl-1"))
                .andExpect(jsonPath("$.data.metrics[0].toolName").value("system_info_tool"))
                .andExpect(jsonPath("$.data.metrics[0].status").value("success"));
    }

    @Test
    @DisplayName("GET /api/dashboard/overview → 所有工具失败时 score=null 仍 HTTP 200")
    void getOverviewAllFailedStillReturns200() throws Exception {
        DashboardOverview overview = DashboardOverview.builder()
                .score(null)
                .successfulMetricCount(0)
                .totalMetricCount(3)
                .degraded(true)
                .auditId("audit-ctrl-2")
                .collectedAt(Instant.now())
                .metrics(List.of(
                        DashboardMetric.builder()
                                .toolName("cpu_status_tool")
                                .status("failed")
                                .errorMessage("cpu probe failed")
                                .durationMs(5L)
                                .build()))
                .build();

        when(dashboardService.refresh()).thenReturn(overview);

        mockMvc.perform(get("/api/dashboard/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.successfulMetricCount").value(0))
                .andExpect(jsonPath("$.data.totalMetricCount").value(3))
                .andExpect(jsonPath("$.data.degraded").value(true))
                .andExpect(jsonPath("$.data.score").doesNotExist())
                .andExpect(jsonPath("$.data.metrics[0].status").value("failed"))
                .andExpect(jsonPath("$.data.metrics[0].errorMessage").value("cpu probe failed"));
    }
}