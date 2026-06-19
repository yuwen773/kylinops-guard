package com.kylinops.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kylinops.notification.NotificationConfig;
import com.kylinops.notification.NotificationRecord;
import com.kylinops.notification.NotificationRecordRepository;
import com.kylinops.notification.NotificationStatus;
import com.kylinops.notification.config.NotificationChannelRepository;
import com.kylinops.notification.config.NotificationConfigurationService;
import com.kylinops.notification.config.NotificationSettingsRepository;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
@ActiveProfiles("test")
@DisplayName("Notification webhook failure does not block chat")
class NotificationWebhookFailureIntegrationTest {

    private static final MockWebServer SERVER = new MockWebServer();

    static {
        try {
            SERVER.start();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void notificationProperties(DynamicPropertyRegistry registry) {
        registry.add("kylinops.notification.enabled", () -> "true");
        registry.add("kylinops.notification.dry-run", () -> "false");
        registry.add("kylinops.notification.channels[0].id", () -> "webhook-500");
        registry.add("kylinops.notification.channels[0].type", () -> "WEBHOOK");
        registry.add("kylinops.notification.channels[0].enabled", () -> "true");
        registry.add("kylinops.notification.channels[0].url", () -> SERVER.url("/notify").toString());
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private NotificationRecordRepository notificationRecordRepository;

    @Autowired
    private NotificationConfigurationService configurationService;

    @Autowired
    private NotificationConfig notificationConfig;

    @Autowired
    private NotificationSettingsRepository settingsRepository;

    @Autowired
    private NotificationChannelRepository channelRepository;

    /**
     * 确保 snapshot 反映 {@link DynamicPropertySource} 设置的通知属性。
     * 参见 NotificationRecordEmissionIntegrationTest 的同类注释。
     */
    @BeforeEach
    void ensureRuntimeConfiguration() {
        channelRepository.deleteAllInBatch();
        settingsRepository.deleteAllInBatch();
        configurationService.initialize(notificationConfig);
    }

    @AfterAll
    static void shutdownServer() throws IOException {
        SERVER.shutdown();
    }

    @Test
    void chatSendReturnsOkWhenWebhookReturns5xxAndRecordsFailure() throws Exception {
        SERVER.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));

        String body = mockMvc.perform(post("/api/chat/send")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"rm -rf /\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(body);
        String auditId = json.path("data").path("auditId").asText();

        List<NotificationRecord> records = waitForRecords(auditId);
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(records.get(0).getResponseCode()).isEqualTo(500);
    }

    private List<NotificationRecord> waitForRecords(String auditId) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        List<NotificationRecord> records = notificationRecordRepository.findByAuditIdOrderByCreatedAtDesc(auditId);
        while (records.stream().noneMatch(record -> record.getStatus() == NotificationStatus.FAILED)
                && System.nanoTime() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            records = notificationRecordRepository.findByAuditIdOrderByCreatedAtDesc(auditId);
        }
        return records;
    }
}
