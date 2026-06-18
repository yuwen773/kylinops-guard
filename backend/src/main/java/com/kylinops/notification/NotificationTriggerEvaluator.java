package com.kylinops.notification;

import com.kylinops.common.enums.IntentType;
import com.kylinops.rca.RootCauseChain;
import com.kylinops.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 运维类事件触发判定器 — P1-01 Plan 02 Task 1。
 *
 * <p>集中判断 SERVICE_ABNORMAL / DISK_RISK 的触发条件,
 * 避免散落在 AgentOrchestrator 的多个分支里。</p>
 *
 * <p><b>关键不变量(最终口径)</b>(详见 plan §关键不变量):</p>
 * <ul>
 *   <li>RCA 为 null → SERVICE_ABNORMAL / DISK_RISK 都不触发</li>
 *   <li>SERVICE_ABNORMAL: intent=SERVICE_DIAGNOSIS 且 rca.confidence ≥ 0.7 且 serviceName 可提取</li>
 *   <li>DISK_RISK: intent=DISK_DIAGNOSIS 且 rca 存在,且(rca.confidence ≥ 0.7 或 diskUsagePercent ≥ 85.0)</li>
 * </ul>
 *
 * <p><b>设计要点</b>:</p>
 * <ul>
 *   <li>不持有任何可变状态,线程安全,Spring 单例即可</li>
 *   <li>不调 LLM、不查数据库、不发通知 — 纯判定逻辑</li>
 *   <li>所有抽取方法都自包含(不依赖 AgentOrchestrator.extractServiceName(ToolPlan) — 签名不匹配)</li>
 * </ul>
 */
@Component
public class NotificationTriggerEvaluator {

    static final String TOOL_DISK_USAGE = "disk_usage_tool";
    static final String TOOL_SERVICE_STATUS = "service_status_tool";

    /** SERVICE_ABNORMAL 触发所需的最低 RCA 置信度 */
    static final double SERVICE_CONFIDENCE_THRESHOLD = 0.7;

    /** DISK_RISK 触发所需的最低磁盘使用率(百分比) */
    static final double DISK_USAGE_THRESHOLD_PERCENT = 85.0;

    // ────────── 触发判定 ──────────

    /**
     * 是否触发 SERVICE_ABNORMAL。
     *
     * <p>条件:intent=SERVICE_DIAGNOSIS 且 rca 非空 且 rca.confidence ≥ 0.7 且 serviceName 可提取</p>
     */
    public boolean shouldEmitServiceAbnormal(IntentType intent, RootCauseChain rca,
                                            List<ToolResult> toolResults) {
        if (intent != IntentType.SERVICE_DIAGNOSIS) return false;
        if (rca == null) return false;
        if (rca.getConfidence() < SERVICE_CONFIDENCE_THRESHOLD) return false;
        return extractServiceName(rca, toolResults).isPresent();
    }

    /**
     * 是否触发 DISK_RISK。
     *
     * <p>条件:intent=DISK_DIAGNOSIS 且 rca 非空,且 rca.confidence ≥ 0.7 或 diskUsagePercent ≥ 85% 任一</p>
     */
    public boolean shouldEmitDiskRisk(IntentType intent, RootCauseChain rca,
                                      List<ToolResult> toolResults) {
        if (intent != IntentType.DISK_DIAGNOSIS) return false;
        if (rca == null) return false; // 严格:即使磁盘 ≥ 85% 也不触发(避免 raw 用量触发空结论)
        if (rca.getConfidence() >= SERVICE_CONFIDENCE_THRESHOLD) return true;
        Optional<Double> usage = extractDiskUsagePercent(toolResults);
        return usage.isPresent() && usage.get() >= DISK_USAGE_THRESHOLD_PERCENT;
    }

    // ────────── 字段抽取 ──────────

