package com.kylinops.agent;

import com.kylinops.agent.AgentOrchestrator.AgentRequest;
import com.kylinops.common.enums.RiskDecision;
import com.kylinops.common.enums.RiskLevel;
import com.kylinops.notification.NotificationConfig;
import com.kylinops.notification.NotificationDispatcher;
import com.kylinops.notification.NotificationEvent;
import com.kylinops.notification.config.NotificationChannelRepository;
import com.kylinops.notification.config.NotificationConfigurationService;
import com.kylinops.notification.config.NotificationSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = "kylinops.notification.enabled=true")
@ActiveProfiles("test")
@DisplayName("Notification enabled/disabled orchestration")
class NotificationEnabledDisabledTest {

    @Autowired
    private AgentOrchestrator orchestrator;

    @MockBean
    private NotificationDispatcher notificationDispatcher;

    @Autowired
    private NotificationConfigurationService configurationService;

    @Autowired
    private NotificationConfig notificationConfig;

    @Autowired
    private NotificationSettingsRepository settingsRepository;

    @Autowired
    private NotificationChannelRepository channelRepository;

    /**
     * 确保 snapshot 反映当前测试类的 enabled=true 属性。
     * 参见 NotificationRecordEmissionIntegrationTest 的同类注释。
     */
    @BeforeEach
    void ensureRuntimeConfiguration() {
        channelRepository.deleteAllInBatch();
        settingsRepository.deleteAllInBatch();
        configurationService.initialize(notificationConfig);
    }

    @Test
    void enabledNotificationDispatcherFailureDoesNotBlockOrchestratorResult() {
        doThrow(new RuntimeException("dispatch boom"))
                .when(notificationDispatcher).dispatchAsync(any(NotificationEvent.class));
        String auditId = UUID.randomUUID().toString();

        AgentResult result = orchestrator.process(AgentRequest.builder()
                .sessionId(UUID.randomUUID().toString())
                .userInput("rm -rf /")
                .requestId(auditId)
                .build());

        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.L4);
        assertThat(result.getRiskDecision()).isEqualTo(RiskDecision.BLOCK.name());
        assertThat(result.getToolCalls()).isEmpty();
        assertThat(result.getAuditId()).isEqualTo(auditId);
        verify(notificationDispatcher, times(1)).dispatchAsync(any(NotificationEvent.class));
    }
}
