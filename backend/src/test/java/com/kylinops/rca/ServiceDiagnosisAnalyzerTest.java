package com.kylinops.rca;

import com.kylinops.common.enums.IntentType;
import com.kylinops.common.enums.RiskDecision;
import com.kylinops.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ServiceDiagnosisAnalyzerTest {

    private final ServiceDiagnosisAnalyzer analyzer = new ServiceDiagnosisAnalyzer();

    @Test
    void failed_service_with_journal_returns_chain() {
        List<ToolResult> results = List.of(
                ToolResult.success("service_status_tool",
                        Map.of("activeState", "failed"),
                        "nginx: failed (inactive)", 0),
                ToolResult.success("network_port_tool",
                        Map.of("summary", "8080 端口未监听"),
                        "Port 8080 not listening", 0),
                ToolResult.success("journal_log_tool",
                        Map.of("errors", List.of("nginx: bind() failed")),
                        "Last 3 errors: bind failed", 0));

        RootCauseChain chain = analyzer.analyze(
                IntentType.SERVICE_DIAGNOSIS, results, RiskDecision.ALLOW);

        assertNotNull(chain);
        assertEquals(3, chain.getEvidence().size());
        assertTrue(chain.getConclusion().contains("nginx") || chain.getConclusion().contains("bind"));
    }
}
