package com.kylinops.report;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ReportController 单元测试。
 * <p>
 * 覆盖：
 * <ul>
 *   <li>POST /api/reports/generate 成功 → 200 + bodyMarkdown</li>
 *   <li>POST /api/reports/generate 同时缺 auditId 与 sessionId → 400</li>
 *   <li>GET /api/reports 分页 → 200</li>
 *   <li>GET /api/reports/{reportId} 命中 → 200；未命中 → 404</li>
 * </ul>
 */
@WebMvcTest(ReportController.class)
@DisplayName("ReportController — 报告 API")
class ReportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ReportService reportService;

    @Test
    @DisplayName("POST /api/reports/generate → 200 + ReportDetail")
    void generateReportSuccess() throws Exception {
        String auditId = UUID.randomUUID().toString();
        ReportDetail detail = ReportDetail.builder()
                .reportId("rpt-1")
                .title("健康巡检报告")
                .reportType(ReportType.HEALTH)
                .riskLevel(com.kylinops.common.enums.RiskLevel.L0)
                .sessionId("session-1")
                .auditId(auditId)
                .bodyMarkdown("# 健康巡检报告\n数据不可用")
                .createdAt(LocalDateTime.now())
                .build();
        when(reportService.generate(any(ReportGenerateRequest.class))).thenReturn(detail);

        String body = "{\"auditId\":\"" + auditId + "\",\"reportType\":\"HEALTH\"}";

        mockMvc.perform(post("/api/reports/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.reportId").value("rpt-1"))
                .andExpect(jsonPath("$.data.auditId").value(auditId))
                .andExpect(jsonPath("$.data.bodyMarkdown").value(org.hamcrest.Matchers.containsString("健康巡检报告")));
    }

    @Test
    @DisplayName("POST /api/reports/generate 同时缺 auditId 与 sessionId → 400")
    void generateReportMissingSource() throws Exception {
        when(reportService.generate(any(ReportGenerateRequest.class)))
                .thenThrow(new IllegalArgumentException("必须提供 auditId 或 sessionId"));

        String body = "{\"reportType\":\"HEALTH\"}";

        mockMvc.perform(post("/api/reports/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("GET /api/reports?page=0&size=20 → 200 分页列表")
    void listReports() throws Exception {
        ReportSummary summary = ReportSummary.builder()
                .reportId("rpt-1")
                .title("健康巡检报告")
                .reportType(ReportType.HEALTH)
                .riskLevel(com.kylinops.common.enums.RiskLevel.L0)
                .sessionId("session-1")
                .auditId("audit-1")
                .createdAt(LocalDateTime.now())
                .build();
        Page<ReportSummary> page = new PageImpl<>(List.of(summary), PageRequest.of(0, 20), 1);
        when(reportService.list(anyInt(), anyInt())).thenReturn(page);

        mockMvc.perform(get("/api/reports").param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.content[0].reportId").value("rpt-1"))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    @DisplayName("GET /api/reports/{reportId} 命中 → 200")
    void getReportDetailHit() throws Exception {
        String reportId = UUID.randomUUID().toString();
        ReportDetail detail = ReportDetail.builder()
                .reportId(reportId)
                .title("磁盘诊断报告")
                .reportType(ReportType.DISK)
                .riskLevel(com.kylinops.common.enums.RiskLevel.L0)
                .sessionId("session-1")
                .auditId("audit-1")
                .bodyMarkdown("# 磁盘诊断")
                .createdAt(LocalDateTime.now())
                .build();
        when(reportService.getDetail(reportId)).thenReturn(detail);

        mockMvc.perform(get("/api/reports/{reportId}", reportId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.reportId").value(reportId));
    }

    @Test
    @DisplayName("GET /api/reports/{reportId} 未命中 → 404")
    void getReportDetailMiss() throws Exception {
        String reportId = UUID.randomUUID().toString();
        when(reportService.getDetail(reportId))
                .thenThrow(new IllegalArgumentException("报告不存在: " + reportId));

        mockMvc.perform(get("/api/reports/{reportId}", reportId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }
}