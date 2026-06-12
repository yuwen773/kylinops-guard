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
     * <p>
     * 候选列表来自 plan.getTarget()（不再硬编码）。P0 阶段不接真实 du/scandir，
     * 因此 sizeBytes 标 null 并在 note 中说明。Phase 3 接入真实扫描后填充 sizeBytes。
     * </p>
     */
    private ExecutionResult executeTempCleanPreview(ExecutionPlan plan, ExecutionPolicy policy) {
        String target = plan.getTarget();
        boolean hasTarget = target != null && !target.isBlank();

        List<Map<String, Object>> candidates;
        String note;
        if (hasTarget) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("path", target);
            entry.put("sizeBytes", null); // P0 阶段不扫描
            entry.put("note", "P0 阶段未扫描真实大小");
            candidates = List.of(entry);
            note = "预览模式 — 候选为用户指定的 target，未做真实大小扫描";
        } else {
            candidates = List.of();
            note = "预览模式 — 未提供 target，candidates 为空（待 Phase 3 接入自动扫描）";
        }

        Map<String, Object> data = new HashMap<>();
        data.put("actionType", "temp_clean_preview");
        data.put("candidates", candidates);
        data.put("totalSizeBytes", null); // P0 阶段不聚合；Phase 3 接入 du 后填实
        data.put("note", note);

        if (isWindows()) {
            return ExecutionResult.okWithDegraded(data,
                    "临时文件清理预览完成（Windows 降级，未执行真实扫描）",
                    "临时文件清理预览需要 Linux 环境（当前为 Windows 环境）");
        }

        return ExecutionResult.ok(data, "临时文件清理预览完成");
    }

    /**
     * 日志截断预览 — P0 仅返回截断计划。
     * <p>
     * target 来自 plan.getTarget()（不再硬编码 /var/log/app.log）。
     * currentSizeBytes 标 null：P0 阶段不假装知道真实大小，Phase 3 接入 du 后填实。
     * </p>
     */
    private ExecutionResult executeLogTruncatePreview(ExecutionPlan plan, ExecutionPolicy policy) {
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
        data.put("target", target); // 直接使用 plan.getTarget()，可能为 null
        data.put("currentSizeBytes", null); // P0 阶段未做真实扫描；不返回 209715200 这种假数据
        data.put("truncatedSizeBytes", null); // P0 阶段未做真实截断计划
        data.put("note", "预览模式 — P0 阶段未做真实扫描；待 Phase 3 接入 du 后填实 currentSizeBytes");

        if (isWindows()) {
            return ExecutionResult.okWithDegraded(data,
                    "日志截断预览完成（Windows 降级，未执行真实扫描）",
                    "日志截断预览需要 Linux 环境（当前为 Windows 环境）");
        }

        return ExecutionResult.ok(data, "日志截断预览完成");
    }

    /**
     * 文件清理预览。敏感路径直接阻断。
     * <p>
     * 候选列表来自 plan.getTarget()（不再硬编码 /var/log/app.log）。
     * P0 阶段不接真实扫描，sizeBytes 标 null。
     * </p>
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

        boolean hasTarget = target != null && !target.isBlank();
        List<Map<String, Object>> candidates;
        String note;
        if (hasTarget) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("path", target);
            entry.put("sizeBytes", null); // P0 阶段不扫描
            entry.put("type", "user_specified");
            entry.put("note", "P0 阶段未扫描真实大小");
            candidates = List.of(entry);
            note = "预览模式 — 候选为用户指定的 target，未做真实大小扫描";
        } else {
            candidates = List.of();
            note = "预览模式 — 未提供 target，candidates 为空（待 Phase 3 接入自动扫描）";
        }

        Map<String, Object> data = new HashMap<>();
        data.put("actionType", "file_clean_preview");
        data.put("candidates", candidates);
        data.put("note", note);

        if (isWindows()) {
            return ExecutionResult.okWithDegraded(data,
                    "文件清理预览完成（Windows 降级，未执行真实扫描）",
                    "文件清理预览需要 Linux 环境（当前为 Windows 环境）");
        }

        return ExecutionResult.ok(data, "文件清理预览完成");
    }
}
