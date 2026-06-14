package com.kylinops.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kylinops.agent.ToolPlanningService.ToolPlan;
import com.kylinops.audit.AuditLog;
import com.kylinops.audit.AuditLogRepository;
import com.kylinops.audit.AuditLogService;
import com.kylinops.common.BusinessException;
import com.kylinops.common.enums.AuditStatus;
import com.kylinops.common.enums.IntentType;
import com.kylinops.common.enums.RiskDecision;
import com.kylinops.common.enums.RiskLevel;
import com.kylinops.security.RiskCheckResult;
import com.kylinops.security.RiskCheckService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("ActionConfirmService - L2 confirmation execution")
class ActionConfirmServiceTest {

    @Autowired
    private ActionConfirmService actionConfirmService;

    @Autowired
    private PendingActionRepository repository;

    @SpyBean
    private AuditLogService auditLogService;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private RiskCheckService riskCheckService;

    @MockBean
    private SafeExecutor safeExecutor;

    private static final AuthenticatedOperator TEST_OPERATOR =
            new AuthenticatedOperator("test-admin", "test-auth-session-1");
    private static final AuthenticatedOperator OTHER_OPERATOR =
            new AuthenticatedOperator("other-admin", "other-auth-session-2");

    @BeforeEach
    void setUp() {
        reset(riskCheckService, safeExecutor, auditLogService);
        when(riskCheckService.checkPlan(any(ToolPlan.class), anyString(), anyString()))
                .thenReturn(confirmDecision());
        when(safeExecutor.execute(any(ExecutionPlan.class)))
                .thenReturn(ExecutionResult.ok(Map.of("restarted", "nginx"), "restart complete"));
    }

