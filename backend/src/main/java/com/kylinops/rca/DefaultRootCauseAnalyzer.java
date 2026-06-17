package com.kylinops.rca;

import com.kylinops.common.enums.IntentType;
import com.kylinops.common.enums.RiskDecision;
import com.kylinops.tool.ToolResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 默认根因分析器：按 intent 分发给具体 analyzer。
 * 仅 Task 3/4/5 三个 analyzer 全部就位后才创建，避免编译顺序问题。
 */
@Component
@RequiredArgsConstructor
public class DefaultRootCauseAnalyzer implements RootCauseAnalyzer {

    private final DiskDiagnosisAnalyzer diskAnalyzer;
    private final HealthCheckAnalyzer healthAnalyzer;
    private final ServiceDiagnosisAnalyzer serviceAnalyzer;

    @Override
    public RootCauseChain analyze(IntentType intent, List<ToolResult> results,
                                  RiskDecision decision) {
        if (intent == null || results == null || results.isEmpty()) {
            return null;
        }
        return switch (intent) {
            case DISK_DIAGNOSIS -> diskAnalyzer.analyze(intent, results, decision);
            case SYSTEM_CHECK -> healthAnalyzer.analyze(intent, results, decision);
            case SERVICE_DIAGNOSIS -> serviceAnalyzer.analyze(intent, results, decision);
            default -> null;
        };
    }
}
