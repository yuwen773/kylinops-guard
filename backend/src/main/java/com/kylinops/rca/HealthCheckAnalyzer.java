package com.kylinops.rca;

import com.kylinops.common.enums.IntentType;
import com.kylinops.common.enums.RiskDecision;
import com.kylinops.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 健康评估链（演示场景 1 重点）。
 * 把多工具扇出结果汇总为"健康评估链"，前端标题统一显示。
 */
@Component
public class HealthCheckAnalyzer {

    public RootCauseChain analyze(IntentType intent, List<ToolResult> results,
                                  RiskDecision decision) {
        if (intent != IntentType.SYSTEM_CHECK
                || results == null || results.isEmpty()) {
            return null;
        }

        List<RootCauseChain.Evidence> evidence = new ArrayList<>();
        int successCount = 0;
        boolean hasAlert = false;
        for (ToolResult r : results) {
            if (!r.isSuccess()) continue;
            successCount++;
            String observation = r.getSummary() != null ? r.getSummary() : r.getStatus();
            if (observation != null && observation.matches(".*\\b(\\d{2,3})%.*")) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{2,3})%").matcher(observation);
                while (m.find()) {
                    int pct = Integer.parseInt(m.group(1));
                    if (pct >= 80) { hasAlert = true; break; }
                }
            }
            evidence.add(new RootCauseChain.Evidence(
                    UUID.randomUUID().toString(), r.getToolName(), null,
                    observation, null, null));
        }
        if (evidence.isEmpty()) return null;

        int baseScore = (int) (successCount * 100.0 / results.size());
        int healthScore = hasAlert ? Math.min(baseScore, 70) : baseScore;

        return RootCauseChain.builder()
                .symptom(String.format("系统健康评分 %d/100", healthScore))
                .evidence(evidence)
                .hypotheses(List.of())
                .excludedCauses(List.of())
                .conclusion(healthScore >= 80 ? "系统运行状态良好"
                        : healthScore >= 60 ? "系统存在部分异常，建议排查"
                        : "系统存在较多异常，请及时处理")
                .confidence(healthScore / 100.0)
                .suggestions(List.of("查看具体异常工具的 evidence"))
                .riskTips(healthScore < 80
                        ? List.of("存在异常工具，请重点关注")
                        : List.of())
                .build();
    }
}