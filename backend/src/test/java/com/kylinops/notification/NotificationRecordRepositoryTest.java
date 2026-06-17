package com.kylinops.notification;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Repository tests for NotificationRecord (P1-01 Plan 01 — Task 2).
 *
 * <p>Verifies:</p>
 * <ul>
 *   <li>save + findById round-trip</li>
 *   <li>unique constraint {@code uk_event_channel} on (event_id, channel_id) throws
 *       {@link DataIntegrityViolationException} on the second insert</li>
 *   <li>{@code findFirstByEventIdAndChannelId} returns the right record</li>
 * </ul>
 */
@DataJpaTest
@DisplayName("P1-01 T2 — NotificationRecordRepository")
class NotificationRecordRepositoryTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private NotificationRecordRepository repository;

    private NotificationRecord sampleRecord() {
        LocalDateTime now = LocalDateTime.now();
        return NotificationRecord.builder()
                .recordId(UUID.randomUUID().toString())
                .eventId(UUID.randomUUID().toString())
                .auditId("audit-" + UUID.randomUUID())
                .channelId("webhook-prod")
                .channelType(ChannelType.WEBHOOK)
                .status(NotificationStatus.PENDING)
                .requestPayload("{\"eventType\":\"L4_BLOCK\"}")
                .responseBody(null)
                .responseCode(null)
                .errorMessage(null)
                .retryCount(0)
                .sentAt(null)
                .createdAt(now)
                .build();
    }

    @BeforeEach
    void setUp() {
        // Each test starts from a clean state for assertions
    }

    @Test
    @DisplayName("save + findById round-trip preserves all fields")
    void saveAndFindByIdRoundTrip() {
        NotificationRecord saved = repository.save(sampleRecord());
        em.flush();
        em.clear();

        Optional<NotificationRecord> found = repository.findById(saved.getRecordId());

        assertThat(found).isPresent();
        NotificationRecord loaded = found.get();
        assertThat(loaded.getRecordId()).isEqualTo(saved.getRecordId());
        assertThat(loaded.getEventId()).isEqualTo(saved.getEventId());
        assertThat(loaded.getAuditId()).isEqualTo(saved.getAuditId());
        assertThat(loaded.getChannelId()).isEqualTo("webhook-prod");
        assertThat(loaded.getChannelType()).isEqualTo(ChannelType.WEBHOOK);
        assertThat(loaded.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(loaded.getRequestPayload()).isEqualTo("{\"eventType\":\"L4_BLOCK\"}");
        assertThat(loaded.getRetryCount()).isEqualTo(0);
        assertThat(loaded.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("Unique constraint uk_event_channel rejects duplicate (event_id, channel_id)")
    void uniqueConstraintViolation() {
        String eventId = UUID.randomUUID().toString();
        String channelId = "webhook-prod";

        NotificationRecord first = NotificationRecord.builder()
                .recordId(UUID.randomUUID().toString())
                .eventId(eventId)
                .auditId("audit-1")
                .channelId(channelId)
                .channelType(ChannelType.WEBHOOK)
                .status(NotificationStatus.PENDING)
                .retryCount(0)
                .createdAt(LocalDateTime.now())
                .build();
        repository.saveAndFlush(first);

        NotificationRecord second = NotificationRecord.builder()
                .recordId(UUID.randomUUID().toString())
                .eventId(eventId)
                .auditId("audit-2")
                .channelId(channelId)
                .channelType(ChannelType.WEBHOOK)
                .status(NotificationStatus.FAILED)
                .retryCount(0)
                .createdAt(LocalDateTime.now())
                .build();

        assertThatThrownBy(() -> repository.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Same eventId is allowed across different channelIds")
    void sameEventIdDifferentChannels() {
        String eventId = UUID.randomUUID().toString();

        NotificationRecord a = NotificationRecord.builder()
                .recordId(UUID.randomUUID().toString())
                .eventId(eventId)
                .auditId("audit-1")
                .channelId("webhook-prod")
                .channelType(ChannelType.WEBHOOK)
                .status(NotificationStatus.SENT)
                .retryCount(0)
                .createdAt(LocalDateTime.now())
                .build();
        NotificationRecord b = NotificationRecord.builder()
                .recordId(UUID.randomUUID().toString())
                .eventId(eventId)
                .auditId("audit-1")
                .channelId("feishu-prod")
                .channelType(ChannelType.FEISHU)
                .status(NotificationStatus.SENT)
                .retryCount(0)
                .createdAt(LocalDateTime.now())
                .build();

        repository.saveAndFlush(a);
        repository.saveAndFlush(b);

        assertThat(repository.findById(a.getRecordId())).isPresent();
        assertThat(repository.findById(b.getRecordId())).isPresent();
    }

    @Test
    @DisplayName("findFirstByEventIdAndChannelId returns the matching record")
    void findFirstByEventIdAndChannelId() {
        String eventId = UUID.randomUUID().toString();

        NotificationRecord wh = NotificationRecord.builder()
                .recordId(UUID.randomUUID().toString())
                .eventId(eventId)
                .auditId("audit-1")
                .channelId("webhook-prod")
                .channelType(ChannelType.WEBHOOK)
                .status(NotificationStatus.SENT)
                .responseCode(200)
                .retryCount(0)
                .createdAt(LocalDateTime.now())
                .sentAt(LocalDateTime.now())
                .build();
        NotificationRecord fs = NotificationRecord.builder()
                .recordId(UUID.randomUUID().toString())
                .eventId(eventId)
                .auditId("audit-1")
                .channelId("feishu-prod")
                .channelType(ChannelType.FEISHU)
                .status(NotificationStatus.SENT)
                .responseCode(200)
                .retryCount(0)
                .createdAt(LocalDateTime.now())
                .sentAt(LocalDateTime.now())
                .build();
        repository.saveAndFlush(wh);
        repository.saveAndFlush(fs);
        em.clear();

        Optional<NotificationRecord> found =
                repository.findFirstByEventIdAndChannelId(eventId, "feishu-prod");

        assertThat(found).isPresent();
        assertThat(found.get().getRecordId()).isEqualTo(fs.getRecordId());
        assertThat(found.get().getChannelType()).isEqualTo(ChannelType.FEISHU);
    }

    @Test
    @DisplayName("findFirstByEventIdAndChannelId returns empty for unknown pair")
    void findFirstByEventIdAndChannelIdMissing() {
        Optional<NotificationRecord> found =
                repository.findFirstByEventIdAndChannelId("missing-event", "missing-channel");
        assertThat(found).isEmpty();
    }
}
