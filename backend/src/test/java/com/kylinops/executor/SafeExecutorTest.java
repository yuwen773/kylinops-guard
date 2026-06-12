package com.kylinops.executor;

import com.kylinops.config.KylinOpsConfig;
import com.kylinops.os.OsCommandExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.*;

/**
 * SafeExecutor 安全执行器测试
 * <p>
 * 验证白名单动作校验、命令参数安全性和保护路径阻断。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SafeExecutor — 白名单安全执行")
class SafeExecutorTest {

    @Mock
    private KylinOpsConfig config;

    @Mock
    private KylinOpsConfig.Executor executorConfig;

    @Mock
    private OsCommandExecutor commandExecutor;

    private SafeExecutor executor;

    @BeforeEach
    void setUp() {
        lenient().when(config.getExecutor()).thenReturn(executorConfig);
        lenient().when(executorConfig.getWhitelistedServices()).thenReturn(List.of("nginx", "mysql", "redis", "ssh", "docker"));
        executor = new SafeExecutor(config, commandExecutor);
    }

    @Test
    @DisplayName("未知动作 → 拒绝执行")
    void unknownActionRejected() {
        ExecutionPlan plan = ExecutionPlan.builder()
                .actionType("unknown_action")
                .auditId("test-audit")
                .build();

        ExecutionResult result = executor.execute(plan);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("不支持");
    }

    @Test
    @DisplayName("服务名含 shell 语法 → 拒绝")
    void serviceNameWithShellSyntaxRejected() {
        ExecutionPlan plan = ExecutionPlan.builder()
                .actionType("safe_service_restart")
                .target("nginx; rm -rf /")
                .auditId("test-audit")
                .build();

        ExecutionResult result = executor.execute(plan);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("不合法");
    }

    @Test
    @DisplayName("safe_temp_clean_preview → 返回预览，不执行删除")
    void tempCleanPreview() {
        ExecutionPlan plan = ExecutionPlan.builder()
                .actionType("safe_temp_clean_preview")
                .auditId("test-audit")
                .build();

        ExecutionResult result = executor.execute(plan);
        // 在非 Linux 环境返回不支持
        if (!result.isSuccess()) {
            assertThat(result.getErrorMessage()).contains("Windows");
        } else {
            assertThat(result.getData()).isNotNull();
        }
    }

    @Test
    @DisplayName("safe_log_truncate_preview → 返回截断预览")
    void logTruncatePreview() {
        ExecutionPlan plan = ExecutionPlan.builder()
                .actionType("safe_log_truncate_preview")
                .target("/var/log/app.log")
                .auditId("test-audit")
                .build();

        ExecutionResult result = executor.execute(plan);
        // 在非 Linux 环境返回不支持
        if (!result.isSuccess()) {
            assertThat(result.getErrorMessage()).contains("Windows");
        }
    }

    @Test
    @DisplayName("safe_file_clean_preview → 敏感路径阻断")
    void fileCleanSensitivePath() {
        ExecutionPlan plan = ExecutionPlan.builder()
                .actionType("safe_file_clean_preview")
                .target("/etc/passwd")
                .auditId("test-audit")
                .build();

        ExecutionResult result = executor.execute(plan);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("敏感路径");
    }

    @Test
    @DisplayName("非白名单服务名 → 拒绝")
    void nonWhitelistedService() {
        ExecutionPlan plan = ExecutionPlan.builder()
                .actionType("safe_service_restart")
                .target("unknown_service_123")
                .auditId("test-audit")
                .build();

        ExecutionResult result = executor.execute(plan);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("不在白名单");
    }

    @Test
    @DisplayName("Linux 服务重启使用固定参数数组并返回真实执行结果")
    void serviceRestartExecutesFixedCommand() {
        when(commandExecutor.isLinux()).thenReturn(true);
        when(commandExecutor.execute(List.of("systemctl", "restart", "nginx"), 10_000))
                .thenReturn(new OsCommandExecutor.CommandResult(
                        0, List.of(), List.of(), false, null, 1_000_000));

        ExecutionResult result = executor.execute(ExecutionPlan.builder()
                .actionType("safe_service_restart")
                .target("nginx")
                .auditId("audit-restart")
                .build());

        assertThat(result.isSuccess()).isTrue();
        verify(commandExecutor).execute(List.of("systemctl", "restart", "nginx"), 10_000);
    }

    @Test
    @DisplayName("systemctl 失败时受控动作返回失败")
    void serviceRestartPropagatesCommandFailure() {
        when(commandExecutor.isLinux()).thenReturn(true);
        when(commandExecutor.execute(List.of("systemctl", "restart", "nginx"), 10_000))
                .thenReturn(new OsCommandExecutor.CommandResult(
                        1, List.of(), List.of("permission denied"), false,
                        "命令退出码: 1", 1_000_000));

        ExecutionResult result = executor.execute(ExecutionPlan.builder()
                .actionType("safe_service_restart")
                .target("nginx")
                .auditId("audit-restart")
                .build());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("命令退出码");
    }

    // ===== F-005: 预览动作注入 plan.getTarget()（不再硬编码） =====

    @Test
    @DisplayName("safe_temp_clean_preview → candidates 来自 plan.getTarget()，不写死路径")
    @SuppressWarnings("unchecked")
    void tempCleanPreviewUsesTargetFromPlan() {
        ExecutionPlan plan = ExecutionPlan.builder()
                .actionType("safe_temp_clean_preview")
                .target("/var/log/app.log")
                .auditId("test-audit")
                .build();

        ExecutionResult result = executor.execute(plan);

        // 新契约：data 必带（即使在 Windows 降级环境也必须返回结构化数据）
        assertThat(result.getData()).isNotNull();
        Map<String, Object> data = (Map<String, Object>) result.getData();
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) data.get("candidates");
        assertThat(candidates).isNotNull();
        assertThat(candidates).isNotEmpty();
        assertThat(candidates.get(0).get("path")).isEqualTo("/var/log/app.log");
        // P0 阶段不做真实扫描，sizeBytes 应为 null
        assertThat(candidates.get(0).get("sizeBytes")).isNull();
        assertThat(candidates.get(0).get("note")).asString().contains("P0 阶段未扫描真实大小");
    }

    @Test
    @DisplayName("safe_temp_clean_preview → target 为空时 candidates 也为空，不假装有内容")
    @SuppressWarnings("unchecked")
    void tempCleanPreviewHandlesNullTarget() {
        ExecutionPlan plan = ExecutionPlan.builder()
                .actionType("safe_temp_clean_preview")
                .target(null)
                .auditId("test-audit")
                .build();

        ExecutionResult result = executor.execute(plan);

        assertThat(result.getData()).isNotNull();
        Map<String, Object> data = (Map<String, Object>) result.getData();
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) data.get("candidates");
        assertThat(candidates).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("safe_log_truncate_preview → target 来自 plan，不硬编码 /var/log/app.log")
    @SuppressWarnings("unchecked")
    void logTruncatePreviewUsesTargetFromPlan() {
        ExecutionPlan plan = ExecutionPlan.builder()
                .actionType("safe_log_truncate_preview")
                .target("/var/log/app.log")
                .auditId("test-audit")
                .build();

        ExecutionResult result = executor.execute(plan);

        assertThat(result.getData()).isNotNull();
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertThat(data.get("target")).isEqualTo("/var/log/app.log");
    }

    @Test
    @DisplayName("safe_file_clean_preview → candidates 来自 plan.getTarget()，不写死 /var/log/app.log")
    @SuppressWarnings("unchecked")
    void fileCleanPreviewUsesTargetFromPlan() {
        ExecutionPlan plan = ExecutionPlan.builder()
                .actionType("safe_file_clean_preview")
                .target("/var/log/app.log")
                .auditId("test-audit")
                .build();

        ExecutionResult result = executor.execute(plan);

        assertThat(result.getData()).isNotNull();
        Map<String, Object> data = (Map<String, Object>) result.getData();
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) data.get("candidates");
        assertThat(candidates).isNotNull();
        assertThat(candidates).isNotEmpty();
        assertThat(candidates.get(0).get("path")).isEqualTo("/var/log/app.log");
        assertThat(candidates.get(0).get("sizeBytes")).isNull();
    }

    @Test
    @DisplayName("safe_log_truncate_preview → 不假装知道 currentSizeBytes（P0 不扫真实大小）")
    @SuppressWarnings("unchecked")
    void logTruncatePreviewDoesNotLieAboutSize() {
        ExecutionPlan plan = ExecutionPlan.builder()
                .actionType("safe_log_truncate_preview")
                .target("/var/log/app.log")
                .auditId("test-audit")
                .build();

        ExecutionResult result = executor.execute(plan);

        assertThat(result.getData()).isNotNull();
        Map<String, Object> data = (Map<String, Object>) result.getData();
        // P0 阶段不做真实扫描，currentSizeBytes 必须为 null（不返回 209715200 这种假数据）
        assertThat(data.get("currentSizeBytes")).isNull();
        // note 字段说明状态
        assertThat(data.get("note")).asString().contains("P0 阶段未做真实扫描");
    }
}