    @Test
    void createsPersistedWaitingAction() {
        PendingAction action = createWaitingAction();

        PendingAction persisted = repository.findByActionId(action.getActionId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(PendingActionStatus.WAITING);
        assertThat(persisted.getParamsJson()).contains("\"serviceName\":\"nginx\"");
        assertThat(persisted.getExpiresAt()).isAfter(LocalDateTime.now());
    }

    @Test
    void confirmationRechecksStoredActionExecutesOnceAndPersistsResultAndAudit() {
        PendingAction action = createWaitingAction();

        PendingAction result = actionConfirmService.confirmAction(action.getActionId(), true, TEST_OPERATOR);

        assertThat(result.getStatus()).isEqualTo(PendingActionStatus.SUCCESS);
        assertThat(result.getExecutionResult()).contains("restart complete");
        verify(riskCheckService).checkPlan(
                any(ToolPlan.class), contains("safe_service_restart"), eq(action.getAuditId()));
        verify(safeExecutor).execute(argThat(plan ->
                plan.getActionType().equals("safe_service_restart")
                        && plan.getTarget().equals("nginx")
                        && plan.getAuditId().equals(action.getAuditId())
                        && "nginx".equals(plan.getParams().get("serviceName"))));

        AuditLog audit = auditLogRepository.findByAuditId(action.getAuditId()).orElseThrow();
        assertThat(audit.getStatus()).isEqualTo(AuditStatus.SUCCESS);
        assertThat(audit.getConfirmationStatus()).isEqualTo(PendingActionStatus.SUCCESS.name());
        assertThat(audit.getExecutionResult()).contains("restart complete");
    }

    @Test
    void cancellationAtomicallyTransitionsAndUpdatesAuditWithoutExecution() {
        PendingAction action = createWaitingAction();

        PendingAction cancelled = actionConfirmService.confirmAction(action.getActionId(), false, TEST_OPERATOR);

        assertThat(cancelled.getStatus()).isEqualTo(PendingActionStatus.CANCELLED);
        verifyNoInteractions(riskCheckService, safeExecutor);
        AuditLog audit = auditLogRepository.findByAuditId(action.getAuditId()).orElseThrow();
        assertThat(audit.getStatus()).isEqualTo(AuditStatus.CANCELLED);
        assertThat(audit.getConfirmationStatus()).isEqualTo(PendingActionStatus.CANCELLED.name());
    }

    @Test
    void expiredActionReturnsBusinessErrorAndNeverExecutes() {
        PendingAction action = createWaitingAction();
        action.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        repository.saveAndFlush(action);

        assertThatThrownBy(() -> actionConfirmService.confirmAction(action.getActionId(), true, TEST_OPERATOR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expired");

        // 拆分后：claimWaitingAction 的 WHERE 包含 expiresAt > now，过期 action
        // 不会被原子 claim 改写，状态保持 WAITING 由 sweeper/expireIfNeeded 兜底。
        assertThat(repository.findByActionId(action.getActionId()).orElseThrow().getStatus())
                .isEqualTo(PendingActionStatus.WAITING);
        verifyNoInteractions(riskCheckService, safeExecutor);
    }

    @Test
    void duplicateConfirmationNeverExecutesTwice() {
        PendingAction action = createWaitingAction();
        actionConfirmService.confirmAction(action.getActionId(), true, TEST_OPERATOR);

        assertThatThrownBy(() -> actionConfirmService.confirmAction(action.getActionId(), true, TEST_OPERATOR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already processed");

        verify(safeExecutor, times(1)).execute(any(ExecutionPlan.class));
    }

    @Test
    void blockedRecheckMarksFailedAndDoesNotExecute() {
        PendingAction action = createWaitingAction();
        when(riskCheckService.checkPlan(any(ToolPlan.class), anyString(), anyString()))
                .thenReturn(RiskCheckResult.block(
                        RiskLevel.L3, java.util.List.of("blocked_action"),
                        "action is no longer permitted", "do not execute"));

        PendingAction result = actionConfirmService.confirmAction(action.getActionId(), true, TEST_OPERATOR);

        assertThat(result.getStatus()).isEqualTo(PendingActionStatus.FAILED);
        assertThat(result.getExecutionResult()).contains("action is no longer permitted");
        verifyNoInteractions(safeExecutor);
        AuditLog audit = auditLogRepository.findByAuditId(action.getAuditId()).orElseThrow();
        assertThat(audit.getStatus()).isEqualTo(AuditStatus.BLOCKED);
        assertThat(audit.getRiskDecision()).isEqualTo(RiskDecision.BLOCK);
    }

    @Test
    void persistedNonL2ActionIsNeverExecutedEvenIfRecheckReturnsConfirm() {
        PendingAction action = createWaitingAction();
        action.setRiskLevel(RiskLevel.L3);
        repository.saveAndFlush(action);

        PendingAction result = actionConfirmService.confirmAction(action.getActionId(), true, TEST_OPERATOR);

        assertThat(result.getStatus()).isEqualTo(PendingActionStatus.FAILED);
        assertThat(result.getExecutionResult()).contains("persisted action is not L2");
        verifyNoInteractions(safeExecutor);
    }

    @Test
    void missingAuditLogPreventsConfirmedExecution() {
        PendingAction action = createWaitingAction();
        auditLogRepository.deleteById(
                auditLogRepository.findByAuditId(action.getAuditId()).orElseThrow().getId());
        auditLogRepository.flush();

        PendingAction result = actionConfirmService.confirmAction(action.getActionId(), true, TEST_OPERATOR);

        assertThat(result.getStatus()).isEqualTo(PendingActionStatus.FAILED);
        assertThat(result.getExecutionResult()).contains("audit");
        verifyNoInteractions(safeExecutor);
    }

    @Test
    @DisplayName("claim 阶段原子拒绝已过期 action —— 单条 SQL 同时检查 status 与 expiresAt")
    void claimActionRejectsExpiredActionAtomically() {
        PendingAction action = createWaitingAction();
        action.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        repository.saveAndFlush(action);

        // 调用 confirmAction 时，claim 步骤的 WHERE 必须包含 expiresAt > now，
        // 避免「先读 snapshot 判定未过期、然后并发推到 claim 时正好过期」的空窗。
        assertThatThrownBy(() -> actionConfirmService.confirmAction(action.getActionId(), true, TEST_OPERATOR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expired");

        // 业务约束：action 已被 service 路径标为 EXPIRED；riskCheck/safeExecutor 都未被触发。
        assertThat(repository.findByActionId(action.getActionId()).orElseThrow().getStatus())
                .isIn(PendingActionStatus.EXPIRED, PendingActionStatus.WAITING);
        verifyNoInteractions(riskCheckService, safeExecutor);
    }

    @Test
    @DisplayName("错误消息区分『已过期』与『被并发 claim』")
    void errorMessageDistinguishesExpiredFromConcurrentClaim() {
        // Case 1: 已过期 → 消息应包含 "expired"，不应包含 "already processed"。
        PendingAction expired = createWaitingAction();
        expired.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        repository.saveAndFlush(expired);

        assertThatThrownBy(() -> actionConfirmService.confirmAction(expired.getActionId(), true, TEST_OPERATOR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expired")
                .hasMessageNotContaining("already processed");

        // Case 2: 第一次 confirm 成功后再次 confirm → 消息应包含 "already processed"。
        PendingAction claimed = createWaitingAction();
        actionConfirmService.confirmAction(claimed.getActionId(), true, TEST_OPERATOR);

        assertThatThrownBy(() -> actionConfirmService.confirmAction(claimed.getActionId(), true, TEST_OPERATOR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already processed")
                .hasMessageNotContaining("expired");
    }

    @Test
    void concurrentConfirmationExecutesAtMostOnce() throws Exception {
        PendingAction action = createWaitingAction();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = pool.submit(() -> confirmAfter(start, action.getActionId()));
            Future<Object> second = pool.submit(() -> confirmAfter(start, action.getActionId()));
            start.countDown();

            Object firstResult = first.get();
            Object secondResult = second.get();

            assertThat(java.util.List.of(firstResult, secondResult)
                    .stream().filter(PendingAction.class::isInstance).count()).isEqualTo(1);
            assertThat(java.util.List.of(firstResult, secondResult)
                    .stream().filter(IllegalStateException.class::isInstance).count()).isEqualTo(1);
            verify(safeExecutor, times(1)).execute(any(ExecutionPlan.class));
            assertThat(repository.findByActionId(action.getActionId()).orElseThrow().getStatus())
                    .isEqualTo(PendingActionStatus.SUCCESS);
        } finally {
            pool.shutdownNow();
        }
    }

    private Object confirmAfter(CountDownLatch start, String actionId) {
        try {
            start.await();
            return actionConfirmService.confirmAction(actionId, true, TEST_OPERATOR);
        } catch (Exception e) {
            return e;
        }
    }

    @Test
    @DisplayName("finalizeAction 阶段 audit 失败 → pendingAction.status 事务回滚保持 EXECUTING，异常向上传播")
    void finalizeActionRollsBackPendingStatusWhenAuditUpdateThrows() {
        PendingAction action = createWaitingAction();

        // finalizeAction 内会先调 updateAuditConfirmation 写入 CONFIRMED 阶段，
        // 让它在 confirm=true 路径抛 RuntimeException，整个事务应回滚。
        doThrow(new RuntimeException("simulated audit confirmation failure"))
                .when(auditLogService).updateAuditConfirmation(
                        eq(action.getAuditId()), anyBoolean(), anyString());

        // 业务执行（safeExecutor）正常发生一次；audit 写入阶段抛异常向上传播。
        assertThatThrownBy(() -> actionConfirmService.confirmAction(action.getActionId(), true, TEST_OPERATOR))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("simulated audit confirmation failure");

        // 关键断言：事务回滚 → DB 里 status 仍是 EXECUTING（不是 SUCCESS/FAILED）。
        PendingAction reloaded = repository.findByActionId(action.getActionId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PendingActionStatus.EXECUTING);
        // 执行结果 JSON 也应被回滚（与 status 在同一事务内 save）。
        assertThat(reloaded.getExecutionResult()).isNull();

        // 业务执行确实发生过（一次），但落库的事务回滚让 effect 不可见。
        verify(safeExecutor, times(1)).execute(any(ExecutionPlan.class));
    }

    @Test
    @DisplayName("cancelWaitingAction 阶段 audit 失败 → pendingAction.status 事务回滚保持 WAITING，异常向上传播")
    void cancelWaitingActionRollsBackStatusWhenAuditThrows() {
        PendingAction action = createWaitingAction();

        doThrow(new RuntimeException("simulated cancel audit failure"))
                .when(auditLogService).updateAuditConfirmation(
                        eq(action.getAuditId()), anyBoolean(), anyString());

        assertThatThrownBy(() -> actionConfirmService.confirmAction(action.getActionId(), false, TEST_OPERATOR))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("simulated cancel audit failure");

        // 关键断言：cancel 事务回滚 → status 仍是 WAITING（不是 CANCELLED）。
        PendingAction reloaded = repository.findByActionId(action.getActionId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PendingActionStatus.WAITING);
        verifyNoInteractions(riskCheckService, safeExecutor);
    }

    @Test
    @DisplayName("跨认证会话确认 → BusinessException(403)")
    void crossSessionConfirmationThrowsForbidden() {
        PendingAction action = createWaitingAction();

        // 用 OTHER_OPERATOR（不同 principal + authSessionId）确认
        assertThatThrownBy(() -> actionConfirmService.confirmAction(
                action.getActionId(), true, OTHER_OPERATOR))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", 403)
                .hasMessageContaining("different");

        // 确认动作未被 claim，仍为 WAITING
        PendingAction reloaded = repository.findByActionId(action.getActionId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PendingActionStatus.WAITING);
        verifyNoInteractions(riskCheckService, safeExecutor);
    }

    @Test
    @DisplayName("跨认证会话取消 → BusinessException(403)")
    void crossSessionCancellationThrowsForbidden() {
        PendingAction action = createWaitingAction();

        assertThatThrownBy(() -> actionConfirmService.confirmAction(
                action.getActionId(), false, OTHER_OPERATOR))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", 403)
                .hasMessageContaining("different");

        PendingAction reloaded = repository.findByActionId(action.getActionId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PendingActionStatus.WAITING);
        verifyNoInteractions(riskCheckService, safeExecutor);
    }

    private PendingAction createWaitingAction() {
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
