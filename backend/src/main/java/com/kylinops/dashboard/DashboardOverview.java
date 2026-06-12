package com.kylinops.dashboard;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Dashboard 系统概览 DTO
 * <p>
 * 包含整体健康分、成功/总数、是否降级、采集时间、关联审计 ID 以及每项工具指标。
 * 任意单条工具失败不影响整体 HTTP 200；得分仅基于成功指标计算。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DashboardOverview {

    /** 健康分 0-100；所有工具均失败时为 {@code null}，绝不使用默认假值 */
    private Integer score;

    /** 成功执行的指标数量（{@code status == "success"}） */
    private int successfulMetricCount;

    /** 本次采集尝试调用的工具总数 */
    private int totalMetricCount;

    /** 是否存在失败/超时/阻断（任何一项失败即视为降级） */
    private boolean degraded;

    /** 关联审计 ID；同一刷新周期内所有 ToolExecutor 调用共享此 ID */
    private String auditId;

    /** 采集完成时间 */
    private Instant collectedAt;

    /** 每个工具的指标列表（结构化返回失败项） */
    private List<DashboardMetric> metrics;
}