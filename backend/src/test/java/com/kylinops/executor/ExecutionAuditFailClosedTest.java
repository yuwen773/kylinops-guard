package com.kylinops.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kylinops.agent.AgentOrchestrator;
import com.kylinops.agent.AgentResult;
import com.kylinops.agent.ToolPlanningService.ToolPlan;
import com.kylinops.audit.AuditLog;
import com.kylinops.audit.AuditLogRepository;
import com.kylinops.audit.AuditLogService;
import com.kylinops.common.enums.AuditStatus;
import com.kylinops.common.enums.IntentType;
import com.kylinops.common.enums.RiskDecision;
import com.kylinops.common.enums.RiskLevel;
import com.kylinops.security.RiskCheckResult;
import com.kylinops.security.RiskCheckService;
import com.kylinops.tool.ToolExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("P2-T4: 执行前审计失败闭锁")
class ExecutionAuditFailClosedTest {

    @Autowired
    private ActionConfirmService actionConfirmService;

    @Autowired
    private PendingActionRepository pendingActionRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private ExecutionAttemptRepository executionAttemptRepository;

    @Autowired
    private ExecutionOutcomeRepository executionOutcomeRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AgentOrchestrator orchestrator;

    @SpyBean
    private AuditLogService auditLogService;

    @SpyBean
    private ExecutionAttemptRepository attemptRepositorySpy;

    @MockBean
    private RiskCheckService riskCheckService;

    @MockBean
    private SafeExecutor safeExecutor;

    @MockBean
    private ToolExecutor toolExecutor;

    private static final AuthenticatedOperator TEST_OPERATOR =
            new AuthenticatedOperator("test-admin", "test-auth-session-1");

    @BeforeEach
    void setUp() {
        // Reset spies — each test method sets up its own stubs
        reset(auditLogService, attemptRepositorySpy, riskCheckService, safeExecutor, toolExecutor);

        // Default: risk recheck returns CONFIRM, safeExecutor returns success
        when(riskCheckService.checkPlan(any(ToolPlan.class), anyString(), anyString()))
                .thenReturn(confirmDecision());
        when(safeExecutor.execute(any(ExecutionPlan.class)))
                .thenReturn(ExecutionResult.ok(Map.of("restarted", "nginx"), "restart complete"));
    }

    // ==================== Scenario 1: AgentOrchestrator audit creation failure ====================

    @Nested
    @DisplayName("Scenario 1: 审计创建失败阻断 tool chain")
    class AuditCreationFailure {

        @Test
        @DisplayName("createAuditLog 抛出异常 → AgentOrchestrator 返回 BLOCK，不进入 tool chain")
        void auditCreationFailureBlocksToolChain() {
            doThrow(new RuntimeException("simulated DB write failure"))
                    .when(auditLogService).createAuditLog(
                            anyString(), anyString(), anyString(), any(), any());

            AgentOrchestrator.AgentRequest request = AgentOrchestrator.AgentRequest.builder()
                    .sessionId(UUID.randomUUID().toString())
                    .userInput("check health status")
                    .build();

            AgentResult result = orchestrator.process(request);

            assertThat(result.getRiskDecision()).isEqualTo("BLOCK");
            assertThat(result.getToolCalls()).isEmpty();
            assertThat(result.getErrorMessage()).contains("audit creation failed");

            // verify tool chain was never entered
            verify(toolExecutor, never()).execute(anyString(), any(), anyString());
        }
    }

    // ==================== Scenario 2: requireAuditLog not found ====================

    @Nested
    @DisplayName("Scenario 2: 审计记录缺失阻断执行")
    class MissingAuditLog {

        @Test
        @DisplayName("requireAuditLog 找不到审计记录 → executeConfirmedAction 返回 FAILED，不调 SafeExecutor")
        void missingAuditLogPreventsSafeExecutor() {
            PendingAction action = createWaitingAction();
            // 删除审计记录，让 requireAuditLog 抛异常
            auditLogRepository.deleteById(
                    auditLogRepository.findByAuditId(action.getAuditId()).orElseThrow().getId());
            auditLogRepository.flush();

            PendingAction result = actionConfirmService.confirmAction(
                    action.getActionId(), true, TEST_OPERATOR);

            assertThat(result.getStatus()).isEqualTo(PendingActionStatus.FAILED);
            assertThat(result.getExecutionResult()).contains("audit");
            verify(safeExecutor, never()).execute(any(ExecutionPlan.class));

            // ExecutionOutcomeRecord 应被写入（FAILED）
            ExecutionAttempt attempt = executionAttemptRepository
                    .findByAuditId(action.getAuditId()).orElseThrow();
            ExecutionOutcomeRecord outcome = executionOutcomeRepository
                    .findByAttemptId(attempt.getAttemptId()).orElseThrow();
            assertThat(outcome.getStatus()).isEqualTo("FAILED");
            assertThat(outcome.getSummary()).contains("audit");
        }
    }

