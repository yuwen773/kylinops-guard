package com.kylinops.inspection;

import com.kylinops.common.enums.RiskDecision;
import com.kylinops.common.enums.RiskLevel;
import com.kylinops.notification.NotificationEvent;
import com.kylinops.notification.NotificationEventFactory;
import com.kylinops.notification.NotificationEventType;
import com.kylinops.notification.NotificationSeverity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Inspection × NotificationEventFactory 单元测试（P1-02 Plan 02 — Task 4）。
 *
 * <p>验证 {@link NotificationEventFactory#createInspectionEvent} 对新增的
 * {@link NotificationEventType#INSPECTION_COMPLETED} / {@link NotificationEventType#INSPECTION_ABNORMAL}
 * / {@link NotificationEventType#INSPECTION_FAILED} 三个枚举值的产出事件形态。</p>
 *
 * <p>关键约束:不修改任何 Channel 实现,事件 detail map 必须含
 * {@code planName / templateType / status / summary / auditId / reportId}。</p>
 */
@DisplayName("Inspection × NotificationEventFactory — 巡检事件工厂")
class InspectionNotificationFactoryTest {

    private final NotificationEventFactory factory = new NotificationEventFactory();

    @Test
    @DisplayName("createInspectionEvent INSPECTION_COMPLETED → 字段全透出")
    void inspectionCompletedPopulatesDetailMap() {
        NotificationEvent e = factory.createInspectionEvent(
                NotificationEventType.INSPECTION_COMPLETED,
                "audit-ins-1",
                "每日健康检查",
                "HEALTH",
                "SUCCESS",
                "系统运行正常",
                "report-1");

        assertThat(e).isNotNull();
        assertThat(e.getEventType()).isEqualTo(NotificationEventType.INSPECTION_COMPLETED);
        assertThat(e.getAuditId()).isEqualTo("audit-ins-1");
        assertThat(e.getSeverity()).isEqualTo(NotificationSeverity.INFO);
        assertThat(e.getRiskLevel()).isEqualTo(RiskLevel.L0);
        assertThat(e.getRiskDecision()).isEqualTo(RiskDecision.ALLOW);

        Map<String, Object> detail = e.getDetailMap();
        assertThat(detail)
                .as("detail map 必须含全部 6 个键")
                .containsEntry("planName", "每日健康检查")
                .containsEntry("templateType", "HEALTH")
                .containsEntry("status", "SUCCESS")
                .containsEntry("summary", "系统运行正常")
                .containsEntry("auditId", "audit-ins-1")
                .containsEntry("reportId", "report-1");
    }

    @Test
    @DisplayName("INSPECTION_ABNORMAL → abnormal=true, severity=WARNING")
    void inspectionAbnormalFlagsAbnormalTrue() {
        NotificationEvent e = factory.createInspectionEvent(
                NotificationEventType.INSPECTION_ABNORMAL,
                "audit-ins-2",
                "磁盘每日巡检",
                "DISK",
                "PARTIAL_SUCCESS",
                "磁盘使用率 92%",
                null);

        assertThat(e.getEventType()).isEqualTo(NotificationEventType.INSPECTION_ABNORMAL);
        assertThat(e.getSeverity()).isEqualTo(NotificationSeverity.WARNING);

        Map<String, Object> detail = e.getDetailMap();
        assertThat(detail)
                .containsEntry("planName", "磁盘每日巡检")
                .containsEntry("templateType", "DISK")
                .containsEntry("status", "PARTIAL_SUCCESS")
                .containsEntry("summary", "磁盘使用率 92%")
                .containsEntry("auditId", "audit-ins-2")
                .containsEntry("abnormal", true);
        // reportId 可空
        assertThat(detail).containsKey("reportId");
        assertThat(detail.get("reportId")).isNull();
    }

    @Test
    @DisplayName("INSPECTION_FAILED → status=FAILED, severity=CRITICAL")
    void inspectionFailedUsesCriticalSeverityAndFailedStatus() {
        NotificationEvent e = factory.createInspectionEvent(
                NotificationEventType.INSPECTION_FAILED,
                "audit-ins-3",
                "服务巡检",
                "SERVICE",
                "FAILED",
                "服务状态获取失败",
                null);

        assertThat(e.getEventType()).isEqualTo(NotificationEventType.INSPECTION_FAILED);
        assertThat(e.getSeverity()).isEqualTo(NotificationSeverity.CRITICAL);

        Map<String, Object> detail = e.getDetailMap();
        assertThat(detail)
                .containsEntry("planName", "服务巡检")
                .containsEntry("templateType", "SERVICE")
                .containsEntry("status", "FAILED")
                .containsEntry("summary", "服务状态获取失败")
                .containsEntry("auditId", "audit-ins-3")
                .containsEntry("abnormal", false);
    }

    @Test
    @DisplayName("summary 超过 500 字符应被截断")
    void longSummaryIsTruncated() {
        String longSummary = "x".repeat(800);
        NotificationEvent e = factory.createInspectionEvent(
                NotificationEventType.INSPECTION_COMPLETED,
                "audit-long",
                "plan",
                "HEALTH",
                "SUCCESS",
                longSummary,
                null);

        assertThat((String) e.getDetailMap().get("summary"))
                .as("summary 必须按现有 NotificationPayloadSanitizer 契约截断")
                .isNotNull()
                .hasSizeLessThanOrEqualTo(503); // 500 + "..."
    }
}