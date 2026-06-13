package com.kylinops.report;

import com.kylinops.audit.AuditLogDetail;
import com.kylinops.audit.AuditLogRepository;
import com.kylinops.audit.AuditLogService;
import com.kylinops.common.enums.AuditStatus;
import com.kylinops.common.enums.IntentType;
import com.kylinops.common.enums.RiskDecision;
import com.kylinops.common.enums.RiskLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReportService 单元测试。
 * <p>
 * 验证：
 * <ul>
 *   <li>基于 auditId 生成报告，bodyMarkdown 仅由 AuditLogDetail 字段组装</li>
 *   <li>基于 sessionId + reportType 解析该会话最新 audit（按 createdAt DESC）</li>
 *   <li>auditId 和 sessionId 同时缺失 → 抛 IllegalArgumentException</li>
 *   <li>缺失的源字段（工具输出/风险决策/执行结果）显式写"数据不可用"</li>
 *   <li>绝不调用 LLM（mock 验证）</li>
 *   <li>Latest audit 选取确定（取 createdAt 最大）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReportService — 可追溯 Markdown 报告")
class ReportServiceTest {

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private ReportRepository reportRepository;

    private ReportService service;

    @BeforeEach
    void setUp() {
        service = new ReportService(auditLogService, auditLogRepository, reportRepository);
    }

    // ------------------- helpers -------------------

    private void stubReportPersistence() {
        when(reportRepository.save(any(Report.class))).thenAnswer(invocation -> {
            Report report = invocation.getArgument(0);
            report.onCreate();
            return report;
        });
    }

    private AuditLogDetail fullDetail(String auditId, String sessionId) {
        return AuditLogDetail.builder()
                .auditId(auditId)
                .sessionId(sessionId)
                .userInput("检查系统状态")
                .intentType(IntentType.SYSTEM_CHECK)
                .riskLevel(RiskLevel.L0)
                .riskDecision(RiskDecision.ALLOW)
                .status(AuditStatus.SUCCESS)
                .message("ok")
                .matchedRules("[]")
                .actionPlan("[]")
                .confirmationRequired(false)
                .confirmationStatus(null)
                .executionResult("{\"restarted\":\"nginx\"}")
                .finalAnswer("系统健康")
                .warning(null)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .toolCalls(List.of(
                        AuditLogDetail.ToolCallInfo.builder()
                                .toolCallId("tc-1")
                                .toolName("cpu_status_tool")
                                .status("SUCCESS")
                                .input(null)
                                .output("{\"usagePercent\":30}")
                                .errorMessage(null)
                                .durationMs(50L)
                                .build()
                ))
                .riskChecks(List.of(
                        AuditLogDetail.RiskCheckInfo.builder()
                                .riskCheckId("rc-1")
                                .targetType("tool")
                                .riskLevel("L0")
                                .riskDecision("ALLOW")
                                .matchedRules("[]")
                                .reason("L0 信息查询")
                                .checkedAt(LocalDateTime.now())
                                .build()
                ))
                .pendingAction(null)
                .build();
    }

    private AuditLogDetail sparseDetail(String auditId) {
        // Missing tool output, risk decision, execution result — to assert "数据不可用" fallback.
        return AuditLogDetail.builder()
                .auditId(auditId)
                .sessionId("session-sparse")
                .userInput(null)
                .intentType(null)
                .riskLevel(null)
                .riskDecision(null)
                .status(AuditStatus.RECEIVED)
                .message(null)
                .matchedRules(null)
                .actionPlan(null)
                .confirmationRequired(false)
                .confirmationStatus(null)
                .executionResult(null)
                .finalAnswer(null)
                .warning(null)
                .toolCalls(List.of())
                .riskChecks(List.of())
                .pendingAction(null)
                .build();
    }

    // ------------------- tests -------------------

