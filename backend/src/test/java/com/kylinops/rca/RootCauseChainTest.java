package com.kylinops.rca;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RootCauseChainTest {

    @Test
    void builder_creates_complete_chain() {
        RootCauseChain.Evidence ev = new RootCauseChain.Evidence(
                "ev-1", "disk_usage_tool", "tc-1",
                "/ 分区 86% 使用", 86.0, "%");
        RootCauseChain.Hypothesis hyp = new RootCauseChain.Hypothesis(
                "/var/log/app.log 占用 12GB", 0.86, true, "large_file_scan_tool 直接定位");
        RootCauseChain.ExcludedCause exc = new RootCauseChain.ExcludedCause(
                "/var/lib/mysql（敏感数据库目录）",
                "数据库目录不建议清理",
                java.util.List.of("ev-1"));

        RootCauseChain chain = RootCauseChain.builder()
                .symptom("磁盘根分区使用率 86%")
                .evidence(java.util.List.of(ev))
                .hypotheses(java.util.List.of(hyp))
                .excludedCauses(java.util.List.of(exc))
                .conclusion("/var/log/app.log 持续增长是主因")
                .confidence(0.86)
                .suggestions(java.util.List.of("归档或截断日志"))
                .riskTips(java.util.List.of("清理前需先归档"))
                .build();

        assertEquals("磁盘根分区使用率 86%", chain.getSymptom());
        assertEquals(1, chain.getEvidence().size());
        assertEquals("ev-1", chain.getEvidence().get(0).getEvidenceId());
        assertEquals("tc-1", chain.getEvidence().get(0).getSourceToolCallId());
        assertEquals(0.86, chain.getHypotheses().get(0).getProbability());
        assertTrue(chain.getHypotheses().get(0).isConfirmed());
        assertEquals("ev-1", chain.getExcludedCauses().get(0).getEvidenceIds().get(0));
    }
}