    /**
     * 从 toolResults 中解析 disk_usage_tool 的最大 usedPercent。
     *
     * <p>遍历所有成功执行的 disk_usage_tool 结果,取 partitions 中 usedPercent 的最大值。</p>
     */
    public Optional<Double> extractDiskUsagePercent(List<ToolResult> toolResults) {
        if (toolResults == null || toolResults.isEmpty()) return Optional.empty();
        Double max = null;
        for (ToolResult tr : toolResults) {
            if (!TOOL_DISK_USAGE.equals(tr.getToolName())) continue;
            if (!tr.isSuccess()) continue;
            Object data = tr.getData();
            if (!(data instanceof Map<?, ?> map)) continue;
            Object partitionsObj = map.get("partitions");
            if (!(partitionsObj instanceof List<?> partitions)) continue;
            for (Object p : partitions) {
                if (!(p instanceof Map<?, ?> partition)) continue;
                Object usedObj = partition.get("usedPercent");
                if (usedObj instanceof Number n) {
                    double v = n.doubleValue();
                    if (max == null || v > max) max = v;
                }
            }
        }
        return Optional.ofNullable(max);
    }

    /**
     * 提取磁盘挂载路径。
     *
     * <p>优先从 disk_usage_tool 结果的 partitions[0].mount 取;</p>
     * <p>如 toolResults 拿不到,再尝试从 rca.conclusion 中匹配 "/" 开头的路径片段;</p>
     * <p>都没有则 empty(调用方一般用 "/" 兜底)。</p>
     */
    public Optional<String> extractDiskPath(RootCauseChain rca, List<ToolResult> toolResults) {
        // 1) 从 toolResults 取首个非空挂载点
        String fromPartitions = firstMountFromDiskResults(toolResults);
        if (fromPartitions != null) return Optional.of(fromPartitions);
        // 2) 退化:从 rca.conclusion 中匹配路径片段
        if (rca != null && rca.getConclusion() != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(/[\\w./_-]+)").matcher(rca.getConclusion());
            if (m.find()) return Optional.of(m.group(1));
        }
        return Optional.empty();
    }

    /**
     * 提取服务名。
     *
     * <p>从 toolResults 的 service_status_tool 结果中取 data.serviceName。</p>
     * <p>找不到则尝试从 rca.conclusion 中提取第一个非空服务名(nginx / redis / mysql 等常见关键字)。</p>
     */
    public Optional<String> extractServiceName(RootCauseChain rca, List<ToolResult> toolResults) {
        if (toolResults != null) {
            for (ToolResult tr : toolResults) {
                if (!TOOL_SERVICE_STATUS.equals(tr.getToolName())) continue;
                if (!tr.isSuccess()) continue;
                Object data = tr.getData();
                if (data instanceof Map<?, ?> map) {
                    Object name = map.get("serviceName");
                    if (name instanceof String s && !s.isBlank()) {
                        return Optional.of(s);
                    }
                }
            }
        }
        // 退化:从 rca.conclusion 中提关键字(轻量启发式)
        if (rca != null && rca.getConclusion() != null) {
            String c = rca.getConclusion();
            for (String kw : List.of("nginx", "redis", "mysql", "postgres", "tomcat", "apache", "sshd")) {
                if (c.toLowerCase().contains(kw)) return Optional.of(kw);
            }
        }
        return Optional.empty();
    }

    // ────────── 私有辅助 ──────────

    private static String firstMountFromDiskResults(List<ToolResult> toolResults) {
        if (toolResults == null) return null;
        for (ToolResult tr : toolResults) {
            if (!TOOL_DISK_USAGE.equals(tr.getToolName())) continue;
            if (!tr.isSuccess()) continue;
            Object data = tr.getData();
            if (!(data instanceof Map<?, ?> map)) continue;
            Object partitionsObj = map.get("partitions");
            if (!(partitionsObj instanceof List<?> partitions)) continue;
            for (Object p : partitions) {
                if (p instanceof Map<?, ?> partition) {
                    Object mount = partition.get("mount");
                    if (mount instanceof String s && !s.isBlank()) return s;
                }
            }
        }
        return null;
    }
}