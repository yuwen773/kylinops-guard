package com.kylinops.inspection;

import com.kylinops.audit.AuditLog;
import com.kylinops.audit.AuditLogDetail;
import com.kylinops.audit.AuditLogRepository;
import com.kylinops.audit.AuditLogService;
import com.kylinops.common.enums.AuditStatus;
import com.kylinops.common.enums.IntentType;
import com.kylinops.common.enums.RiskDecision;
import com.kylinops.common.enums.RiskLevel;
import com.kylinops.executor.PendingActionRepository;
import com.kylinops.tool.ToolCallRecordRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Inspection × AuditLog 集成测试（P1-02 Plan 02 — Task 4）。
 *
 * <p>验证 {@link AuditLogService#createInspectionAudit} 写入巡检来源标记的审计行：</p>
 * <ul>
 *   <li>定时触发：{@code triggerType = "SCHEDULED"}, {@code operator = "SYSTEM_SCHEDULER"}</li>
 *   <li>手动触发：{@code operator} 记录当前管理员用户名, {@code triggerType = "MANUAL"}</li>
 *   <li>巡检审计严禁伪造 chat Session / Message / PendingAction</li>
 *   <li>同一 {@code auditId} 多事件（工具调用 / 风险检查）共享同一行, 不产生重复 audit</li>
 * </ul>
 *
 * <p>对应设计文档 §5.4 "审计与报告闭环"。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Inspection × AuditLog — 巡检来源标记写入审计")
class InspectionAuditIntegrationTest {

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private PendingActionRepository pendingActionRepository;

    @Autowired
    private ToolCallRecordRepository toolCallRecordRepository;

    @Test
    @DisplayName("定时触发 → triggerType=SCHEDULED, operator=SYSTEM_SCHEDULER, 无 Session")
    void scheduledTriggerWritesSchedulerMetadataAndNoSession() {
        String auditId = UUID.randomUUID().toString();

        AuditLog saved = auditLogService.createInspectionAudit(
                auditId,
                IntentType.SYSTEM_CHECK,
                "SCHEDULED",
                "SYSTEM_SCHEDULER",
                "健康检查计划执行");

        assertThat(saved).isNotNull();
        assertThat(saved.getAuditId()).isEqualTo(auditId);
        assertThat(saved.getTriggerType()).isEqualTo("SCHEDULED");
        assertThat(saved.getOperator()).isEqualTo("SYSTEM_SCHEDULER");

        // 从数据库回读验证持久化字段一致
        AuditLog reloaded = auditLogRepository.findByAuditId(auditId).orElseThrow();
        assertThat(reloaded.getTriggerType()).isEqualTo("SCHEDULED");
        assertThat(reloaded.getOperator()).isEqualTo("SYSTEM_SCHEDULER");
        assertThat(reloaded.getSessionId())
                .as("巡检审计绝不创建 chat Session")
                .isNull();
        assertThat(reloaded.isConfirmationRequired())
                .as("巡检路径不创建 PendingAction")
                .isFalse();
        assertThat(reloaded.getStatus())
                .as("巡检审计初始状态必须为 RECEIVED,与现有 chat 审计一致")
                .isEqualTo(AuditStatus.RECEIVED);

        // 巡检路径不创建任何 PendingAction
        assertThat(pendingActionRepository.findByAuditId(auditId))
                .as("巡检审计不应关联任何 PendingAction")
                .isEmpty();
    }

    @Test
    @DisplayName("手动触发 → operator 记录当前管理员用户名, triggerType=MANUAL")
    void manualTriggerRecordsAuthenticatedAdminOperator() {
        String auditId = UUID.randomUUID().toString();

        auditLogService.createInspectionAudit(
                auditId,
                IntentType.DISK_DIAGNOSIS,
                "MANUAL",
                "admin",
                "管理员手动触发磁盘诊断");

        AuditLog reloaded = auditLogRepository.findByAuditId(auditId).orElseThrow();
        assertThat(reloaded.getTriggerType()).isEqualTo("MANUAL");
        assertThat(reloaded.getOperator()).isEqualTo("admin");
        assertThat(reloaded.getSessionId()).isNull();
        assertThat(reloaded.isConfirmationRequired()).isFalse();
    }

    @Test
    @DisplayName("同 auditId 多事件共享同一行 — 工具调用与风险检查写入不创建重复 audit")
    void sameAuditIdIsSharedAcrossToolCallsAndRiskChecks() {
        String auditId = UUID.randomUUID().toString();

        // 1. 首次创建巡检审计
        auditLogService.createInspectionAudit(
                auditId,
                IntentType.SYSTEM_CHECK,
                "SCHEDULED",
                "SYSTEM_SCHEDULER",
                "巡检启动");

        // 2. 后续阶段多次 updateAuditLog（模拟工具调用 + 风险检查更新）
        auditLogService.updateAuditLog(auditId, RiskLevel.L0, RiskDecision.ALLOW,
                "cpu_status_tool", AuditStatus.RISK_CHECKED, "L0 信息查询");
        auditLogService.updateAuditLog(auditId, RiskLevel.L0, RiskDecision.ALLOW,
                "memory_status_tool", AuditStatus.SUCCESS, "执行完成");

        // 3. 数据库中只能存在一条该 auditId 的记录（按 auditId 唯一约束保证）
        assertThat(auditLogRepository.findByAuditId(auditId))
                .as("同一 auditId 不应产生重复行")
                .isPresent();

        AuditLog only = auditLogRepository.findByAuditId(auditId).get();
        assertThat(only.getStatus()).isEqualTo(AuditStatus.SUCCESS);
        assertThat(only.getToolName())
                .as("最后更新的 toolName 透传")
                .isEqualTo("memory_status_tool");
        assertThat(only.getTriggerType()).isEqualTo("SCHEDULED");
        assertThat(only.getOperator()).isEqualTo("SYSTEM_SCHEDULER");
        assertThat(only.getSessionId()).isNull();
    }

    @Test
    @DisplayName("getDetail 透出 triggerType 与 operator")
    void auditLogDetailExposesInspectionMetadata() {
        String auditId = UUID.randomUUID().toString();

        auditLogService.createInspectionAudit(
                auditId,
                IntentType.SERVICE_DIAGNOSIS,
                "MANUAL",
                "admin",
                "服务诊断");

        AuditLogDetail detail = auditLogService.getDetail(auditId).orElseThrow();
        assertThat(detail.getTriggerType()).isEqualTo("MANUAL");
        assertThat(detail.getOperator()).isEqualTo("admin");
        assertThat(detail.getSessionId()).isNull();
        assertThat(detail.isConfirmationRequired()).isFalse();
        assertThat(detail.getPendingAction())
                .as("巡检审计详情中 pendingAction 必须为 null")
                .isNull();
    }
}