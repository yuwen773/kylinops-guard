package com.kylinops.executor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kylinops.agent.ToolPlanningService.ToolPlan;
import com.kylinops.agent.ToolPlanningService.ActionPlan;
import com.kylinops.audit.AuditLogService;
import com.kylinops.common.enums.AuditStatus;
import com.kylinops.common.enums.IntentType;
import com.kylinops.common.enums.RiskDecision;
import com.kylinops.common.enums.RiskLevel;
import com.kylinops.security.RiskCheckResult;
import com.kylinops.security.RiskCheckService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ActionConfirmService {

    private static final long DEFAULT_TIMEOUT_MINUTES = 5;
    private static final long MAX_PENDING_PER_SESSION = 10;

    private final PendingActionRepository repository;
    private final ObjectMapper objectMapper;
    private final RiskCheckService riskCheckService;
    private final SafeExecutor safeExecutor;
    private final AuditLogService auditLogService;

    @Transactional
    public PendingAction createAction(String auditId, String sessionId,
                                      String actionType, String toolName,
                                      Map<String, Object> params, RiskLevel riskLevel) {
        long pendingCount = repository.countBySessionIdAndStatus(sessionId, PendingActionStatus.WAITING);
        if (pendingCount >= MAX_PENDING_PER_SESSION) {
            throw new IllegalStateException(
                    "session pending action limit reached (" + MAX_PENDING_PER_SESSION + ")");
        }

        PendingAction action = new PendingAction();
        action.setActionId(UUID.randomUUID().toString());
        action.setAuditId(auditId);
        action.setSessionId(sessionId);
        action.setActionType(actionType);
        action.setToolName(toolName);
        action.setParamsJson(toJson(params));
        action.setRiskLevel(riskLevel);
        action.setStatus(PendingActionStatus.WAITING);
        action.setExpiresAt(LocalDateTime.now().plusMinutes(DEFAULT_TIMEOUT_MINUTES));
        return repository.save(action);
    }

    /**
     * Confirms and executes, or cancels, the server-persisted action.
     * The request contributes no executable fields beyond actionId and confirm.
     */
    public PendingAction confirmAction(String actionId, boolean confirm) {
        PendingAction persisted = findByActionId(actionId);
        LocalDateTime now = LocalDateTime.now();

        if (persisted.getStatus() != PendingActionStatus.WAITING) {
            throw alreadyProcessed(persisted);
        }
        if (!persisted.getExpiresAt().isAfter(now)) {
            repository.expireWaitingAction(
                    actionId, PendingActionStatus.WAITING, PendingActionStatus.EXPIRED, now);
            auditLogService.updateAuditConfirmation(
                    persisted.getAuditId(), true, PendingActionStatus.EXPIRED.name());
            throw new IllegalStateException("pending action expired: " + actionId);
        }

        if (!confirm) {
            int updated = repository.transitionActiveAction(
                    actionId, PendingActionStatus.WAITING, PendingActionStatus.CANCELLED, now);
            if (updated != 1) {
                throw concurrentStateError(actionId);
            }
            auditLogService.updateAuditConfirmation(
                    persisted.getAuditId(), true, PendingActionStatus.CANCELLED.name());
            auditLogService.updateAuditLog(
                    persisted.getAuditId(), persisted.getRiskLevel(), RiskDecision.CONFIRM,
                    persisted.getActionType(), AuditStatus.CANCELLED, "action cancelled");
            return findByActionId(actionId);
        }

        int claimed = repository.transitionActiveAction(
                actionId, PendingActionStatus.WAITING, PendingActionStatus.EXECUTING, now);
        if (claimed != 1) {
            throw concurrentStateError(actionId);
        }

        PendingAction executing = findByActionId(actionId);
        Map<String, Object> params;
        try {
            auditLogService.requireAuditLog(executing.getAuditId());
            if (executing.getRiskLevel() != RiskLevel.L2) {
                return failAction(executing, AuditStatus.BLOCKED, RiskDecision.BLOCK,
                        "persisted action is not L2");
            }
            params = parseParams(executing.getParamsJson());
            RiskCheckResult risk = recheckPersistedAction(executing);
            if (risk.getRiskLevel() != RiskLevel.L2 || risk.getDecision() != RiskDecision.CONFIRM) {
                return failRiskCheck(executing, risk);
            }
        } catch (Exception e) {
            log.warn("confirmed action risk recheck failed: actionId={}", actionId, e);
            return failAction(executing, AuditStatus.FAILED, RiskDecision.BLOCK,
                    "risk recheck failed: " + e.getMessage());
        }

        ExecutionPlan plan = ExecutionPlan.builder()
                .actionType(executing.getActionType())
                .target(resolveTarget(executing, params))
                .params(params)
                .auditId(executing.getAuditId())
                .build();

        try {
            ExecutionResult result = safeExecutor.execute(plan);
            String resultJson = toJson(result);
            executing.setExecutionResult(resultJson);
            executing.setStatus(result.isSuccess()
                    ? PendingActionStatus.SUCCESS : PendingActionStatus.FAILED);
            PendingAction saved = repository.save(executing);
            AuditStatus auditStatus = result.isSuccess() ? AuditStatus.SUCCESS : AuditStatus.FAILED;
            try {
                auditLogService.updateAuditConfirmation(
                        saved.getAuditId(), true, saved.getStatus().name());
                auditLogService.updateAuditResult(saved.getAuditId(), resultJson, result.getSummary());
                auditLogService.updateAuditLog(
                        saved.getAuditId(), RiskLevel.L2, RiskDecision.CONFIRM,
                        saved.getActionType(), auditStatus, result.getSummary());
            } catch (Exception auditException) {
                log.error("controlled action audit finalization failed: actionId={}",
                        actionId, auditException);
            }
            return saved;
        } catch (Exception e) {
            log.error("controlled action execution failed: actionId={}", actionId, e);
            return failAction(executing, AuditStatus.FAILED, RiskDecision.BLOCK,
                    "execution failed: " + e.getMessage());
        }
    }

    public PendingAction cancelAction(String actionId) {
        return confirmAction(actionId, false);
    }

    public PendingAction markConfirmed(String actionId) {
        return confirmAction(actionId, true);
    }

    @Transactional
    public boolean expireIfNeeded(String actionId) {
        PendingAction action = repository.findByActionId(actionId).orElse(null);
        if (action == null) {
            return false;
        }
        int updated = repository.expireWaitingAction(
                actionId, PendingActionStatus.WAITING, PendingActionStatus.EXPIRED, LocalDateTime.now());
        if (updated == 1) {
            auditLogService.updateAuditConfirmation(
                    action.getAuditId(), true, PendingActionStatus.EXPIRED.name());
        }
        return updated == 1;
    }

    public PendingAction findByActionId(String actionId) {
        return repository.findByActionId(actionId)
                .orElseThrow(() -> new IllegalArgumentException("pending action not found: " + actionId));
    }

    private RiskCheckResult recheckPersistedAction(PendingAction action) {
        ToolPlan noToolPlan = ToolPlan.builder()
                .intent(IntentType.COMMAND_EXECUTION)
                .steps(List.of())
                .action(ActionPlan.builder()
                        .actionType(action.getActionType())
                        .target(action.getToolName())
                        .params(parseParamsUnchecked(action.getParamsJson()))
                        .build())
                .requiresRiskCheck(true)
                .build();
        String actionContext = action.getActionType() + " " + action.getParamsJson();
        return riskCheckService.checkPlan(noToolPlan, actionContext, action.getAuditId());
    }

    private Map<String, Object> parseParamsUnchecked(String paramsJson) {
        try {
            return parseParams(paramsJson);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("invalid persisted action params", e);
        }
    }

    private PendingAction failRiskCheck(PendingAction action, RiskCheckResult risk) {
        String reason = risk != null && risk.getReason() != null
                ? risk.getReason() : "risk recheck did not return L2/CONFIRM";
        return failAction(action, AuditStatus.BLOCKED, RiskDecision.BLOCK, reason);
    }

    private PendingAction failAction(PendingAction action, AuditStatus auditStatus,
                                     RiskDecision decision, String reason) {
        action.setStatus(PendingActionStatus.FAILED);
        action.setExecutionResult(reason);
        PendingAction saved = repository.save(action);
        auditLogService.updateAuditConfirmation(
                saved.getAuditId(), true, PendingActionStatus.FAILED.name());
        auditLogService.updateAuditResult(saved.getAuditId(), reason, reason);
        auditLogService.updateAuditLog(
                saved.getAuditId(), RiskLevel.L3, decision,
                saved.getActionType(), auditStatus, reason);
        return saved;
    }

    private IllegalStateException concurrentStateError(String actionId) {
        PendingAction current = findByActionId(actionId);
        if (current.getStatus() == PendingActionStatus.EXPIRED) {
            return new IllegalStateException("pending action expired: " + actionId);
        }
        return alreadyProcessed(current);
    }

    private IllegalStateException alreadyProcessed(PendingAction action) {
        return new IllegalStateException(
                "pending action already processed: " + action.getStatus());
    }

    private Map<String, Object> parseParams(String paramsJson) throws JsonProcessingException {
        if (paramsJson == null || paramsJson.isBlank()) {
            return Map.of();
        }
        return objectMapper.readValue(paramsJson, new TypeReference<>() {});
    }

    private String resolveTarget(PendingAction action, Map<String, Object> params) {
        Object serviceName = params.get("serviceName");
        return serviceName != null ? serviceName.toString() : action.getToolName();
    }

    private String toJson(Object value) {
        if (value == null) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize persisted action data", e);
        }
    }
}
