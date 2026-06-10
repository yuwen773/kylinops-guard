package com.kylinops.common.enums;

/**
 * 工具启用状态
 * <p>
 * 每个注册的 OpsTool 必须声明其状态，
 * ToolRegistry 根据此状态决定工具是否可用。
 * </p>
 */
public enum ToolStatus {

    /** 启用 — 工具可被调用 */
    ENABLED,

    /** 禁用 — 工具不可被调用 */
    DISABLED
}