    // ==================== Scenario 3: ExecutionAttempt write failure ====================

    @Nested
    @DisplayName("Scenario 3: ExecutionAttempt 写入失败")
    class AttemptWriteFailure {

        @Test
        @DisplayName("ExecutionAttempt 写入失败 → confirm 流程抛异常，PendingAction 状态保持 EXECUTING")
        void attemptFailurePreservesExecutingStatus() {
            PendingAction action = createWaitingAction();

            doThrow(new RuntimeException("simulated attempt DB write failure"))
                    .when(attemptRepositorySpy).save(any(ExecutionAttempt.class));

            assertThatThrownBy(() -> actionConfirmService.confirmAction(
                    action.getActionId(), true, TEST_OPERATOR))
                    .isInstanceOf(Exception.class);

            // claim 已成功（WAITING → EXECUTING），但 attempt 写入失败，
            // 所以 status 保持 EXECUTING
            PendingAction reloaded = pendingActionRepository
                    .findByActionId(action.getActionId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(PendingActionStatus.EXECUTING);

            verify(safeExecutor, never()).execute(any(ExecutionPlan.class));
        }
    }

    // ==================== Scenario 4: Normal flow ====================

    @Nested
    @DisplayName("Scenario 4: 正常流程中 execution 审计记录被正确写入")
    class NormalFlow {

        @Test
        @DisplayName("正常确认流程：ExecutionAttempt + ExecutionOutcomeRecord 被正确写入")
        void normalFlowRecordsAttemptAndOutcome() {
            PendingAction action = createWaitingAction();

            PendingAction result = actionConfirmService.confirmAction(
                    action.getActionId(), true, TEST_OPERATOR);

            assertThat(result.getStatus()).isEqualTo(PendingActionStatus.SUCCESS);

            // 验证 ExecutionAttempt 存在
            ExecutionAttempt attempt = executionAttemptRepository
                    .findByAuditId(action.getAuditId()).orElseThrow();
            assertThat(attempt.getAttemptId()).isNotBlank();
            assertThat(attempt.getActionId()).isEqualTo(action.getActionId());
            assertThat(attempt.getActionType()).isEqualTo("safe_service_restart");
            assertThat(attempt.getTargetSummary()).isEqualTo("nginx");

            // 验证 ExecutionOutcomeRecord 存在，并通过 attemptId 关联
            ExecutionOutcomeRecord outcome = executionOutcomeRepository
                    .findByAttemptId(attempt.getAttemptId()).orElseThrow();
            assertThat(outcome.getOutcomeId()).isNotBlank();
            assertThat(outcome.getAttemptId()).isEqualTo(attempt.getAttemptId());
            assertThat(outcome.getStatus()).isEqualTo("SUCCEEDED");
            assertThat(outcome.getEvidenceJson()).isNotBlank();
        }

        @Test
        @DisplayName("失败执行：outcome 记录为 FAILED")
        void failedExecutionRecordsFailedOutcome() {
            PendingAction action = createWaitingAction();
            when(safeExecutor.execute(any(ExecutionPlan.class)))
                    .thenReturn(ExecutionResult.failed("nginx restart failed"));

            PendingAction result = actionConfirmService.confirmAction(
                    action.getActionId(), true, TEST_OPERATOR);

            assertThat(result.getStatus()).isEqualTo(PendingActionStatus.FAILED);

            ExecutionAttempt attempt = executionAttemptRepository
                    .findByAuditId(action.getAuditId()).orElseThrow();
            ExecutionOutcomeRecord outcome = executionOutcomeRepository
                    .findByAttemptId(attempt.getAttemptId()).orElseThrow();
            assertThat(outcome.getStatus()).isEqualTo("FAILED");
        }
    }

    // ==================== helpers ====================

    private PendingAction createWaitingAction() {
        // 使用 real auditLogService（spy reset 后走默认真实实现）
        String auditId = UUID.randomUUID().toString();
        auditLogService.createAuditLog(
                auditId, UUID.randomUUID().toString(), "restart nginx",
                IntentType.SERVICE_DIAGNOSIS, AuditStatus.CONFIRM_PENDING);
        return actionConfirmService.createAction(
                auditId,
                UUID.randomUUID().toString(),
                TEST_OPERATOR,
                "safe_service_restart",
                "nginx",
                Map.of("serviceName", "nginx"),
                RiskLevel.L2);
    }

    private RiskCheckResult confirmDecision() {
        return RiskCheckResult.confirm(
                RiskLevel.L2, "confirmation required", "confirm before execution", null);
    }
}
