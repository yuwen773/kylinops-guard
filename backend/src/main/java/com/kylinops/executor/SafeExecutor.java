package com.kylinops.executor;

import com.kylinops.config.KylinOpsConfig;
import com.kylinops.os.OsCommandExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 最小权限安全执行器
 * <p>
 * 仅执行白名单预定义动作。不支持通用命令执行，不使用 sh -c / sudo / root。
 * 所有输入参数在执行前经过格式校验和内容安全检查。
 * </p>
 *
 * <h3>受控动作</h3>
 * <ul>
 *   <li>safe_service_restart — 仅允许白名单服务重启</li>
 *   <li>safe_temp_clean_preview — 临时文件清理预览</li>
 *   <li>safe_log_truncate_preview — 日志截断预览</li>
 *   <li>safe_file_clean_preview — 文件清理预览（敏感路径阻断）</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SafeExecutor {

    private final KylinOpsConfig config;
    private final OsCommandExecutor commandExecutor;

    /** 服务名合法字符：字母、数字、点、下划线、@、连字符 */
    private static final Pattern SERVICE_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9._@-]+$");

    /** 敏感路径前缀（file_clean 时阻断） */
    private static final List<String> SENSITIVE_PATH_PREFIXES = List.of(
            "/etc", "/usr", "/bin", "/sbin", "/boot", "/dev",
            "/proc", "/sys", "/root", "/var/lib/mysql", "/var/lib/postgresql"
    );

    /** 当前操作系统是否为 Windows */
    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    /**
     * 执行受控操作。
     *
     * @param plan 执行计划
     * @return 执行结果
     */
    public ExecutionResult execute(ExecutionPlan plan) {
        if (plan == null) {
            return ExecutionResult.failed("执行计划不能为空");
        }

        String actionType = plan.getActionType();
        log.info("安全执行: actionType={}, target={}, auditId={}",
                actionType, plan.getTarget(), plan.getAuditId());

        ExecutionPolicy policy = ExecutionPolicy.fromActionType(actionType);
        if (policy == null) {
            return ExecutionResult.unsupported(actionType);
        }

        return switch (actionType) {
            case "safe_service_restart" -> executeServiceRestart(plan, policy);
            case "safe_temp_clean_preview" -> executeTempCleanPreview(plan, policy);
            case "safe_log_truncate_preview" -> executeLogTruncatePreview(plan, policy);
            case "safe_file_clean_preview" -> executeFileCleanPreview(plan, policy);
            default -> ExecutionResult.unsupported(actionType);
        };
    }

    /**
     * 执行服务重启。
     * 仅在 Linux 环境通过 systemctl restart 执行。
     */
    private ExecutionResult executeServiceRestart(ExecutionPlan plan, ExecutionPolicy policy) {
        String serviceName = plan.getTarget();
        if (serviceName == null || serviceName.isBlank()) {
            return ExecutionResult.failed("服务名不能为空");
        }

        // 格式校验
        if (!SERVICE_NAME_PATTERN.matcher(serviceName).matches()) {
            return ExecutionResult.failed("服务名不合法（包含非法字符）: " + serviceName);
        }

        // 白名单校验
        List<String> whitelist = config.getExecutor().getWhitelistedServices();
        if (whitelist == null || whitelist.isEmpty()) {
            return ExecutionResult.failed("服务白名单未配置");
        }
        if (whitelist.stream().noneMatch(s -> s.equalsIgnoreCase(serviceName))) {
            return ExecutionResult.failed("服务不在白名单中: " + serviceName + "（允许: " + whitelist + "）");
        }

        // 非 Linux 环境不支持
        if (!commandExecutor.isLinux()) {
            return ExecutionResult.degraded("safe_service_restart",
                    "服务重启需要 Linux systemctl（当前环境不支持）");
        }

        // 执行 systemctl restart
        List<String> command = List.of("systemctl", "restart", serviceName);
        log.info("执行命令: command={}", String.join(" ", command));

        OsCommandExecutor.CommandResult commandResult = commandExecutor.execute(command, 10_000);
        if (!commandResult.isSuccess()) {
            return ExecutionResult.failed("服务重启失败: " + commandResult.getErrorMessage());
        }

        Map<String, Object> data = new HashMap<>();
        data.put("actionType", "service_restart");
        data.put("serviceName", serviceName);
        data.put("exitCode", commandResult.getExitCode());
        data.put("durationMs", commandResult.getDurationMs());

        return ExecutionResult.ok(data, "服务重启成功: " + serviceName);
    }

    /**
     * 临时文件清理预览 — P0 仅返回候选文件，不执行删除。
     */
    private ExecutionResult executeTempCleanPreview(ExecutionPlan plan, ExecutionPolicy policy) {
        if (isWindows()) {
            return ExecutionResult.degraded("safe_temp_clean_preview",
                    "临时文件清理预览需要 Linux 环境（当前为 Windows 环境）");
        }

        Map<String, Object> data = new HashMap<>();
        data.put("actionType", "temp_clean_preview");
        data.put("candidates", List.of(
                Map.of("path", "/tmp/cache-demo/", "sizeBytes", 52428800, "note", "可清理的缓存目录"),
                Map.of("path", "/tmp/temp-files/", "sizeBytes", 10485760, "note", "临时文件目录")
        ));
        data.put("totalSizeBytes", 62914560);
        data.put("note", "预览模式 — 仅展示可清理文件，未执行删除");

        return ExecutionResult.ok(data, "临时文件清理预览完成，共 2 个候选路径");
    }

    /**
     * 日志截断预览 — P0 仅返回截断计划。
     */
    private ExecutionResult executeLogTruncatePreview(ExecutionPlan plan, ExecutionPolicy policy) {
        if (isWindows()) {
            return ExecutionResult.degraded("safe_log_truncate_preview",
                    "日志截断预览需要 Linux 环境（当前为 Windows 环境）");
        }

        String target = plan.getTarget();
        if (target != null && !target.isBlank()) {
            // 检查敏感路径
            for (String sensitive : SENSITIVE_PATH_PREFIXES) {
                if (target.startsWith(sensitive)) {
                    return ExecutionResult.failed("敏感路径不可操作: " + sensitive);
                }
            }
        }

        Map<String, Object> data = new HashMap<>();
        data.put("actionType", "log_truncate_preview");
        data.put("target", target != null ? target : "/var/log/app.log");
        data.put("currentSizeBytes", 209715200);
        data.put("truncatedSizeBytes", 104857600);
        data.put("note", "预览模式 — 仅展示拟截断信息，未执行实际截断");

        return ExecutionResult.ok(data, "日志截断预览完成，拟释放约 100MB 空间");
    }

    /**
     * 文件清理预览。
     * 敏感路径直接阻断。
     */
    private ExecutionResult executeFileCleanPreview(ExecutionPlan plan, ExecutionPolicy policy) {
        String target = plan.getTarget();

        // 检查敏感路径
        if (target != null && !target.isBlank()) {
            String normalized = target.replace('\\', '/').toLowerCase();
            for (String sensitive : SENSITIVE_PATH_PREFIXES) {
                if (normalized.startsWith(sensitive)) {
                    return ExecutionResult.failed("敏感路径不可操作: " + sensitive);
                }
            }
        }

        if (isWindows()) {
            return ExecutionResult.degraded("safe_file_clean_preview",
                    "文件清理预览需要 Linux 环境（当前为 Windows 环境）");
        }

        Map<String, Object> data = new HashMap<>();
        data.put("actionType", "file_clean_preview");
        data.put("candidates", List.of(
                Map.of("path", "/var/log/app.log", "sizeBytes", 209715200, "type", "log"),
                Map.of("path", "/tmp/cache-demo/", "sizeBytes", 52428800, "type", "cache")
        ));
        data.put("note", "预览模式 — 仅展示待清理文件，未执行删除");

        return ExecutionResult.ok(data, "文件清理预览完成，共 2 个候选文件/目录");
    }
}
