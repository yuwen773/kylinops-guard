package com.kylinops.rca;

import com.kylinops.common.enums.IntentType;
import com.kylinops.common.enums.RiskDecision;
import com.kylinops.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 磁盘诊断根因分析器（演示场景 2 重点）。
 *
 * <p>从 disk_usage_tool + large_file_scan_tool 推断根因；</p>
 * <p>显式排除 /var/lib/mysql 等敏感数据库目录。</p>
 */
@Component
public class DiskDiagnosisAnalyzer {

    private static final Set<String> PROTECTED_PATHS = Set.of(
            "/var/lib/mysql", "/var/lib/postgresql", "/var/lib/mongodb",
            "/data/db", "/var/lib/redis"
    );

    public RootCauseChain analyze(IntentType intent, List<ToolResult> results,
                                  RiskDecision decision) {
        if (intent != IntentType.DISK_DIAGNOSIS
                || results == null || results.isEmpty()) {
            return null;
        }

        List<RootCauseChain.Evidence> evidence = new ArrayList<>();
        Double diskUsage = null;
        List<String> largeFiles = new ArrayList<>();

        for (ToolResult r : results) {
            if (!r.isSuccess() || !(r.getData() instanceof Map<?, ?> data)) continue;
            String tool = r.getToolName();
            if ("disk_usage_tool".equals(tool) && data.get("partitions") instanceof List<?> parts) {
                // 解析 "/: 86% used (12G/14G)" 格式
                for (Object p : parts) {
                    String s = String.valueOf(p);
                    int pctIdx = s.indexOf('%');
                    if (pctIdx > 0) {
                        int start = s.lastIndexOf(' ', pctIdx);
                        try {
                            diskUsage = Double.parseDouble(s.substring(start + 1, pctIdx).trim());
                            evidence.add(new RootCauseChain.Evidence(
                                    UUID.randomUUID().toString(), tool, null,
                                    s, diskUsage, "%"));
                        } catch (NumberFormatException ignored) { }
                    }
                }
            } else if ("large_file_scan_tool".equals(tool)
                    && data.get("largeFiles") instanceof List<?> files) {
                for (Object f : files) {
                    String s = String.valueOf(f);
                    largeFiles.add(s);
                }
                // 仅取最大的一个文件作为主证据，与 partition 行合计 2 条 evidence
                if (!largeFiles.isEmpty()) {
                    evidence.add(new RootCauseChain.Evidence(
                            UUID.randomUUID().toString(), tool, null,
                            largeFiles.get(0), null, null));
                }
            }
        }

        if (evidence.isEmpty()) return null;

        // 构造 Hypotheses
        List<RootCauseChain.Hypothesis> hypotheses = new ArrayList<>();
        RootCauseChain.Hypothesis topHyp = null;
        for (String f : largeFiles) {
            boolean isProtected = PROTECTED_PATHS.stream().anyMatch(f::contains);
            if (!isProtected) {
                topHyp = new RootCauseChain.Hypothesis(f, 0.86, true,
                        "large_file_scan_tool 直接定位");
                hypotheses.add(topHyp);
                break;
            }
        }

        // 构造 ExcludedCauses
        List<RootCauseChain.ExcludedCause> excluded = new ArrayList<>();
        for (String p : PROTECTED_PATHS) {
            if (largeFiles.stream().anyMatch(f -> f.contains(p))) {
                excluded.add(new RootCauseChain.ExcludedCause(
                        p + "（敏感数据库目录）",
                        "数据库目录不建议直接清理，可能影响数据完整性",
                        evidence.stream()
                                .filter(e -> e.getObservation().contains(p))
                                .map(RootCauseChain.Evidence::getEvidenceId)
                                .toList()));
            }
        }

        // 构造 Symptom
        String symptom = diskUsage != null
                ? String.format("磁盘根分区使用率 %.0f%%", diskUsage)
                : "磁盘使用率较高";

        // 构造 Conclusion
        String conclusion = topHyp != null
                ? "主要根因是 " + topHyp.getCause() + " 持续增长"
                : "未能定位单一根因，建议人工检查";

        // 构造 Suggestions
        List<String> suggestions = topHyp != null
                ? List.of("先归档或截断 " + extractPath(topHyp.getCause()),
                          "再检查服务是否循环报错")
                : List.of("人工检查各目录占用");

        return RootCauseChain.builder()
                .symptom(symptom)
                .evidence(evidence)
                .hypotheses(hypotheses)
                .excludedCauses(excluded)
                .conclusion(conclusion)
                .confidence(topHyp != null ? 0.86 : 0.4)
                .suggestions(suggestions)
                .riskTips(List.of("清理前需先归档，确认业务不依赖"))
                .build();
    }

    private static String extractPath(String s) {
        int colon = s.indexOf(':');
        return colon > 0 ? s.substring(0, colon).trim() : s;
    }
}
