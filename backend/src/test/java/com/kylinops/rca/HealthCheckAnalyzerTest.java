package com.kylinops.rca;

import com.kylinops.common.enums.IntentType;
import com.kylinops.common.enums.RiskDecision;
import com.kylinops.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HealthCheckAnalyzerTest {

    private final HealthCheckAnalyzer analyzer = new HealthCheckAnalyzer();

    @Test
    void multi_tool_results_yield_health_chain() {
        List<ToolResult> results = List.of(
                ToolResult.success("cpu_status_tool",
                        Map.of("summary", "CPU 4 核，使用率 12%"), "CPU 12%", 0),
                ToolResult.success("memory_status_tool",
                        Map.of("summary", "内存 8G/16G"), "Mem 50%", 0),
                ToolResult.success("disk_usage_tool",
                        Map.of("summary", "/ 86%"), "Disk 86%", 0));

        RootCauseChain chain = analyzer.analyze(
                IntentType.SYSTEM_CHECK, results, RiskDecision.ALLOW);

        assertNotNull(chain);
        assertEquals(3, chain.getEvidence().size());
        // 健康分 < 80 必有 riskTip
        assertFalse(chain.getRiskTips().isEmpty());
    }

    @Test
    void empty_returns_null() {
        assertNull(analyzer.analyze(IntentType.SYSTEM_CHECK,
                List.of(), RiskDecision.ALLOW));
    }
}
