package com.kylinops.executor;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 受控执行结果
 * <p>
 * SafeExecutor 的输出：执行状态、数据和错误信息。
 * </p>
 */
@Data
public class ExecutionResult {

    /** 是否成功 */
    private final boolean success;

    /** 结果数据（预览内容等） */
    private final Object data;

    /** 错误信息 */
    private final String errorMessage;

    /** 执行摘要 */
    private final String summary;

    /** 执行耗时（毫秒） */
    private final long durationMs;

    /** 执行时间 */
    private final LocalDateTime executedAt;

    public ExecutionResult(boolean success, Object data, String errorMessage,
                           String summary, long durationMs) {
        this.success = success;
        this.data = data;
        this.errorMessage = errorMessage;
        this.summary = summary;
        this.durationMs = durationMs;
        this.executedAt = LocalDateTime.now();
    }

    public static ExecutionResult ok(Object data, String summary) {
        return new ExecutionResult(true, data, null, summary, 0);
    }

    public static ExecutionResult ok(Object data, String summary, long durationMs) {
        return new ExecutionResult(true, data, null, summary, durationMs);
    }

    public static ExecutionResult failed(String errorMessage) {
        return new ExecutionResult(false, null, errorMessage, "执行失败: " + errorMessage, 0);
    }

    public static ExecutionResult unsupported(String actionType) {
        return new ExecutionResult(false, null,
                "不支持的执行动作: " + actionType,
                "当前系统不支持此操作", 0);
    }

    public static ExecutionResult degraded(String actionType, String reason) {
        return new ExecutionResult(false, null,
                "执行降级: " + reason,
                "当前环境无法执行 " + actionType, 0);
    }
}
