package com.kylinops.executor;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 受控执行计划
 * <p>
 * SafeExecutor 的输入参数，包含确认后的动作信息和审计追踪 ID。
 * 所有字段由服务端构建，不接受用户直接输入。
 * </p>
 */
@Data
@Builder
public class ExecutionPlan {

    /** 动作类型（如 safe_service_restart） */
    private final String actionType;

    /** 目标（服务名、文件路径等） */
    private final String target;

    /** 额外参数 */
    private final Map<String, Object> params;

    /** 审计 ID */
    private final String auditId;
}
