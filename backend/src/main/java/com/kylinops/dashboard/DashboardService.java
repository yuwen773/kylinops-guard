package com.kylinops.dashboard;

import com.kylinops.audit.AuditLog;
import com.kylinops.audit.AuditLogService;
import com.kylinops.common.enums.AuditStatus;
import com.kylinops.common.enums.PermissionType;
import com.kylinops.common.enums.RiskLevel;
import com.kylinops.tool.ToolDefinition;
import com.kylinops.tool.ToolExecutor;
import com.kylinops.tool.ToolRegistry;
import com.kylinops.tool.ToolResult;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Dashboard 概览采集服务
 * <p>
 * 单一职责：拉取所有已注册只读工具，通过 {@link ToolExecutor} 并行执行，
 * 收集每个工具的执行结果并产出 {@link DashboardOverview}。
 * </p>
 *
 * <h3>安全约束</h3>
 * <ul>
 *   <li>不直接调用 {@code ProcessBuilder} / shell / {@code OsCommandExecutor}</li>
 *   <li>仅过滤 {@code riskLevel ∈ {L0, L1}} 且 {@code permissionType == READ} 的工具</li>
 *   <li>单次刷新先创建 {@link AuditLog}，auditId 共享到所有 ToolExecutor 调用</li>
 *   <li>采集结束通过 {@code auditLogService.finalizeCollection} 写入覆盖率与最终状态</li>
 *   <li>单工具失败不外抛 → 整体 HTTP 200；得分仅基于成功指标</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {

    private static final Set<RiskLevel> ALLOWED_RISK_LEVELS = EnumSet.of(RiskLevel.L0, RiskLevel.L1);

    private static final int CORE_POOL_SIZE = 4;
    private static final int MAX_POOL_SIZE = 16;
    private static final int QUEUE_CAPACITY = 64;
    private static final long PER_TOOL_TIMEOUT_MS = 10_000L;

    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;
    private final AuditLogService auditLogService;

    /**
     * Dashboard 采集专用并行线程池：有界队列 + CallerRunsPolicy，避免 OOM，
     * 不与 AgentOrchestrator 共享池以免健康检查刷新拖垮 Chat 链路。
     */
    private ExecutorService collectionExecutor;

    @PostConstruct
    void initExecutor() {
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "dashboard-collection-");
            t.setDaemon(true);
            return t;
        };
        this.collectionExecutor = new ThreadPoolExecutor(
                CORE_POOL_SIZE, MAX_POOL_SIZE, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                tf,
                new ThreadPoolExecutor.CallerRunsPolicy());
        log.info("DashboardService 采集线程池初始化完成: core={}, max={}, queue={}",
                CORE_POOL_SIZE, MAX_POOL_SIZE, QUEUE_CAPACITY);
    }

    @PreDestroy
    void shutdownExecutor() {
        if (collectionExecutor == null) {
            return;
        }
        collectionExecutor.shutdown();
        try {
            if (!collectionExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                collectionExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            collectionExecutor.shutdownNow();
        }
    }

    /**
     * 拉取只读工具、并行执行、汇总产出 Dashboard 概览。
     */
    public DashboardOverview refresh() {
        // 1. 过滤只读 L0/L1 工具
        List<ToolDefinition> readOnlyTools = filterReadOnlyTools();

        // 2. 先创建审计（必须在任何 ToolExecutor 调用之前）
        AuditLog audit = auditLogService.startCollection("dashboard_refresh");
        String auditId = audit.getAuditId();
        log.info("Dashboard 采集开始: auditId={}, toolCount={}", auditId, readOnlyTools.size());

        // 3. 并行调用（bounded 线程池）
        List<DashboardMetric> metrics = collectInParallel(readOnlyTools, auditId);

        // 4. 计算健康分（仅基于成功指标）
        int total = metrics.size();
        int successCount = (int) metrics.stream()
                .filter(m -> "success".equals(m.getStatus()))
                .count();
        boolean degraded = successCount < total;
        Integer score = computeHealthScore(metrics);

        // 5. 收尾审计
        double coverage = total == 0 ? 0.0 : (double) successCount / total;
        AuditStatus finalStatus = degraded ? AuditStatus.FAILED : AuditStatus.SUCCESS;
        try {
            auditLogService.finalizeCollection(auditId, coverage, finalStatus);
        } catch (Exception e) {
            log.warn("Dashboard 采集审计收尾失败: auditId={}, err={}", auditId, e.getMessage());
        }

        log.info("Dashboard 采集完成: auditId={}, success={}/{}, degraded={}, score={}",
                auditId, successCount, total, degraded, score);

        return DashboardOverview.builder()
                .score(score)
                .successfulMetricCount(successCount)
                .totalMetricCount(total)
                .degraded(degraded)
                .auditId(auditId)
                .collectedAt(Instant.now())
                .metrics(metrics)
                .build();
    }

    /**
     * 仅保留 L0/L1 + READ 权限的工具定义。
     */
    private List<ToolDefinition> filterReadOnlyTools() {
        List<ToolDefinition> enabled = toolRegistry.getEnabledToolDefinitions();
        if (enabled == null || enabled.isEmpty()) {
            return Collections.emptyList();
        }
        List<ToolDefinition> filtered = new ArrayList<>();
        for (ToolDefinition def : enabled) {
            if (def == null) {
                continue;
            }
            if (!ALLOWED_RISK_LEVELS.contains(def.getRiskLevel())) {
                continue;
            }
            if (def.getPermissionType() != PermissionType.READ) {
                continue;
            }
            filtered.add(def);
        }
        // 稳定排序：按工具名升序，便于快照测试与前端展示一致
        filtered.sort((a, b) -> a.getToolName().compareTo(b.getToolName()));
        return filtered;
    }

    /**
     * 在有界线程池中并行执行所有只读工具，并把每个工具的结果（成功 / 失败 / 超时）
     * 收集为 {@link DashboardMetric}。
     */
    private List<DashboardMetric> collectInParallel(List<ToolDefinition> tools, String auditId) {
        if (tools.isEmpty()) {
            return new ArrayList<>();
        }

        Map<String, CompletableFuture<DashboardMetric>> futures = new HashMap<>();
        for (ToolDefinition def : tools) {
            String toolName = def.getToolName();
            CompletableFuture<DashboardMetric> future = CompletableFuture.supplyAsync(
                    () -> runOneTool(def, auditId), collectionExecutor);
            futures.put(toolName, future);
        }

        List<DashboardMetric> results = new ArrayList<>(tools.size());
        for (ToolDefinition def : tools) {
            String toolName = def.getToolName();
            CompletableFuture<DashboardMetric> future = futures.get(toolName);
            try {
                results.add(future.get(PER_TOOL_TIMEOUT_MS, TimeUnit.MILLISECONDS));
            } catch (TimeoutException e) {
                log.warn("Dashboard 工具采集超时: toolName={}, timeoutMs={}", toolName, PER_TOOL_TIMEOUT_MS);
                future.cancel(true);
                results.add(DashboardMetric.builder()
                        .toolName(toolName)
                        .status("timeout")
                        .errorMessage("工具采集超时")
                        .durationMs(PER_TOOL_TIMEOUT_MS)
                        .build());
            } catch (Exception e) {
                log.error("Dashboard 工具采集异常: toolName={}", toolName, e);
                results.add(DashboardMetric.builder()
                        .toolName(toolName)
                        .status("failed")
                        .errorMessage("采集异常: " + e.getMessage())
                        .durationMs(0L)
                        .build());
            }
        }
        return results;
    }

    /**
     * 单个工具的采集入口：捕获所有异常与中断，统一返回 DashboardMetric（不抛）。
     */
    private DashboardMetric runOneTool(ToolDefinition def, String auditId) {
        long start = System.currentTimeMillis();
        try {
            ToolResult result = toolExecutor.execute(def.getToolName(),
                    java.util.Collections.emptyMap(), auditId);
            long duration = System.currentTimeMillis() - start;
            if (result == null) {
                return DashboardMetric.builder()
                        .toolName(def.getToolName())
                        .status("failed")
                        .errorMessage("工具返回 null 结果")
                        .durationMs(duration)
                        .build();
            }
            return DashboardMetric.builder()
                    .toolName(def.getToolName())
                    .status(result.getStatus())
                    .data("success".equals(result.getStatus()) ? result.getData() : null)
                    .errorMessage(result.getErrorMessage())
                    .durationMs(duration)
                    .build();
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.warn("Dashboard 工具执行异常: toolName={}, err={}", def.getToolName(), e.getMessage());
            return DashboardMetric.builder()
                    .toolName(def.getToolName())
                    .status("failed")
                    .errorMessage("工具执行异常: " + e.getMessage())
                    .durationMs(duration)
                    .build();
        }
    }

    /**
     * 根据成功指标计算 0-100 健康分；全部失败或无指标时返回 {@code null}。
     * <p>
     * 计算逻辑（仅基于 {@code status == "success"} 的指标，绝不用假值填充）：
     * <ul>
     *   <li>成功率基线：success / total × 60（满分 60）</li>
     *   <li>CPU 使用率扣分（来自 cpu_status_tool.usagePercent，> 80 扣 20，> 60 扣 10）</li>
     *   <li>内存使用率扣分（来自 memory_status_tool.usedPercent，> 90 扣 20，> 75 扣 10）</li>
     *   <li>磁盘高占用扣分（来自 disk_usage_tool 任一分区 usedPercent > 90 扣 10）</li>
     * </ul>
     * </p>
     */
    @SuppressWarnings("unchecked")
    Integer computeHealthScore(List<DashboardMetric> metrics) {
        if (metrics == null || metrics.isEmpty()) {
            return null;
        }
        long successCount = metrics.stream()
                .filter(m -> "success".equals(m.getStatus()))
                .count();
        if (successCount == 0) {
            return null;
        }

        int total = metrics.size();
        int score = (int) Math.round((double) successCount / total * 60.0);

        for (DashboardMetric m : metrics) {
            if (!"success".equals(m.getStatus()) || !(m.getData() instanceof Map<?, ?>)) {
                continue;
            }
            Map<String, Object> data = (Map<String, Object>) m.getData();

            if ("cpu_status_tool".equals(m.getToolName())) {
                score -= applyCpuPenalty(data);
            } else if ("memory_status_tool".equals(m.getToolName())) {
                score -= applyMemoryPenalty(data);
            } else if ("disk_usage_tool".equals(m.getToolName())) {
                score -= applyDiskPenalty(data);
            }
        }

        return Math.max(0, Math.min(100, score));
    }

    private int applyCpuPenalty(Map<String, Object> data) {
        Object v = data.get("usagePercent");
        if (!(v instanceof Number)) {
            return 0;
        }
        double pct = ((Number) v).doubleValue();
        if (pct < 0) {
            return 0;
        }
        if (pct > 80.0) return 20;
        if (pct > 60.0) return 10;
        return 0;
    }

    private int applyMemoryPenalty(Map<String, Object> data) {
        Object v = data.get("usedPercent");
        if (!(v instanceof Number)) {
            return 0;
        }
        double pct = ((Number) v).doubleValue();
        if (pct < 0) {
            return 0;
        }
        if (pct > 90.0) return 20;
        if (pct > 75.0) return 10;
        return 0;
    }

    @SuppressWarnings("unchecked")
    private int applyDiskPenalty(Map<String, Object> data) {
        Object parts = data.get("partitions");
        if (!(parts instanceof List)) {
            return 0;
        }
        int penalty = 0;
        for (Object p : (List<Object>) parts) {
            if (!(p instanceof Map)) continue;
            Object v = ((Map<String, Object>) p).get("usedPercent");
            if (!(v instanceof Number)) continue;
            double pct = ((Number) v).doubleValue();
            if (pct > 90.0) {
                penalty = Math.max(penalty, 10);
            }
        }
        return penalty;
    }
}