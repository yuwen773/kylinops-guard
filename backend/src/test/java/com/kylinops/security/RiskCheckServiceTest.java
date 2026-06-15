package com.kylinops.security;

import com.kylinops.agent.ToolPlanningService.ExecutionMode;
import com.kylinops.agent.ToolPlanningService.ToolPlan;
import com.kylinops.agent.ToolPlanningService.ToolStep;
import com.kylinops.common.enums.IntentType;
import com.kylinops.common.enums.RiskDecision;
import com.kylinops.common.enums.RiskLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RiskCheckService 集成测试
 * <p>
 * 验证重构后的前置风险校验行为：
 * — 注册 L0/L1 工具 → ALLOW
 * — 注册 L2 工具 → CONFIRM（不执行）— 需等 Task 4/5 实现后
 * — 注册 L3/L4 工具 → BLOCK
 * — 未注册工具 → 默认 L3/BLOCK
 * — 危险命令内容覆盖名义 L0 工具 → L4/BLOCK
 * — 每次校验持久化 RiskCheckRecord
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("RiskCheckService — 前置风险校验")
class RiskCheckServiceTest {

    @Autowired
    private RiskCheckService riskCheckService;

    @Autowired
    private RiskCheckRecordRepository recordRepository;

    @Test
    @DisplayName("注册 L0 工具 → ALLOW")
    void registeredL0ToolReturnsAllow() {
        ToolPlan plan = createPlan("cpu_status_tool");
        RiskCheckResult result = riskCheckService.checkPlan(plan, "查看 CPU 状态", UUID.randomUUID().toString());

        assertThat(result.getDecision()).isEqualTo(RiskDecision.ALLOW);
        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.L0);
    }

    @Test
    @DisplayName("注册 L0 只读工具 → 不阻断")
    void registeredL0ToolReadOnly() {
        ToolPlan plan = createPlan("memory_status_tool");
        RiskCheckResult result = riskCheckService.checkPlan(plan, "检查内存", UUID.randomUUID().toString());

        assertThat(result.getDecision()).isEqualTo(RiskDecision.ALLOW);
    }

    @Test
    @DisplayName("未注册工具 → L3/BLOCK")
    void unregisteredToolDefaultsBlock() {
        ToolPlan plan = createPlan("nonexistent_tool");
        RiskCheckResult result = riskCheckService.checkPlan(plan, "test", UUID.randomUUID().toString());

        assertThat(result.getDecision()).isEqualTo(RiskDecision.BLOCK);
        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.L3);
        assertThat(result.getMatchedRules()).contains("unregistered_tool");
    }

    @Test
    @DisplayName("危险命令覆盖 L0 工具 → L4/BLOCK")
    void dangerousCommandOverridesL0Tool() {
        ToolPlan plan = createPlan("disk_usage_tool");
        RiskCheckResult result = riskCheckService.checkPlan(plan, "rm -rf /", UUID.randomUUID().toString());

        assertThat(result.getDecision()).isEqualTo(RiskDecision.BLOCK);
        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.L4);
        assertThat(result.getMatchedRules()).isNotEmpty();
    }

    @Test
    @DisplayName("L0 工具 + 安全输入 → ALLOW")
    void safeToolAndInputReturnsAllow() {
        ToolPlan plan = createPlan("system_info_tool");
        RiskCheckResult result = riskCheckService.checkPlan(plan, "帮我查看系统信息", UUID.randomUUID().toString());

        assertThat(result.getDecision()).isEqualTo(RiskDecision.ALLOW);
    }

    @Test
    @DisplayName("危险命令直接检查 → L4/BLOCK")
    void dangerousContentReturnsBlock() {
        // 空计划 — 内容级检查
        ToolPlan plan = ToolPlan.builder()
                .intent(IntentType.UNKNOWN)
                .steps(List.of())
                .requiresRiskCheck(false)
                .build();
        RiskCheckResult result = riskCheckService.checkPlan(plan, "rm -rf /etc/passwd", UUID.randomUUID().toString());

        assertThat(result.getDecision()).isEqualTo(RiskDecision.BLOCK);
        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.L4);
    }

    @Test
    @DisplayName("正常只读命令 → ALLOW")
    void normalReadCommandReturnsAllow() {
        ToolPlan plan = createPlan("disk_usage_tool");
        RiskCheckResult result = riskCheckService.checkPlan(plan, "帮我查看磁盘状态", UUID.randomUUID().toString());

        assertThat(result.getDecision()).isEqualTo(RiskDecision.ALLOW);
    }

    @Test
    void serviceRestartActionRequiresConfirmation() {
        String auditId = UUID.randomUUID().toString();
        ToolPlan plan = ToolPlan.builder()
                .intent(IntentType.SERVICE_DIAGNOSIS)
                .steps(List.of())
                .action(com.kylinops.agent.ToolPlanningService.ActionPlan.builder()
                        .actionType("safe_service_restart")
                        .target("nginx")
                        .params(Map.of("serviceName", "nginx"))
                        .build())
                .requiresRiskCheck(true)
                .build();

        RiskCheckResult result = riskCheckService.checkPlan(plan, "重启 nginx 服务", auditId);

        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.L2);
        assertThat(result.getDecision()).isEqualTo(RiskDecision.CONFIRM);
        assertThat(result.getMatchedRules()).contains("confirm_service_restart");
        assertThat(recordRepository.findByAuditId(auditId))
                .isNotEmpty()
                .allMatch(record -> auditId.equals(record.getAuditId()));
    }

    @Test
    void persistsCallerAuditId() {
        String auditId = UUID.randomUUID().toString();

        riskCheckService.checkPlan(createPlan("system_info_tool"), "检查系统信息", auditId);

        assertThat(recordRepository.findByAuditId(auditId))
                .isNotEmpty()
                .allMatch(record -> auditId.equals(record.getAuditId()));
    }

    // ==================== 辅助方法 ====================

    private ToolPlan createPlan(String toolName) {
        return ToolPlan.builder()
                .intent(IntentType.SYSTEM_CHECK)
                .steps(List.of(ToolStep.builder()
                        .toolName(toolName)
                        .params(Map.of())
                        .mode(ExecutionMode.PARALLEL)
                        .order(0)
                        .build()))
                .requiresRiskCheck(false)
                .build();
    }
}
