package com.kylinops.rca;

import com.kylinops.common.enums.IntentType;
import com.kylinops.common.enums.RiskDecision;
import com.kylinops.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DiskDiagnosisAnalyzerTest {

    private final DiskDiagnosisAnalyzer analyzer = new DiskDiagnosisAnalyzer();

    @Test
    void disk_above_85_with_large_log_returns_full_rca() {
        ToolResult diskResult = ToolResult.success("disk_usage_tool",
                Map.of("partitions", List.of("/: 86% used (12G/14G)")),
                "/: 86% used", 0);
        // 测试数据必须包含一个可处理大文件 + 一个敏感数据库目录，
        // 否则 excludedCauses 断言会因输入数据不匹配而失败
        ToolResult largeFileResult = ToolResult.success("large_file_scan_tool",
                Map.of("largeFiles", List.of(
                        "/var/log/app.log: 12GB",
                        "/var/lib/mysql/binlog.00001: 8GB")),
                "Top 2: /var/log/app.log (12GB), /var/lib/mysql/binlog.00001 (8GB)", 0);

        RootCauseChain chain = analyzer.analyze(
                IntentType.DISK_DIAGNOSIS,
                List.of(diskResult, largeFileResult),
                RiskDecision.ALLOW);

        assertNotNull(chain);
        assertTrue(chain.getSymptom().contains("86%"));
        assertEquals(2, chain.getEvidence().size());

        // ① /var/log/app.log 必须被确认为主因
        assertTrue(chain.getHypotheses().stream().anyMatch(h -> h.isConfirmed()
                && h.getCause().contains("/var/log/app.log")),
                "应确认 /var/log/app.log 为主因");

        // ② /var/lib/mysql 必须出现在 excludedCauses（敏感数据库目录）
        assertTrue(chain.getExcludedCauses().stream()
                .anyMatch(e -> e.getCause().contains("/var/lib/mysql")),
                "应将 /var/lib/mysql 标记为 excludedCause（敏感数据库目录）");

        // ③ 结论应指向 /var/log/app.log，不能指向数据库目录
        assertTrue(chain.getConclusion().contains("/var/log/app.log"),
                "conclusion 必须指向 /var/log/app.log");
        assertFalse(chain.getConclusion().contains("/var/lib/mysql"),
                "conclusion 不能误指 /var/lib/mysql（它是排除项）");

        // ④ 置信度门槛
        assertTrue(chain.getConfidence() >= 0.7);
    }

    @Test
    void empty_results_returns_null() {
        assertNull(analyzer.analyze(IntentType.DISK_DIAGNOSIS,
                List.of(), RiskDecision.ALLOW));
    }
}
