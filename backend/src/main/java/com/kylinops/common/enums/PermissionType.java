package com.kylinops.common.enums;

/**
 * 操作权限类型
 * <p>
 * 每个注册的 OpsTool 必须声明其 permissionType，
 * SafeExecutor 根据此类型决定执行账户和执行环境。
 * </p>
 */
public enum PermissionType {

    /** 只读 — 查询类操作，无需特殊权限 */
    READ,

    /** 写入 — 日志截断、文件清理等写操作 */
    WRITE,

    /** 执行 — 服务重启等操作 */
    EXECUTE,

    /** 管理 — 涉及系统配置变更的操作 */
    ADMIN
}
