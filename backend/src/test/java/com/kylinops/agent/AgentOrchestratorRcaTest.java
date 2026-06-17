package com.kylinops.agent;

import com.kylinops.common.enums.IntentType;
import com.kylinops.common.enums.RiskDecision;
import com.kylinops.executor.AuthenticatedOperator;
import com.kylinops.rca.RootCauseChain;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@SpringBootTest
class AgentOrchestratorRcaTest {

    @Autowired
    private AgentOrchestrator orchestrator;

    @MockBean
    private com.kylinops.rca.RootCauseAnalyzer analyzer; // mock 接口

    @Test
    void rca_filled_into_agent_result_when_intent_matches() {
        RootCauseChain mockChain = RootCauseChain.builder()
                .symptom("test symptom").confidence(0.9).build();
        when(analyzer.analyze(any(IntentType.class), anyList(), any(RiskDecision.class)))
                .thenReturn(mockChain);

        // 选用 master 上规则必然能命中的输入句（"帮我看看磁盘为什么快满了"）
        // — 避免 "帮我看磁盘" 这种短语在某些规则下识别不稳定
        AgentOrchestrator.AgentRequest req = AgentOrchestrator.AgentRequest.builder()
                .userInput("帮我看看磁盘为什么快满了")
                .requestId("test-audit-id-12345")
                .operator(AuthenticatedOperator.ANONYMOUS)
                .build();

        AgentResult result = orchestrator.process(req);

        // 强断言：intent 与 RCA 字段必须同时满足，不再 if 守卫跳过
        assertEquals(IntentType.DISK_DIAGNOSIS, result.getIntentType(),
                "输入句应稳定识别为 DISK_DIAGNOSIS");
        assertNotNull(result.getRootCauseChain(),
                "RCA 必须被填入 AgentResult（演示场景 2 强契约）");
        assertEquals("test symptom", result.getRootCauseChain().getSymptom(),
                "RCA.symptom 必须来自 analyzer 返回值");
    }
}