package com.kylinops.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@SpringBootTest
@DisplayName("ActionConfirmService - L2 confirmation execution")
class ActionConfirmServiceTest {

    @Autowired
    private ActionConfirmService actionConfirmService;

    @Autowired
    private PendingActionRepository repository;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private RiskCheckService riskCheckService;

    @MockBean
    private SafeExecutor safeExecutor;

    @BeforeEach
    void setUp() {
        reset(riskCheckService, safeExecutor);
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

        PendingAction result = actionConfirmService.confirmAction(action.getActionId(), true);

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

        PendingAction cancelled = actionConfirmService.confirmAction(action.getActionId(), false);

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

        assertThatThrownBy(() -> actionConfirmService.confirmAction(action.getActionId(), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expired");

        assertThat(repository.findByActionId(action.getActionId()).orElseThrow().getStatus())
                .isEqualTo(PendingActionStatus.EXPIRED);
        verifyNoInteractions(riskCheckService, safeExecutor);
    }

    @Test
    void duplicateConfirmationNeverExecutesTwice() {
        PendingAction action = createWaitingAction();
        actionConfirmService.confirmAction(action.getActionId(), true);

        assertThatThrownBy(() -> actionConfirmService.confirmAction(action.getActionId(), true))
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

        PendingAction result = actionConfirmService.confirmAction(action.getActionId(), true);

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

        PendingAction result = actionConfirmService.confirmAction(action.getActionId(), true);

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

        PendingAction result = actionConfirmService.confirmAction(action.getActionId(), true);

        assertThat(result.getStatus()).isEqualTo(PendingActionStatus.FAILED);
        assertThat(result.getExecutionResult()).contains("audit");
        verifyNoInteractions(safeExecutor);
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
            return actionConfirmService.confirmAction(actionId, true);
        } catch (Exception e) {
            return e;
        }
    }

    private PendingAction createWaitingAction() {
        String auditId = UUID.randomUUID().toString();
        auditLogService.createAuditLog(
                auditId, UUID.randomUUID().toString(), "restart nginx",
                IntentType.SERVICE_DIAGNOSIS, AuditStatus.CONFIRM_PENDING);
        return actionConfirmService.createAction(
                auditId,
                UUID.randomUUID().toString(),
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
