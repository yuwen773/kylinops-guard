package com.kylinops.agent;

import com.kylinops.rca.RootCauseChain;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AgentResultTest {

    @Test
    void builder_supports_root_cause_chain_field() {
        RootCauseChain chain = RootCauseChain.builder()
                .symptom("test").confidence(0.5).build();
        AgentResult result = AgentResult.builder()
                .sessionId("s1").answer("a")
                .intentType(com.kylinops.common.enums.IntentType.DISK_DIAGNOSIS)
                .toolCalls(List.of())
                .riskLevel(com.kylinops.common.enums.RiskLevel.L0)
                .riskDecision("ALLOW")
                .auditId("a1")
                .rootCauseChain(chain)
                .build();
        assertNotNull(result.getRootCauseChain());
        assertEquals("test", result.getRootCauseChain().getSymptom());
    }
}