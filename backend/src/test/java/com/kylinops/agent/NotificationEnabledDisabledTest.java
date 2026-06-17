package com.kylinops.agent;

import com.kylinops.agent.AgentOrchestrator.AgentRequest;
import com.kylinops.common.enums.RiskDecision;
import com.kylinops.common.enums.RiskLevel;
import com.kylinops.notification.NotificationDispatcher;
import com.kylinops.notification.NotificationEvent;
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
