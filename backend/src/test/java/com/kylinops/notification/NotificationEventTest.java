package com.kylinops.notification;

import com.kylinops.common.enums.IntentType;
import com.kylinops.common.enums.RiskDecision;
import com.kylinops.common.enums.RiskLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the mutable NotificationEvent DTO.
 *
 * <p>P1-01 Plan 01 — Task 1 (data models). Verifies the builder assembles all
 * spec §4.1 fields, getters return what the builder received, and the
 * setters used by {@code NotificationService.emit()} work as expected.</p>
 */
@DisplayName("P1-01 T1 — NotificationEvent DTO builder")
class NotificationEventTest {

    @Test
    @DisplayName("Builder assembles all spec fields and getters return them")
    void builderAssemblesAllFields() {
        String eventId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.of(2026, 6, 17, 10, 30, 0);

        NotificationEvent event = NotificationEvent.builder()
                .eventId(eventId)
                .eventType(NotificationEventType.L4_BLOCK)
                .severity(NotificationSeverity.CRITICAL)
                .title("危险命令被拦截")
                .summary("rm -rf / 被 L4 规则阻断")
                .detail("命中规则 rm_root_recursive")
                .sessionId("sess-001")
                .auditId("audit-001")
                .riskLevel(RiskLevel.L4)
                .riskDecision(RiskDecision.BLOCK)
                .intentType(IntentType.COMMAND_EXECUTION)
                .matchedRuleId("rm_root_recursive")
                .serviceName(null)
                .diskPath(null)
                .diskUsagePercent(null)
                .promptInjectionPattern(null)
                .rcaConfidence(null)
                .occurredAt(now)
                .build();

        assertThat(event.getEventId()).isEqualTo(eventId);
        assertThat(event.getEventType()).isEqualTo(NotificationEventType.L4_BLOCK);
        assertThat(event.getSeverity()).isEqualTo(NotificationSeverity.CRITICAL);
        assertThat(event.getTitle()).isEqualTo("危险命令被拦截");
        assertThat(event.getSummary()).isEqualTo("rm -rf / 被 L4 规则阻断");
        assertThat(event.getDetail()).isEqualTo("命中规则 rm_root_recursive");
        assertThat(event.getSessionId()).isEqualTo("sess-001");
        assertThat(event.getAuditId()).isEqualTo("audit-001");
        assertThat(event.getRiskLevel()).isEqualTo(RiskLevel.L4);
        assertThat(event.getRiskDecision()).isEqualTo(RiskDecision.BLOCK);
        assertThat(event.getIntentType()).isEqualTo(IntentType.COMMAND_EXECUTION);
        assertThat(event.getMatchedRuleId()).isEqualTo("rm_root_recursive");
        assertThat(event.getServiceName()).isNull();
        assertThat(event.getDiskPath()).isNull();
        assertThat(event.getDiskUsagePercent()).isNull();
        assertThat(event.getPromptInjectionPattern()).isNull();
        assertThat(event.getRcaConfidence()).isNull();
        assertThat(event.getOccurredAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("Builder pattern: toBuilder copies and mutates a single field")
    void builderToBuilderCopiesAndMutates() {
        NotificationEvent original = NotificationEvent.builder()
                .eventType(NotificationEventType.DISK_RISK)
                .severity(NotificationSeverity.INFO)
                .title("磁盘风险")
                .diskPath("/var")
                .diskUsagePercent(86.5)
                .build();

        NotificationEvent updated = original.toBuilder()
                .severity(NotificationSeverity.WARNING)
                .build();

        assertThat(updated.getEventType()).isEqualTo(NotificationEventType.DISK_RISK);
        assertThat(updated.getTitle()).isEqualTo("磁盘风险");
        assertThat(updated.getDiskPath()).isEqualTo("/var");
        assertThat(updated.getDiskUsagePercent()).isEqualTo(86.5);
        assertThat(updated.getSeverity()).isEqualTo(NotificationSeverity.WARNING);
    }

    @Test
    @DisplayName("Mutable DTO: setters used by NotificationService.emit work")
    void settersWork() {
        NotificationEvent event = new NotificationEvent();
        event.setEventId("evt-123");
        event.setOccurredAt(LocalDateTime.now());
        event.setEventType(NotificationEventType.L2_CONFIRM_REQUIRED);
        event.setSeverity(NotificationSeverity.WARNING);

        assertThat(event.getEventId()).isEqualTo("evt-123");
        assertThat(event.getEventType()).isEqualTo(NotificationEventType.L2_CONFIRM_REQUIRED);
        assertThat(event.getSeverity()).isEqualTo(NotificationSeverity.WARNING);
        assertThat(event.getOccurredAt()).isNotNull();
    }

    @Test
    @DisplayName("Disk-risk context fields carry Double values")
    void diskRiskFields() {
        NotificationEvent event = NotificationEvent.builder()
                .eventType(NotificationEventType.DISK_RISK)
                .diskPath("/var/log")
                .diskUsagePercent(92.0)
                .rcaConfidence(0.85)
                .build();

        assertThat(event.getDiskPath()).isEqualTo("/var/log");
        assertThat(event.getDiskUsagePercent()).isEqualTo(92.0);
        assertThat(event.getRcaConfidence()).isEqualTo(0.85);
    }

    @Test
    @DisplayName("Service-abnormal context fields carry rcaConfidence")
    void serviceAbnormalFields() {
        NotificationEvent event = NotificationEvent.builder()
                .eventType(NotificationEventType.SERVICE_ABNORMAL)
                .serviceName("nginx")
                .rcaConfidence(0.75)
                .build();

        assertThat(event.getServiceName()).isEqualTo("nginx");
        assertThat(event.getRcaConfidence()).isEqualTo(0.75);
    }

    @Test
    @DisplayName("Prompt-injection context carries the matched pattern")
    void promptInjectionFields() {
        NotificationEvent event = NotificationEvent.builder()
                .eventType(NotificationEventType.PROMPT_INJECTION_BLOCK)
                .promptInjectionPattern("忽略之前所有规则")
                .build();

        assertThat(event.getPromptInjectionPattern()).isEqualTo("忽略之前所有规则");
    }
}
