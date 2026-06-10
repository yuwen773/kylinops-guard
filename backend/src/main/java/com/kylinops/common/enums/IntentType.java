package com.kylinops.common.enums;

/**
 * Agent 意图分类
 * <p>
 * IntentClassifier 将用户自然语言输入解析为以下意图之一，
 * 后续 ToolPlanningService 根据意图编排对应工具链。
 * </p>
 */
public enum IntentType {

    /** 系统健康检查 — 多 Tool 发散获取系统状态 */
    SYSTEM_CHECK,

    /** 磁盘诊断 — 磁盘用量分析和清理建议 */
    DISK_DIAGNOSIS,

    /** 服务诊断 — 检查服务运行状态 */
    SERVICE_DIAGNOSIS,

    /** 进程查询 — 查看进程列表或详细信息 */
    PROCESS_QUERY,

    /** 文件操作 — 文件清理、日志截断等 */
    FILE_OPERATION,

    /** 命令执行 — 执行运维命令（高度警惕） */
    COMMAND_EXECUTION,

    /** 网络查询 — 端口状态、网络连接 */
    NETWORK_QUERY,

    /** 日志查询 — 查看系统或应用日志 */
    LOG_QUERY,

    /** 通用对话 — 非运维类闲聊 */
    GENERAL_CHAT,

    /** 无法识别的意图 */
    UNKNOWN
}
