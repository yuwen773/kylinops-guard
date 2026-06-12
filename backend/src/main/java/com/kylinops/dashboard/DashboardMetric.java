package com.kylinops.dashboard;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Dashboard 单条指标数据
 * <p>
 * 封装一次 OpsTool 调用在 Dashboard 上的展示视图：工具名称、状态、结构化数据、
 * 错误信息、耗时。
 * </p>
 *
 * <p>
 * 状态字段使用 {@code "success" | "failed" | "timeout" | "blocked"} 四值，与
 * {@link com.kylinops.tool.ToolResult#getStatus()} 一致。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DashboardMetric {

    /** 工具名称（与 OpsTool 定义一致） */
    private String toolName;

    /** 执行状态: "success" | "failed" | "timeout" | "blocked" */
    private String status;

    /** 工具返回的结构化数据（仅成功时填充） */
    private Object data;

    /** 失败 / 超时 / 阻断时的错误信息 */
    private String errorMessage;

    /** 单次工具调用耗时（毫秒） */
    private long durationMs;
}