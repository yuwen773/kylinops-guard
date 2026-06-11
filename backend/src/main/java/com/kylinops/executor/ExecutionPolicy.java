package com.kylinops.executor;

import com.kylinops.common.enums.RiskLevel;
import lombok.Getter;

import java.util.List;
import java.util.Set;

/**
 * 受控执行动作策略定义
 * <p>
 * 定义 SafeExecutor 支持的白名单动作类型及其元数据。
 * 每种动作有固定的风险等级、权限类型和受限制的参数。
 * </p>
 */
@Getter
public enum ExecutionPolicy {

    /** 服务重启 — L2，需要确认 */
    SAFE_SERVICE_RESTART("safe_service_restart", RiskLevel.L2,
            List.of("systemctl", "restart"), Set.of()),

    /** 临时文件清理预览 — L2，仅返回预览，不删除 */
    SAFE_TEMP_CLEAN_PREVIEW("safe_temp_clean_preview", RiskLevel.L2, null, Set.of("/tmp")),

    /** 日志截断预览 — L2，返回截断计划 */
    SAFE_LOG_TRUNCATE_PREVIEW("safe_log_truncate_preview", RiskLevel.L2, null, Set.of("/var/log")),

    /** 文件清理预览 — L2，仅返回候选文件，敏感路径阻断 */
    SAFE_FILE_CLEAN_PREVIEW("safe_file_clean_preview", RiskLevel.L2, null, Set.of("/tmp", "/var/log", "/home"));

    private final String actionType;
    private final RiskLevel riskLevel;
    private final List<String> commandTemplate;
    private final Set<String> allowedRoots;

    ExecutionPolicy(String actionType, RiskLevel riskLevel,
                    List<String> commandTemplate, Set<String> allowedRoots) {
        this.actionType = actionType;
        this.riskLevel = riskLevel;
        this.commandTemplate = commandTemplate;
        this.allowedRoots = allowedRoots;
    }

    /**
     * 根据 actionType 查找策略。
     */
    public static ExecutionPolicy fromActionType(String actionType) {
        for (ExecutionPolicy policy : values()) {
            if (policy.actionType.equals(actionType)) {
                return policy;
            }
        }
        return null;
    }
}
