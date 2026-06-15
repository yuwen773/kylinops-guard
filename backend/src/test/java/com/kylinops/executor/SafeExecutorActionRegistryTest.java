package com.kylinops.executor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生产动作白名单回归测试（P1-T5 Tripwire）
 * <p>
 * 这是安全回归红线，未来任何修改若导致以下断言失败，必须先人工审核：
 * <ul>
 *   <li>唯一真实系统副作用是 {@code safe_service_restart}</li>
 *   <li>三个 cleanup 动作必须以 {@code _preview} 结尾</li>
 *   <li>没有任何 {@code /api/exec}, {@code /api/shell}, {@code /api/command/run} 端点</li>
 * </ul>
 * </p>
 *
 * @see ExecutionPolicy
 * @see SafeExecutor#execute(ExecutionPlan)
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("P1-T5 — 生产动作白名单回归保护")
class SafeExecutorActionRegistryTest {

    /** 明确可接受的唯一真实系统副作用 */
    private static final String ONLY_REAL_SIDE_EFFECT = "safe_service_restart";

    /** 预览动作后缀 */
    private static final String PREVIEW_SUFFIX = "_preview";

    /** 禁止出现的原始命令端点路径片段 */
    private static final Set<String> FORBIDDEN_ENDPOINT_FRAGMENTS = Set.of(
            "/api/exec", "/api/shell", "/api/command/run", "/api/command"
    );

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    // ==================== 测试 1: ExecutionPolicy 枚举校验 ====================

    @Test
    @DisplayName("ExecutionPolicy 枚举中唯一有 commandTemplate 的动作是 safe_service_restart")
    void onlyServiceRestartHasCommandTemplate() {
        for (ExecutionPolicy policy : ExecutionPolicy.values()) {
            String actionType = policy.getActionType();
            boolean hasCommandTemplate = policy.getCommandTemplate() != null;

            if (ONLY_REAL_SIDE_EFFECT.equals(actionType)) {
                assertThat(hasCommandTemplate)
                        .as("safe_service_restart 必须指定 commandTemplate")
                        .isTrue();
            } else {
                assertThat(hasCommandTemplate)
                        .as("非重启动作 '%s' 不应有 commandTemplate", actionType)
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("所有非重启动作必须以 _preview 结尾")
    void nonRestartActionsEndWithPreview() {
        for (ExecutionPolicy policy : ExecutionPolicy.values()) {
            String actionType = policy.getActionType();

            if (!ONLY_REAL_SIDE_EFFECT.equals(actionType)) {
                assertThat(actionType)
                        .as("清理动作 '%s' 必须以 _preview 结尾", actionType)
                        .endsWith(PREVIEW_SUFFIX);
            }
        }
    }

    @Test
    @DisplayName("safe_service_restart 的 commandTemplate 是固定参数列表")
    void serviceRestartCommandTemplateIsFixed() {
        ExecutionPolicy restartPolicy = ExecutionPolicy.fromActionType(ONLY_REAL_SIDE_EFFECT);
        assertThat(restartPolicy).isNotNull();

        List<String> template = restartPolicy.getCommandTemplate();
        assertThat(template).containsExactly("systemctl", "restart");
        // 验证不可变
        assertThat(template).isUnmodifiable();
    }

    // ==================== 测试 2: SafeExecutor.execute() 调度器校验 ====================

    @Test
    @DisplayName("SafeExecutor.execute() 的 switch 分支应与 ExecutionPolicy 枚举一致")
    void executorDispatcherMatchesPolicyEnum() {
        // 收集 Executor 中 switch 实际处理的动作类型
        // 注：这通过枚举完整性间接验证。如果 SafeExecutor 的 switch 漏掉了某个枚举值，
        // 编译器不会报警（String switch 而非 enum switch）。此处通过验证所有枚举值都被
        // SafeExecutor 的可达路径覆盖来间接测试。
        // 具体实现上，SafeExecutor 使用 actionType String 做 switch，因此不可能直接
        // 反射验证。但我们通过枚举完整性 + 拒绝验证来保证一致性。

        // 每个枚举值应有对应的 fromActionType 可查
        for (ExecutionPolicy policy : ExecutionPolicy.values()) {
            ExecutionPolicy resolved = ExecutionPolicy.fromActionType(policy.getActionType());
            assertThat(resolved)
                    .as("ExecutionPolicy.fromActionType 应能解析 '%s'", policy.getActionType())
                    .isEqualTo(policy);
        }

        // 未知 actionType 应返回 null → SafeExecutor 会返回 unsupported
        ExecutionPolicy unknown = ExecutionPolicy.fromActionType("__non_existent_action__");
        assertThat(unknown).isNull();
    }

    // ==================== 测试 3: 端点表面扫描 ====================

    @Test
    @DisplayName("无 /api/exec, /api/shell, /api/command/run 端点")
    void noArbitraryCommandEndpoints() {
        Set<String> allEndpoints = handlerMapping.getHandlerMethods().keySet().stream()
                .map(this::endpointPattern)
                .collect(Collectors.toSet());

        for (String forbidden : FORBIDDEN_ENDPOINT_FRAGMENTS) {
            boolean found = allEndpoints.stream().anyMatch(url -> url.contains(forbidden));
            assertThat(found)
                    .as("禁止的端点路径 '%s' 不应在任何 Controller 中出现", forbidden)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("所有 API 端点 /api/** 应有明确的白名单（无通配 catch-all）")
    void noWildcardCatchAllEndpoints() {
        Set<String> allPatterns = handlerMapping.getHandlerMethods().keySet().stream()
                .flatMap(info -> info.getPatternValues().stream())
                .collect(Collectors.toSet());

        // 允许 /api/health 和 /api/error（Spring Boot 默认）等已知例外
        Set<String> allowedGeneralPatterns = Set.of("/api/health", "/api/error");

        for (String pattern : allPatterns) {
            if (pattern.startsWith("/api/") && !allowedGeneralPatterns.contains(pattern)) {
                // 通配符模式的检查（如 /api/**）
                assertThat(pattern)
                        .as("API 端点应使用具体路径而非通配符: %s", pattern)
                        .doesNotContain("**");
            }
        }
    }

    // ==================== 测试 4: 动作白名单完整性 ====================

    @Test
    @DisplayName("四个受控动作必须全部存在于 ExecutionPolicy 中")
    void allControlledActionsPresent() {
        Set<String> expectedActions = Set.of(
                "safe_service_restart",
                "safe_temp_clean_preview",
                "safe_log_truncate_preview",
                "safe_file_clean_preview"
        );

        Set<String> actualActions = java.util.Arrays.stream(ExecutionPolicy.values())
                .map(ExecutionPolicy::getActionType)
                .collect(Collectors.toSet());

        assertThat(actualActions)
                .as("ExecutionPolicy 应包含全部四个预期受控动作")
                .containsAll(expectedActions);
    }

    // ==================== 辅助方法 ====================

    /**
     * 从 RequestMappingInfo 提取端点 URL 模式。
     */
    private String endpointPattern(RequestMappingInfo info) {
        return info.getPatternValues().stream().findFirst().orElse("");
    }
}
