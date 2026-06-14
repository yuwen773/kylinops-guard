package com.kylinops.executor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kylinops.agent.ToolPlanningService.ToolPlan;
import com.kylinops.agent.ToolPlanningService.ActionPlan;
import com.kylinops.audit.AuditLogService;
import com.kylinops.common.BusinessException;
import com.kylinops.common.enums.AuditStatus;
import com.kylinops.common.enums.IntentType;
import com.kylinops.common.enums.RiskDecision;
import com.kylinops.common.enums.RiskLevel;
import com.kylinops.security.RiskCheckResult;
import com.kylinops.security.RiskCheckService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class ActionConfirmService {

    private static final long DEFAULT_TIMEOUT_MINUTES = 5;
    private static final long MAX_PENDING_PER_SESSION = 10;

    private final PendingActionRepository repository;
    private final ObjectMapper objectMapper;
    private final RiskCheckService riskCheckService;
    private final SafeExecutor safeExecutor;
    private final AuditLogService auditLogService;
    private final TransactionTemplate transactionTemplate;

    /**
     * 构造器显式注入 {@link TransactionTemplate}。
     * <p>
     * Spring Boot 自动注入 {@link PlatformTransactionManager}，由其构造 TransactionTemplate。
     * 本服务内部「同类内部调用」无法被 Spring AOP 拦截（self-invocation），
     * 若依赖 {@code @Transactional} 注解，{@link #confirmAction} 内部对
     * {@link #claimForExecution} / {@link #finalizeAction} / {@link #cancelWaitingAction}
     * 的 {@code this.xxx()} 调用会绕过代理，事务边界形同虚设。
     * 改用 TransactionTemplate 包裹，可显式控制事务边界，确保 short transaction 设计意图生效。
     * </p>
     */
    public ActionConfirmService(PendingActionRepository repository,
                                ObjectMapper objectMapper,
                                RiskCheckService riskCheckService,
                                SafeExecutor safeExecutor,
                                AuditLogService auditLogService,
                                PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.riskCheckService = riskCheckService;
        this.safeExecutor = safeExecutor;
        this.auditLogService = auditLogService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public PendingAction createAction(String auditId, String sessionId, AuthenticatedOperator operator,
                                      String actionType, String toolName,
                                      Map<String, Object> params, RiskLevel riskLevel) {
        return transactionTemplate.execute(status -> {
            long pendingCount = repository.countBySessionIdAndStatus(sessionId, PendingActionStatus.WAITING);
            if (pendingCount >= MAX_PENDING_PER_SESSION) {
                throw new IllegalStateException(
                        "session pending action limit reached (" + MAX_PENDING_PER_SESSION + ")");
            }

            PendingAction action = new PendingAction();
            action.setActionId(UUID.randomUUID().toString());
            action.setAuditId(auditId);
            action.setSessionId(sessionId);
            action.setCreatorPrincipal(operator.principal());
            action.setCreatorAuthSessionId(operator.authSessionId());
            action.setActionType(actionType);
            action.setToolName(toolName);
            action.setParamsJson(toJson(params));
            action.setRiskLevel(riskLevel);
            action.setStatus(PendingActionStatus.WAITING);
            action.setExpiresAt(LocalDateTime.now().plusMinutes(DEFAULT_TIMEOUT_MINUTES));
            return repository.save(action);
        });
    }

    /**
     * Confirms and executes, or cancels, the server-persisted action.
     * The request contributes no executable fields beyond actionId and confirm.
     * <p>
     * 拆分三段短事务：
     * <ol>
     *   <li>{@link #claimForExecution} —— 短事务，原子 CAS 校验 status + expiresAt；</li>
     *   <li>{@link #executeConfirmedAction} —— 无事务，跑 riskCheck / safeExecutor；</li>
     *   <li>{@link #finalizeAction} —— 短事务，状态/审计落库。</li>
     * </ol>
     * 公开方法自身不带事务，事务边界由三个私有方法各自通过 {@link TransactionTemplate} 独立控制。
     * </p>
     * <p>
     * 关键：{@code finalizeAction} / {@code cancelWaitingAction} 内 audit 写入失败时，
     * 异常向上传播（不再 try/catch 吞掉），由本事务回滚让 status 保持中间态
     * （EXECUTING / WAITING）。调用方 {@link ActionConfirmController#confirmAction}
     * 当前不处理 RuntimeException，audit 失败会经由
     * {@link com.kylinops.common.GlobalExceptionHandler#handleUnhandledException}
     * 返回 HTTP 500，pendingAction 状态保持 EXECUTING/WAITING 可由 sweeper
     * 后续清理。Phase 3 再考虑在 controller 层降级返回（带降级 audit 行）。
     * </p>
     */
    public PendingAction confirmAction(String actionId, boolean confirm, AuthenticatedOperator operator) {
        if (!confirm) {
            return cancelWaitingAction(actionId, operator);
        }
        PendingAction claimed = claimForExecution(actionId, operator);
        ExecutionOutcome outcome = executeConfirmedAction(claimed);
        return finalizeAction(claimed, outcome);
    }

    /**
     * 取消路径：把等待中的 action 原子改为 CANCELLED。
     * <p>
     * 走专属短事务，复用 {@link PendingActionRepository#claimWaitingAction} 的
     * 「status + expiresAt」原子语义：已过期 action 不会先被 cancel、再被 sweeper 标 EXPIRED
     * 导致状态抖动。
     * </p>
     * <p>
     * audit 写入失败时异常向上传播，事务回滚让 status 保持 WAITING。
     * </p>
     */
    public PendingAction cancelWaitingAction(String actionId, AuthenticatedOperator operator) {
        return transactionTemplate.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            int cancelled = repository.claimOwnedWaitingAction(
                    actionId, PendingActionStatus.WAITING, PendingActionStatus.CANCELLED, now,
                    operator.principal(), operator.authSessionId());
            if (cancelled != 1) {
                throw claimFailedWithOwnershipCheck(actionId, operator, now);
            }
            PendingAction cancelledAction = findByActionId(actionId);
            // 不再 try/catch：audit 失败 → 异常向上传播 → 事务回滚 → status 仍是 WAITING。
            // 异常经 GlobalExceptionHandler 转 HTTP 500，sweeper 后续清理。
            auditLogService.updateAuditConfirmation(
                    cancelledAction.getAuditId(), true, PendingActionStatus.CANCELLED.name());
            auditLogService.updateAuditLog(
                    cancelledAction.getAuditId(), cancelledAction.getRiskLevel(), RiskDecision.CONFIRM,
                    cancelledAction.getActionType(), AuditStatus.CANCELLED, "action cancelled");
            return cancelledAction;
        });
    }

    /**
     * 第一段：原子 claim 等待中的 action。
     * <p>
     * 单条 SQL 同时校验 status=WAITING 与 expiresAt&gt;now，过期或被并发 claim 都会返回 0。
     * 错误消息区分「已过期」与「已被并发 claim」两种情形，方便调用方和审计定位。
     * </p>
     */
    public PendingAction claimForExecution(String actionId, AuthenticatedOperator operator) {
        return transactionTemplate.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            int claimed = repository.claimOwnedWaitingAction(
                    actionId, PendingActionStatus.WAITING, PendingActionStatus.EXECUTING, now,
                    operator.principal(), operator.authSessionId());
            if (claimed != 1) {
                throw claimFailedWithOwnershipCheck(actionId, operator, now);
            }
            // 同一事务内 findByActionId，可见 claim 后的最新状态。
            return findByActionId(actionId);
        });
    }

    /**
     * 第二段：业务执行，**不带事务**。
     * <p>
     * 避免在 riskCheck / safeExecutor 期间持有 DB 连接和事务；本方法只读已 claim 的实体
     * 并构造返回值，不写库。
     * </p>
     */
    public ExecutionOutcome executeConfirmedAction(PendingAction claimed) {
        Map<String, Object> params;
        try {
            auditLogService.requireAuditLog(claimed.getAuditId());
            if (claimed.getRiskLevel() != RiskLevel.L2) {
                return ExecutionOutcome.failed(AuditStatus.BLOCKED, RiskDecision.BLOCK,
                        "persisted action is not L2");
            }
            params = parseParams(claimed.getParamsJson());
            RiskCheckResult risk = recheckPersistedAction(claimed);
            if (risk.getRiskLevel() != RiskLevel.L2 || risk.getDecision() != RiskDecision.CONFIRM) {
                String reason = risk != null && risk.getReason() != null
                        ? risk.getReason() : "risk recheck did not return L2/CONFIRM";
                return ExecutionOutcome.failed(AuditStatus.BLOCKED, RiskDecision.BLOCK, reason);
            }
        } catch (Exception e) {
            log.warn("confirmed action risk recheck failed: actionId={}", claimed.getActionId(), e);
            return ExecutionOutcome.failed(AuditStatus.FAILED, RiskDecision.BLOCK,
                    "risk recheck failed: " + e.getMessage());
        }

        ExecutionPlan plan = ExecutionPlan.builder()
                .actionType(claimed.getActionType())
                .target(resolveTarget(claimed, params))
                .params(params)
                .auditId(claimed.getAuditId())
                .build();

        try {
            ExecutionResult result = safeExecutor.execute(plan);
            return ExecutionOutcome.executed(result);
        } catch (Exception e) {
            log.error("controlled action execution failed: actionId={}", claimed.getActionId(), e);
            return ExecutionOutcome.failed(AuditStatus.FAILED, RiskDecision.BLOCK,
                    "execution failed: " + e.getMessage());
        }
    }

    /**
     * 第三段：把执行结果原子落到 DB 与审计。
     * <p>
     * 短事务：只做 save + auditLogService.update*；不调用 safeExecutor。
     * </p>
     * <p>
     * audit 写入失败时异常向上传播，事务回滚让 status 保持 EXECUTING，
     * 下次重试 / sweeper / 监控可见中间态。
     * </p>
     */
    public PendingAction finalizeAction(PendingAction claimed, ExecutionOutcome outcome) {
        return transactionTemplate.execute(status -> {
            // 短事务内重读，确保 createdAt / updatedAt 由 JPA 正确管理。
            PendingAction toSave = findByActionId(claimed.getActionId());

            if (outcome.isExecuted()) {
                String resultJson = toJson(outcome.getResult());
                toSave.setStatus(outcome.nextStatus());
                toSave.setExecutionResult(resultJson);
                PendingAction saved = repository.save(toSave);
                AuditStatus auditStatus = outcome.getResult().isSuccess()
                        ? AuditStatus.SUCCESS : AuditStatus.FAILED;
                // 不再 try/catch：audit 失败 → 异常向上传播 → 事务回滚 → status 仍是 EXECUTING。
                auditLogService.updateAuditConfirmation(
                        saved.getAuditId(), true, saved.getStatus().name());
                auditLogService.updateAuditResult(
                        saved.getAuditId(), resultJson, outcome.getResult().getSummary());
                auditLogService.updateAuditLog(
                        saved.getAuditId(), RiskLevel.L2, RiskDecision.CONFIRM,
                        saved.getActionType(), auditStatus, outcome.getResult().getSummary());
                return saved;
            }
            // 未到执行阶段（risk recheck 失败 / 非 L2）→ 走 FAILED 落库
            String reason = outcome.getReason();
            toSave.setStatus(PendingActionStatus.FAILED);
            toSave.setExecutionResult(reason);
            PendingAction saved = repository.save(toSave);
            // 同上：audit 失败异常向上传播，事务回滚让 status 保持 EXECUTING。
            auditLogService.updateAuditConfirmation(
                    saved.getAuditId(), true, PendingActionStatus.FAILED.name());
            auditLogService.updateAuditResult(saved.getAuditId(), reason, reason);
            auditLogService.updateAuditLog(
                    saved.getAuditId(), RiskLevel.L3, outcome.getDecision(),
                    saved.getActionType(), outcome.getAuditStatus(), reason);
            return saved;
        });
    }

    public boolean expireIfNeeded(String actionId) {
        Boolean result = transactionTemplate.execute(status -> {
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
        });
        return Boolean.TRUE.equals(result);
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

    /**
     * claim 失败的错误分流：先 re-read 当前状态，再决定抛哪种 message。
     * <p>
     * claim SQL 同时校验 status=WAITING + expiresAt&gt;now。
     * 当 claim 返回 0 时，状态可能是「已 EXECUTING/SUCCESS/FAILED/CANCELLED（并发）」，
     * 也可能是「WAITING 但已过期（TOCTOU 窗口）」，再或者是「已被 sweeper 标 EXPIRED」。
     * 错误消息按当前 DB 状态分流，避免误导调用方。
     * </p>
     */
    /**
     * claim 失败时先检查是否为跨会话归属不匹配，再 fallback 到 {@link #claimFailedError}。
     * <p>
     * {@link PendingActionRepository#claimOwnedWaitingAction} 的 WHERE 子句同时校验
     * status、expiresAt、creatorPrincipal、creatorAuthSessionId。当返回 0 时，
     * 可能是过期、被并发 claim、或归属不匹配。本方法先排查归属不匹配（403），
     * 再由 claimFailedError 区分过期 vs 并发（400）。
     * </p>
     */
    private RuntimeException claimFailedWithOwnershipCheck(String actionId,
                                                                   AuthenticatedOperator operator,
                                                                   LocalDateTime now) {
        PendingAction current = findByActionId(actionId);
        // 状态 WAITING + 未过期 + 归属不匹配 → 403
        if (current.getStatus() == PendingActionStatus.WAITING
                && current.getExpiresAt().isAfter(now)) {
            if (!operator.principal().equals(current.getCreatorPrincipal())) {
                return BusinessException.forbidden(
                        "pending action was created by a different user");
            }
            if (!operator.authSessionId().equals(current.getCreatorAuthSessionId())) {
                return BusinessException.forbidden(
                        "pending action was created in a different session");
            }
        }
        // 过期/并发 → 400 (IllegalStateException)
        return claimFailedError(actionId);
    }

    private IllegalStateException claimFailedError(String actionId) {
        PendingAction current = findByActionId(actionId);
        if (current.getStatus() == PendingActionStatus.EXPIRED) {
            return new IllegalStateException("pending action expired: " + actionId);
        }
        if (current.getStatus() == PendingActionStatus.WAITING
                && !current.getExpiresAt().isAfter(LocalDateTime.now())) {
            // claim SQL 拒绝，原因是 expiresAt 已过；让上层 sweeper / 下次 claim 兜底
            return new IllegalStateException("pending action expired: " + actionId);
        }
        return new IllegalStateException(
                "pending action already processed: " + current.getStatus());
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
