package com.kylinops.tool;

import com.kylinops.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 工具管理 REST 控制器
 * <p>
 * 提供工具注册中心的查询接口，供前端「工具中心」页面使用。
 * </p>
 *
 * <h3>Task 11 扩展</h3>
 * <p>
 * 列表与详情端点同时返回调用统计：
 * <ul>
 *   <li>{@code callCount} — 总调用次数（long）</li>
 *   <li>{@code successRate} — SUCCESS / terminal calls（Double）；
 *       无 terminal calls 时为 null</li>
 *   <li>{@code lastCalledAt} — 最近一次调用时间（Instant），无记录时为 null</li>
 * </ul>
 * 统计通过 {@link ToolCallRecordRepository#findStatsByToolNameIn} 一次性聚合，
 * 严禁在循环中逐工具查询 — 防止 N+1。
 * </p>
 *
 * <h3>接口列表</h3>
 * <ul>
 *   <li>{@code GET /api/tools} — 返回所有已注册工具（含已禁用）</li>
 *   <li>{@code GET /api/tools/{toolName}} — 返回单个工具详情</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/tools")
@RequiredArgsConstructor
public class ToolController {

    private final ToolRegistry toolRegistry;
    private final ToolCallRecordRepository toolCallRecordRepository;

    /**
     * 获取所有已注册工具列表（含调用统计）。
     * <p>
     * 单次 aggregate 查询（{@link ToolCallRecordRepository#findStatsByToolNameIn}）
     * 覆盖全部工具名 — 禁止循环中调用 repository。
     * </p>
     */
    @GetMapping
    public ApiResponse<List<ToolDefinitionVO>> listTools() {
        List<ToolDefinition> definitions = toolRegistry.getAllToolDefinitions();
        Map<String, ToolCallRecordRepository.ToolStatsProjection> statsByName =
                loadStatsMap(definitions);

        List<ToolDefinitionVO> tools = definitions.stream()
                .map(def -> {
                    ToolDefinitionVO vo = ToolDefinitionVO.fromEntity(def);
                    applyStats(vo, statsByName.get(def.getToolName()));
                    return vo;
                })
                .collect(Collectors.toList());
        log.debug("查询工具列表: 共 {} 个工具", tools.size());
        return ApiResponse.success(tools);
    }

    /**
     * 获取单个工具详情（含调用统计）。
     *
     * @param toolName 工具名称
     */
    @GetMapping("/{toolName}")
    public ApiResponse<ToolDefinitionVO> getTool(@PathVariable String toolName) {
        OpsTool tool = toolRegistry.getTool(toolName);
        ToolDefinitionVO vo = ToolDefinitionVO.fromEntity(tool.definition());
        // 单工具的统计走同名接口的 aggregate，1 次查询
        Map<String, ToolCallRecordRepository.ToolStatsProjection> stats =
                loadStatsMap(List.of(tool.definition()));
        applyStats(vo, stats.get(toolName));
        return ApiResponse.success(vo);
    }

    /**
     * 一次性聚合所有工具的调用统计。返回的 Map key 为 toolName。
     * <p>
     * 工具名列表为空时跳过数据库查询（避免无谓的 IN () 子句）。
     * </p>
     */
    private Map<String, ToolCallRecordRepository.ToolStatsProjection> loadStatsMap(
            List<ToolDefinition> definitions) {
        if (definitions == null || definitions.isEmpty()) {
            return Map.of();
        }
        List<String> toolNames = definitions.stream()
                .map(ToolDefinition::getToolName)
                .collect(Collectors.toList());
        List<ToolCallRecordRepository.ToolStatsProjection> rows =
                toolCallRecordRepository.findStatsByToolNameIn(toolNames);
        Map<String, ToolCallRecordRepository.ToolStatsProjection> map = new HashMap<>();
        if (rows != null) {
            for (ToolCallRecordRepository.ToolStatsProjection row : rows) {
                map.put(row.getToolName(), row);
            }
        }
        return map;
    }

    /**
     * 把 aggregate 行合并进 VO。统计缺失时保留默认值（callCount=0，
     * successRate=null, lastCalledAt=null）。
     */
    private void applyStats(ToolDefinitionVO vo,
                            ToolCallRecordRepository.ToolStatsProjection agg) {
        if (agg == null) {
            // 工具从未被调用过 — callCount=0 已由 fromEntity 设置；
            // successRate / lastCalledAt 保持 null，@JsonInclude(NON_NULL)
            // 会让它们不出现在 JSON 中。
            return;
        }
        vo.setCallCount(nullSafe(agg.getCallCount()));
        vo.setLastCalledAt(toInstant(agg.getLastCalledAt()));
        vo.setSuccessRate(computeSuccessRate(agg));
    }

    /** null-safe unbox of an aggregate count. */
    private static long nullSafe(Long value) {
        return value == null ? 0L : value;
    }

    /**
     * successRate = SUCCESS / terminal calls。
     * terminal = SUCCESS + FAILED + TIMEOUT + BLOCKED。
     * 无 terminal calls 时返回 null（不是 0、不是 100）。
     */
    private static Double computeSuccessRate(
            ToolCallRecordRepository.ToolStatsProjection agg) {
        long terminal = nullSafe(agg.getTerminalCount());
        if (terminal <= 0L) {
            return null;
        }
        long success = nullSafe(agg.getSuccessCount());
        if (success < 0L) {
            // 不可能，但兜底：denominator 已 > 0，分子视为 0
            return 0.0;
        }
        return (double) success / (double) terminal;
    }

    /**
     * LocalDateTime → Instant（使用系统默认时区）。
     * Repository 持久化的 createdAt 是 LocalDateTime，转 Instant 是
     * JSON 序列化的常见约定。
     */
    private static Instant toInstant(LocalDateTime ldt) {
        if (ldt == null) {
            return null;
        }
        return ldt.atZone(ZoneId.systemDefault()).toInstant();
    }
}