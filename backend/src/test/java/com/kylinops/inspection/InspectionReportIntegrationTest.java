package com.kylinops.inspection;

import com.kylinops.audit.AuditLogService;
import com.kylinops.common.enums.AuditStatus;
import com.kylinops.common.enums.IntentType;
import com.kylinops.inspection.model.InspectionTemplateType;
import com.kylinops.report.ReportDetail;
import com.kylinops.report.ReportService;
import com.kylinops.report.ReportSummary;
import com.kylinops.report.ReportType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Inspection × ReportService 集成测试（P1-02 Plan 02 — Task 4）。
 *
 * <p>验证巡检审计可以无 chat Session 地生成报告:</p>
 * <ul>
 *   <li>{@code ReportService.generateFromInspectionAudit(auditId, template)} 在仅有 auditId
 *       （无 sessionId, 无 chat Session）的情况下能产出报告</li>
 *   <li>InspectionTemplateType → ReportType 映射:HEALTH / DISK / SERVICE</li>
 *   <li>报告生成失败时按降级规则返回 {@code null} reportId, 不向上抛异常</li>
 * </ul>
 *
 * <p>对应设计文档 §5.4 "审计与报告闭环"。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Inspection × ReportService — 巡检报告无 Session 生成")
class InspectionReportIntegrationTest {

    @Autowired
    private ReportService reportService;

    @Autowired
    private AuditLogService auditLogService;

    @Test
    @DisplayName("HEALTH 模板 — generateFromInspectionAudit 不依赖 chat Session")
    void healthTemplateGeneratesReportFromInspectionAudit() {
        String auditId = UUID.randomUUID().toString();
        auditLogService.createInspectionAudit(
                auditId,
                IntentType.SYSTEM_CHECK,
                "SCHEDULED",
                "SYSTEM_SCHEDULER",
                "健康检查巡检执行");
        auditLogService.markCompleted(auditId);

        String reportId = reportService.generateFromInspectionAudit(
                auditId, InspectionTemplateType.HEALTH);

        assertThat(reportId)
                .as("HEALTH 模板应返回非空 reportId")
                .isNotNull()
                .isNotBlank();

        ReportDetail detail = reportService.getDetail(reportId);
        assertThat(detail).isNotNull();
        assertThat(detail.getAuditId()).isEqualTo(auditId);
        assertThat(detail.getSessionId())
                .as("巡检路径报告 sessionId 应为空")
                .isNull();
        assertThat(detail.getReportType()).isEqualTo(ReportType.HEALTH);
        assertThat(detail.getTitle()).contains("系统健康检查");
    }

    @Test
    @DisplayName("DISK 模板 → 报告类型为 DISK")
    void diskTemplateMapsToDiskReportType() {
        String auditId = UUID.randomUUID().toString();
        auditLogService.createInspectionAudit(
                auditId,
                IntentType.DISK_DIAGNOSIS,
                "MANUAL",
                "admin",
                "磁盘巡检");
        auditLogService.markCompleted(auditId);

        String reportId = reportService.generateFromInspectionAudit(
                auditId, InspectionTemplateType.DISK);

        assertThat(reportId).isNotBlank();
        ReportDetail detail = reportService.getDetail(reportId);
        assertThat(detail.getReportType()).isEqualTo(ReportType.DISK);
        assertThat(detail.getTitle()).contains("磁盘诊断");
    }

    @Test
    @DisplayName("SERVICE 模板 → 报告类型为 SERVICE")
    void serviceTemplateMapsToServiceReportType() {
        String auditId = UUID.randomUUID().toString();
        auditLogService.createInspectionAudit(
                auditId,
                IntentType.SERVICE_DIAGNOSIS,
                "SCHEDULED",
                "SYSTEM_SCHEDULER",
                "服务巡检");
        auditLogService.markCompleted(auditId);

        String reportId = reportService.generateFromInspectionAudit(
                auditId, InspectionTemplateType.SERVICE);

        assertThat(reportId).isNotBlank();
        ReportDetail detail = reportService.getDetail(reportId);
        assertThat(detail.getReportType()).isEqualTo(ReportType.SERVICE);
        assertThat(detail.getTitle()).contains("服务诊断");
    }

    @Test
    @DisplayName("报告生成失败 → 不抛异常, 返回 null reportId")
    void reportGenerationFailureReturnsNullWithoutThrowing() {
        // 审计根本不存在 → 不抛异常,降级为 null
        String nonexistentAuditId = UUID.randomUUID().toString();

        String direct = reportService.generateFromInspectionAudit(
                nonexistentAuditId, InspectionTemplateType.HEALTH);
        assertThat(direct)
                .as("审计不存在时应返回 null reportId, 不抛异常")
                .isNull();
    }

    @Test
    @DisplayName("巡检报告出现在 list() 中且 auditId 关联正确")
    void inspectionReportShowsUpInListWithAuditLink() {
        String auditId = UUID.randomUUID().toString();
        auditLogService.createInspectionAudit(
                auditId,
                IntentType.SYSTEM_CHECK,
                "SCHEDULED",
                "SYSTEM_SCHEDULER",
                "list 测试");
        auditLogService.markCompleted(auditId);

        String reportId = reportService.generateFromInspectionAudit(
                auditId, InspectionTemplateType.HEALTH);
        assertThat(reportId).isNotBlank();

        ReportSummary found = reportService.list(0, 100).stream()
                .filter(s -> reportId.equals(s.getReportId()))
                .findFirst()
                .orElseThrow();
        assertThat(found.getAuditId()).isEqualTo(auditId);
        assertThat(found.getReportType()).isEqualTo(ReportType.HEALTH);
    }

    @Test
    @DisplayName("未显式 markCompleted 的 RUNNING 审计仍可生成报告(降级路径允许)")
    void runningAuditStillAllowsReportGeneration() {
        String auditId = UUID.randomUUID().toString();
        auditLogService.createInspectionAudit(
                auditId,
                IntentType.SYSTEM_CHECK,
                "SCHEDULED",
                "SYSTEM_SCHEDULER",
                "未收尾的巡检审计");
        // 注意:不调用 markCompleted,模拟 Task 5 提前失败的场景
        assertThat(auditLogService.findByAuditId(auditId).get().getStatus())
                .isEqualTo(AuditStatus.RECEIVED);

        String reportId = reportService.generateFromInspectionAudit(
                auditId, InspectionTemplateType.HEALTH);
        assertThat(reportId).isNotBlank();
    }
}