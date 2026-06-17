package com.kylinops.rca;

import com.kylinops.common.enums.IntentType;
import com.kylinops.common.enums.RiskDecision;
import com.kylinops.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DefaultRootCauseAnalyzerTest {

    private final DefaultRootCauseAnalyzer analyzer = new DefaultRootCauseAnalyzer(
            new DiskDiagnosisAnalyzer(),
            new HealthCheckAnalyzer(),
            new ServiceDiagnosisAnalyzer());

    @Test
    void disk_intent_dispatches_to_disk_analyzer() {
        ToolResult r = ToolResult.success("disk_usage_tool",
                Map.of("partitions", List.of("/: 86% used (12G/14G)")),
                "/: 86% used", 0);
        ToolResult lf = ToolResult.success("large_file_scan_tool",
                Map.of("largeFiles", List.of(
                        "/var/log/app.log: 12GB",
                        "/var/lib/mysql/binlog.00001: 8GB")),
                "Top 2: app.log (12GB), binlog (8GB)", 0);
        RootCauseChain chain = analyzer.analyze(
                IntentType.DISK_DIAGNOSIS,
                List.of(r, lf), RiskDecision.ALLOW);
        assertNotNull(chain);
        assertTrue(chain.getConclusion().contains("/var/log/app.log"));
    }

    @Test
    void system_check_intent_dispatches_to_health_analyzer() {
        ToolResult r = ToolResult.success("cpu_status_tool",
                Map.of("summary", "CPU 12%"), "CPU 12%", 0);
        RootCauseChain chain = analyzer.analyze(
                IntentType.SYSTEM_CHECK,
                List.of(r), RiskDecision.ALLOW);
        assertNotNull(chain);
        assertTrue(chain.getSymptom().contains("系统健康评分"));
    }

    @Test
    void unsupported_intent_returns_null() {
        assertNull(analyzer.analyze(IntentType.GENERAL_CHAT,
                List.of(), RiskDecision.ALLOW));
        assertNull(analyzer.analyze(null, List.of(), RiskDecision.ALLOW));
    }
}
