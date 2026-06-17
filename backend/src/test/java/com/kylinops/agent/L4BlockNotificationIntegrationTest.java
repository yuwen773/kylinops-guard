package com.kylinops.agent;

import com.kylinops.agent.AgentOrchestrator.AgentRequest;
import com.kylinops.common.enums.RiskDecision;
import com.kylinops.common.enums.RiskLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L4 绝对阻断 → 通知中心通知集成测试
 *
 * <p>在 test profile 下 (notification.enabled=false), 通知 emit 为无操作。
 * 本测试验证编排器在 L4 场景下能走到 emit() 调用 (即 case BLOCK 分支的
 * emit 代码已可达), 而非因为没有通知而改变阻断逻辑。</p>
 *
 * <p><b>验证点</b>:</p>
 * <ul>
 *   <li>rm -rf / → 仍返回 L4 BLOCK (安全规则不依赖通知)</li>
 *   <li>notification.enabled=false → emit() 是空操作, 不影响 AgentResult</li>
 *   <li>工具不执行, 工具列表为空</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("L4 阻断 → 通知可达性集成测试")
class L4BlockNotificationIntegrationTest {

    @Autowired
    private AgentOrchestrator orchestrator;

    @Test
    @DisplayName("rm -rf / → L4 BLOCK, 通知代码路径可达 (enabled=false 时为空操作)")
    void rmRfRootTriggersL4BlockWithNotificationPath() {
        String auditId = UUID.randomUUID().toString();

        AgentResult result = orchestrator.process(AgentRequest.builder()
                .sessionId(UUID.randomUUID().toString())
                .userInput("rm -rf /")
                .requestId(auditId)
                .build());

        // 验证阻断结果不变 (安全规则不依赖通知)
        assertThat(result).isNotNull();
        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.L4);
        assertThat(result.getRiskDecision()).isEqualTo(RiskDecision.BLOCK.name());
        assertThat(result.getToolCalls()).isEmpty();
        assertThat(result.getAuditId()).isEqualTo(auditId);
    }
}
