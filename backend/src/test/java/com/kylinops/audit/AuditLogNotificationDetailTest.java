package com.kylinops.audit;

import com.kylinops.common.enums.AuditStatus;
import com.kylinops.common.enums.IntentType;
import com.kylinops.common.enums.RiskDecision;
import com.kylinops.common.enums.RiskLevel;
import com.kylinops.notification.ChannelType;
import com.kylinops.notification.NotificationRecord;
import com.kylinops.notification.NotificationRecordRepository;
import com.kylinops.notification.NotificationRecordSummary;
import com.kylinops.notification.NotificationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("AuditLogDetail notification records")
class AuditLogNotificationDetailTest {

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private NotificationRecordRepository notificationRecordRepository;

    @Test
    void detailIncludesRelatedNotificationRecordSummariesOnly() {
        String auditId = UUID.randomUUID().toString();
        String unrelatedAuditId = UUID.randomUUID().toString();
        LocalDateTime base = LocalDateTime.of(2026, 6, 17, 12, 0);

        AuditLog log = new AuditLog();
        log.setAuditId(auditId);
        log.setSessionId(UUID.randomUUID().toString());
        log.setUserInput("notification detail");
        log.setIntentType(IntentType.SYSTEM_CHECK);
        log.setRiskLevel(RiskLevel.L4);
        log.setRiskDecision(RiskDecision.BLOCK);
        log.setStatus(AuditStatus.BLOCKED);
        auditLogRepository.save(log);

        notificationRecordRepository.save(record(auditId, "webhook-prod",
                NotificationStatus.SENT, base.plusSeconds(1)));
        notificationRecordRepository.save(record(auditId, "feishu-prod",
                NotificationStatus.FAILED, base.plusSeconds(2)));
        notificationRecordRepository.save(record(unrelatedAuditId, "webhook-unrelated",
                NotificationStatus.SENT, base.plusSeconds(3)));

        AuditLogDetail detail = auditLogService.getDetail(auditId).orElseThrow();

        assertThat(detail.getNotificationRecords()).hasSize(2);
        assertThat(detail.getNotificationRecords())
                .extracting(NotificationRecordSummary::getAuditId)
                .containsOnly(auditId);
        assertThat(detail.getNotificationRecords())
                .extracting(NotificationRecordSummary::getStatus)
                .containsExactly(NotificationStatus.FAILED, NotificationStatus.SENT);

        assertThat(NotificationRecordSummary.class.getDeclaredFields()).hasSize(11);
        assertThat(Arrays.stream(NotificationRecordSummary.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName))
                .doesNotContain("requestPayload", "responseBody");
    }

    private NotificationRecord record(String auditId, String channelId,
                                      NotificationStatus status, LocalDateTime createdAt) {
        return NotificationRecord.builder()
                .recordId(UUID.randomUUID().toString())
                .eventId(UUID.randomUUID().toString())
                .auditId(auditId)
                .channelId(channelId)
                .channelType(ChannelType.WEBHOOK)
                .status(status)
                .requestPayload("{\"secret\":\"redacted\"}")
                .responseBody("internal response")
                .responseCode(status == NotificationStatus.SENT ? 200 : 500)
                .errorMessage(status == NotificationStatus.FAILED ? "failed" : null)
                .retryCount(0)
                .sentAt(status == NotificationStatus.SENT ? createdAt.plusSeconds(1) : null)
                .createdAt(createdAt)
                .build();
    }
}
