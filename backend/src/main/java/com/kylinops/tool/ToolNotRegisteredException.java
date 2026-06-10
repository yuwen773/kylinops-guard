package com.kylinops.tool;

/**
 * 工具未注册异常 — 当 Agent 尝试调用未在 ToolRegistry 中注册的工具时抛出。
 * <p>
 * 由 {@link com.kylinops.common.GlobalExceptionHandler} 统一捕获并返回 400 错误。
 * </p>
 */
public class ToolNotRegisteredException extends RuntimeException {

    /** 未注册的工具名称 */
    private final String toolName;

    public ToolNotRegisteredException(String toolName) {
        super("工具未注册: " + toolName + "，请检查工具名称是否正确或工具是否已禁用");
        this.toolName = toolName;
    }

    public ToolNotRegisteredException(String toolName, String message) {
        super(message);
        this.toolName = toolName;
    }

    public String getToolName() {
        return toolName;
    }
}
