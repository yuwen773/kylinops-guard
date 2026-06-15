package com.kylinops.executor;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 执行对账服务 — 只诊断不执行。
 * <p>
 * 查询 dangling EXECUTING 状态的 PendingAction（超过 5 分钟仍未完成），
 * 打日志告警，不自动修复。由运维人员根据日志介入处理。
 * </p>
 *
 * <h3>设计原则</h3>
 * <ul>
 *   <li>只读：不修改任何记录，不做自动修复</li>
 *   <li>幂等：重复调用输出相同日志（不会重复告警同一 action）</li>
 *   <li>轻量：仅查询单表 + 日志，不涉及 RPC / 外部系统</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionReconciliationService {

    private static final long EXECUTING_STALL_THRESHOLD_MINUTES = 5;

    private final PendingActionRepository pendingActionRepository;
    private final ExecutionAttemptRepository executionAttemptRepository;
    private final ExecutionOutcomeRepository executionOutcomeRepository;

    /**
     * 启动时执行一次对账检查（非阻塞，不依赖 scheduler）。
     */
    @PostConstruct
    public void reconcileOnStartup() {
        try {
            reconcileDanglingExecutions();
        } catch (Exception e) {
            log.warn("启动对账检查异常（非关键，不影响启动）", e);
        }
    }

    /**
     * 对账：查找超过阈值的 EXECUTING 状态 action，打日志告警。
     * <p>
     * 利用 {@link PendingActionRepository#findByStatusAndExpiresAtBefore} 的语义：
     * EXECUTING 状态的 {@code expiresAt} 已过期意味着执行超时（normal 路径下
     * 几秒内完成不应超过 5 分钟）。此调用同时过滤 WAITING 过期 action。
     * </p>
     */
    public void reconcileDanglingExecutions() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(EXECUTING_STALL_THRESHOLD_MINUTES);

        // 查找 EXECUTING 状态且 expiresAt 早于阈值的 action
        List<PendingAction> stalled = pendingActionRepository
                .findByStatusAndExpiresAtBefore(PendingActionStatus.EXECUTING, threshold);

        if (stalled.isEmpty()) {
            return;
        }

        log.warn("发现 {} 个 dangling EXECUTING 待确认动作（超过 {} 分钟）",
                stalled.size(), EXECUTING_STALL_THRESHOLD_MINUTES);

        for (PendingAction action : stalled) {
            String attemptSummary = describeAttempt(action.getAuditId());
            log.warn("Dangling action: actionId={}, auditId={}, actionType={}, "
                            + "createdAt={}, expiresAt={}, attempt={}",
                    action.getActionId(), action.getAuditId(), action.getActionType(),
                    action.getCreatedAt(), action.getExpiresAt(), attemptSummary);
        }
    }

    /**
     * 描述某 auditId 关联的执行尝试状态（用于日志输出）。
     */
    private String describeAttempt(String auditId) {
        try {
            var attempt = executionAttemptRepository.findByAuditId(auditId);
            if (attempt.isEmpty()) {
                return "NO_ATTEMPT_RECORDED";
            }
            var outcome = executionOutcomeRepository.findByAttemptId(
                    attempt.get().getAttemptId());
            return outcome.isPresent()
                    ? "OUTCOME_" + outcome.get().getStatus()
                    : "ATTEMPT_STARTED_NO_OUTCOME";
        } catch (Exception e) {
            return "DESCRIBE_FAILED: " + e.getMessage();
        }
    }
}
