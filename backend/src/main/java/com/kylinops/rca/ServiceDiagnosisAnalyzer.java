package com.kylinops.rca;

import com.kylinops.common.enums.IntentType;
import com.kylinops.common.enums.RiskDecision;
import com.kylinops.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 服务诊断链（演示场景 3 重点）。
 * 综合 service_status / network_port / journal_log 三个工具推断服务异常根因。
 */
@Component
public class ServiceDiagnosisAnalyzer {

    public RootCauseChain analyze(IntentType intent, List<ToolResult> results,
                                  RiskDecision decision) {
        if (intent != IntentType.SERVICE_DIAGNOSIS
                || results == null || results.isEmpty()) {
            return null;
        }

        List<RootCauseChain.Evidence> evidence = new ArrayList<>();
        String serviceState = null;
        boolean portMissing = false;
        List<String> journalErrors = new ArrayList<>();

        for (ToolResult r : results) {
            if (!r.isSuccess()) continue;
            String tool = r.getToolName();
            String summary = r.getSummary() != null ? r.getSummary() : "";
            evidence.add(new RootCauseChain.Evidence(
                    UUID.randomUUID().toString(), tool, null, summary, null, null));
            if ("service_status_tool".equals(tool)) serviceState = summary;
            if ("network_port_tool".equals(tool) && summary.contains("未监听")) {
                portMissing = true;
            }
            if ("journal_log_tool".equals(tool) && r.getData() instanceof Map<?, ?> data
                    && data.get("errors") instanceof List<?> errs) {
                for (Object e : errs) journalErrors.add(String.valueOf(e));
            }
        }
        if (evidence.isEmpty()) return null;

        String conclusion;
        List<String> suggestions = new ArrayList<>();
        if (serviceState != null && serviceState.contains("failed")) {
            conclusion = "服务未运行（" + serviceState + "）";
            suggestions.add("使用 service restart 重启（需 L2 确认）");
        } else if (portMissing) {
            conclusion = "服务进程在但端口未监听（启动未完成或配置错误）";
            suggestions.add("检查服务配置（端口 / 监听地址）");
        } else if (!journalErrors.isEmpty()) {
            conclusion = "服务运行中但有错误：" + String.join("; ", journalErrors);
            suggestions.add("查看完整日志定位错误");
        } else {
            conclusion = "服务状态正常";
        }

        return RootCauseChain.builder()
                .symptom("服务异常诊断")
                .evidence(evidence)
                .hypotheses(List.of())
                .excludedCauses(List.of())
                .conclusion(conclusion)
                .confidence(journalErrors.isEmpty() ? 0.6 : 0.85)
                .suggestions(suggestions)
                .riskTips(List.of("重启服务需用户二次确认"))
                .build();
    }
}