    @Test
    @DisplayName("基于 auditId 生成报告 → 返回 ReportDetail，body 含源 auditId 链接")
    void generateByAuditIdReturnsReportWithSourceLink() {
        String auditId = UUID.randomUUID().toString();
        AuditLogDetail detail = fullDetail(auditId, "session-1");

        when(auditLogService.getDetail(auditId)).thenReturn(Optional.of(detail));
        stubReportPersistence();

        ReportGenerateRequest req = ReportGenerateRequest.builder()
                .auditId(auditId)
                .reportType(ReportType.HEALTH)
                .build();

        ReportDetail result = service.generate(req);

        assertThat(result).isNotNull();
        assertThat(result.getReportId()).isNotBlank();
        assertThat(result.getTitle()).contains("系统健康");
        assertThat(result.getAuditId()).isEqualTo(auditId);
        assertThat(result.getSessionId()).isEqualTo("session-1");
        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.L0);
        assertThat(result.getBodyMarkdown())
                .contains(auditId)
                .contains("# 用户请求")
                .contains("# 意图")
                .contains("# 工具调用")
                .contains("cpu_status_tool")
                .contains("# 风险检查")
                .contains("# 最终答复");
    }

    @Test
    @DisplayName("基于 sessionId + reportType → 取该会话 createdAt 最大的 audit 生成报告")
    void generateBySessionIdPicksLatestAudit() {
        String sessionId = "session-latest";
        String olderAudit = UUID.randomUUID().toString();
        String newerAudit = UUID.randomUUID().toString();

        LocalDateTime older = LocalDateTime.now().minusMinutes(10);
        LocalDateTime newer = LocalDateTime.now();

        com.kylinops.audit.AuditLog olderLog = new com.kylinops.audit.AuditLog();
        olderLog.setAuditId(olderAudit);
        olderLog.setSessionId(sessionId);
        olderLog.setCreatedAt(older);

        com.kylinops.audit.AuditLog newerLog = new com.kylinops.audit.AuditLog();
        newerLog.setAuditId(newerAudit);
        newerLog.setSessionId(sessionId);
        newerLog.setCreatedAt(newer);

        // Repo returns DESC by createdAt — service must respect that order.
        when(auditLogRepository.findBySessionIdOrderByCreatedAtDesc(eq(sessionId)))
                .thenReturn(List.of(newerLog, olderLog));
        when(auditLogService.getDetail(newerAudit))
                .thenReturn(Optional.of(fullDetail(newerAudit, sessionId)));
        stubReportPersistence();

        ReportGenerateRequest req = ReportGenerateRequest.builder()
                .sessionId(sessionId)
                .reportType(ReportType.DISK)
                .build();

        ReportDetail result = service.generate(req);

        assertThat(result.getAuditId()).isEqualTo(newerAudit);
        verify(auditLogService, never()).getDetail(olderAudit);
    }

    @Test
    @DisplayName("sessionId 无对应 audit → 抛 IllegalArgumentException")
    void generateBySessionIdNoAuditThrows() {
        String sessionId = "session-empty";
        when(auditLogRepository.findBySessionIdOrderByCreatedAtDesc(eq(sessionId)))
                .thenReturn(List.of());

        ReportGenerateRequest req = ReportGenerateRequest.builder()
                .sessionId(sessionId)
                .reportType(ReportType.SERVICE)
                .build();

        assertThatThrownBy(() -> service.generate(req))
                .isInstanceOf(IllegalArgumentException.class);
        verify(auditLogService, never()).getDetail(any());
        verify(reportRepository, never()).save(any());
    }

    @Test
    @DisplayName("auditId 与 sessionId 同时缺失 → 抛 IllegalArgumentException")
    void generateWithoutSourceThrows() {
        ReportGenerateRequest req = ReportGenerateRequest.builder()
                .reportType(ReportType.AUDIT)
                .build();

        assertThatThrownBy(() -> service.generate(req))
                .isInstanceOf(IllegalArgumentException.class);

        verify(auditLogService, never()).getDetail(any());
        verify(reportRepository, never()).save(any());
    }

    @Test
    @DisplayName("auditId 在 AuditLogService 中查不到 → 抛 IllegalArgumentException")
    void generateByMissingAuditIdThrows() {
        String auditId = "missing-audit";
        when(auditLogService.getDetail(auditId)).thenReturn(Optional.empty());

        ReportGenerateRequest req = ReportGenerateRequest.builder()
                .auditId(auditId)
                .reportType(ReportType.SECURITY)
                .build();

        assertThatThrownBy(() -> service.generate(req))
                .isInstanceOf(IllegalArgumentException.class);
        verify(reportRepository, never()).save(any());
    }

    @Test
    @DisplayName("缺失的源字段（userInput/tool output/risk/execution）→ Markdown 含“数据不可用”")
    void missingFieldsRenderAsUnavailable() {
        String auditId = UUID.randomUUID().toString();
        when(auditLogService.getDetail(auditId)).thenReturn(Optional.of(sparseDetail(auditId)));
        stubReportPersistence();

        ReportGenerateRequest req = ReportGenerateRequest.builder()
                .auditId(auditId)
                .reportType(ReportType.AUDIT)
                .build();

        ReportDetail result = service.generate(req);

        assertThat(result.getBodyMarkdown())
                .contains("数据不可用")
                .contains("# 用户请求")
                .contains("# 意图")
                .contains("# 风险检查")
                .contains("# 执行")
                .contains("# 最终答复");
    }

    @Test
    @DisplayName("ReportService 构造时不接受任何 LLM 依赖，生成路径绝不引用 LLM")
    void noLlmDependencyInService() {
        // Reflection-based smoke check: the service's declared fields must NOT contain
        // any LLM-related type. This guards the hard rule "报告绝不调用 LLM 补事实".
        java.lang.reflect.Field[] fields = ReportService.class.getDeclaredFields();
        for (java.lang.reflect.Field field : fields) {
            assertThat(field.getType().getName().toLowerCase())
                    .doesNotContain("llm")
                    .doesNotContain("chatclient")
                    .doesNotContain("openai");
        }
    }

    @Test
    @DisplayName("持久化的 Report 实体包含全部规定字段")
    void persistedReportHasAllMandatoryFields() {
        String auditId = UUID.randomUUID().toString();
        when(auditLogService.getDetail(auditId)).thenReturn(Optional.of(fullDetail(auditId, "session-x")));
        stubReportPersistence();

        ReportGenerateRequest req = ReportGenerateRequest.builder()
                .auditId(auditId)
                .reportType(ReportType.SERVICE)
                .build();

        service.generate(req);

        ArgumentCaptor<Report> captor = ArgumentCaptor.forClass(Report.class);
        verify(reportRepository).save(captor.capture());
        Report saved = captor.getValue();

        assertThat(saved.getReportId()).isNotBlank();
        assertThat(saved.getReportType()).isEqualTo(ReportType.SERVICE);
        assertThat(saved.getTitle()).isNotBlank();
        assertThat(saved.getSessionId()).isEqualTo("session-x");
        assertThat(saved.getAuditId()).isEqualTo(auditId);
        assertThat(saved.getRiskLevel()).isEqualTo(RiskLevel.L0);
        assertThat(saved.getBodyMarkdown()).isNotBlank();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("分页查询 → 边界 clamp 生效")
    void listReportsClampsSize() {
        when(reportRepository.findAll(any(org.springframework.data.domain.Pageable.class)))
                .thenAnswer(inv -> {
                    org.springframework.data.domain.Pageable p = inv.getArgument(0);
                    assertThat(p.getPageSize()).isBetween(1, 100);
                    return org.springframework.data.domain.Page.empty(p);
                });

        service.list(0, 99999);
        service.list(0, 0);
        service.list(0, -5);
        service.list(0, 20);
    }

    @Test
    @DisplayName("按 reportId 查询详情 → 未找到抛 IllegalArgumentException")
    void getDetailByReportIdMissingThrows() {
        String reportId = UUID.randomUUID().toString();
        when(reportRepository.findByReportId(reportId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDetail(reportId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("按 reportId 查询详情 → 命中返回 ReportDetail 含 bodyMarkdown")
    void getDetailByReportIdHit() {
        String reportId = UUID.randomUUID().toString();
        Report entity = new Report();
        entity.setReportId(reportId);
        entity.setReportType(ReportType.HEALTH);
        entity.setTitle("健康巡检报告");
        entity.setSessionId("session-9");
        entity.setAuditId("audit-9");
        entity.setRiskLevel(RiskLevel.L0);
        entity.setBodyMarkdown("# 报告\n");
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());

        when(reportRepository.findByReportId(reportId)).thenReturn(Optional.of(entity));

        ReportDetail detail = service.getDetail(reportId);
        assertThat(detail.getReportId()).isEqualTo(reportId);
        assertThat(detail.getBodyMarkdown()).contains("# 报告");
    }
}
