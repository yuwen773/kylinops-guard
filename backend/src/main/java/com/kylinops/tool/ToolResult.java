package com.kylinops.tool;

import lombok.Data;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 工具调用输出 POJO
 * <p>
 * 封装一次 OpsTool 调用的完整结果，包含执行状态、返回数据、耗时和错误信息。
 * 使用静态工厂方法构造不同状态的结果：
 * <ul>
 *   <li>{@link #success(String, Object, String, long)} — 成功</li>
 *   <li>{@link #failed(String, String, long)} — 失败</li>
 *   <li>{@link #timeout(String, long)} — 超时</li>
 *   <li>{@link #blocked(String, String, long)} — 被安全规则阻断</li>
 * </ul>
 * </p>
 */
@Data
public class ToolResult {

    /** 工具名称 */
    private String toolName;

    /** 执行状态: "success" | "failed" | "timeout" | "blocked" */
    private String status;

    /** 工具返回的结构化数据 */
    private Object data;

    /** 中文摘要 */
    private String summary;

    /** 失败/超时/阻断时的错误信息 */
    private String errorMessage;

    /** 开始时间 */
    private LocalDateTime startedAt;

    /** 结束时间 */
    private LocalDateTime finishedAt;

    /** 执行耗时（毫秒） */
    private long durationMs;

    public ToolResult() {
        this.startedAt = LocalDateTime.now();
    }

    /**
     * 标记执行结束，计算耗时。
     */
    private ToolResult finish() {
        this.finishedAt = LocalDateTime.now();
        this.durationMs = ChronoUnit.MILLIS.between(this.startedAt, this.finishedAt);
        return this;
    }

    /**
     * 判断是否执行成功
     */
    public boolean isSuccess() {
        return "success".equals(status);
    }

    // ==================== 静态工厂方法 ====================

    /**
     * 创建成功结果
     *
     * @param toolName  工具名称
     * @param data      返回数据
     * @param summary   中文摘要
     * @param durationMs 已计时的耗时（若传 0 则自动计时）
     */
    public static ToolResult success(String toolName, Object data, String summary, long durationMs) {
        ToolResult result = new ToolResult();
        result.toolName = toolName;
        result.status = "success";
        result.data = data;
        result.summary = summary;
        if (durationMs > 0) {
            result.durationMs = durationMs;
            result.finishedAt = LocalDateTime.now();
        } else {
            result.finish();
        }
        return result;
    }

    /**
     * 创建失败结果
     *
     * @param toolName     工具名称
     * @param errorMessage 错误描述
     * @param durationMs   已计时的耗时
     */
    public static ToolResult failed(String toolName, String errorMessage, long durationMs) {
        ToolResult result = new ToolResult();
        result.toolName = toolName;
        result.status = "failed";
        result.errorMessage = errorMessage;
        result.summary = "工具执行失败: " + errorMessage;
        if (durationMs > 0) {
            result.durationMs = durationMs;
            result.finishedAt = LocalDateTime.now();
        } else {
            result.finish();
        }
        return result;
    }

    /**
     * 创建超时结果
     *
     * @param toolName   工具名称
     * @param timeoutMs  超时阈值（毫秒）
     */
    public static ToolResult timeout(String toolName, long timeoutMs) {
        ToolResult result = new ToolResult();
        result.toolName = toolName;
        result.status = "timeout";
        result.errorMessage = "工具执行超时（阈值: " + timeoutMs + "ms）";
        result.summary = "工具执行超时";
        result.finish();
        return result;
    }

    /**
     * 创建阻断结果（被安全规则拦截）
     *
     * @param toolName  工具名称
     * @param reason    阻断原因
     * @param durationMs 已计时的耗时
     */
    public static ToolResult blocked(String toolName, String reason, long durationMs) {
        ToolResult result = new ToolResult();
        result.toolName = toolName;
        result.status = "blocked";
        result.errorMessage = reason;
        result.summary = "工具调用被安全规则阻断: " + reason;
        if (durationMs > 0) {
            result.durationMs = durationMs;
            result.finishedAt = LocalDateTime.now();
        } else {
            result.finish();
        }
        return result;
    }
}
