package com.kylinops.agent;

import com.kylinops.agent.AgentOrchestrator.AgentRequest;
import com.kylinops.common.enums.RiskDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("L2 confirm notification path")
class L2ConfirmNotificationTest {

    @Autowired
    private AgentOrchestrator orchestrator;

    @Test
    void restartNginxRequiresConfirmationWithoutToolExecution() {
        String auditId = UUID.randomUUID().toString();

        AgentResult result = orchestrator.process(AgentRequest.builder()
                .sessionId(UUID.randomUUID().toString())
                .userInput("重启 nginx 服务")
                .requestId(auditId)
                .build());

        assertThat(result.isNeedConfirmation()).isTrue();
        assertThat(result.getPendingAction()).isNotNull();
        assertThat(result.getPendingAction().getActionId()).isNotBlank();
        assertThat(result.getRiskDecision()).isEqualTo(RiskDecision.CONFIRM.name());
        assertThat(result.getToolCalls()).isEmpty();
        assertThat(result.getAuditId()).isEqualTo(auditId);
    }
}
