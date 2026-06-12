package com.kylinops.executor;

import com.kylinops.common.enums.AuditStatus;
import com.kylinops.common.enums.RiskDecision;
import lombok.Getter;

/**
 * 第二段（executeConfirmedAction）的返回值：把「是否真正执行了 safeExecutor」与
 * 「成功/失败语义」都收口到一个对象里，第三段（finalizeAction）只需按 outcome 落库。
 * <p>
 * 不持有事务，只描述结果。{@link #isExecuted()} 为 true 时携带
 * {@link ExecutionResult}；为 false 时携带 (auditStatus, decision, reason)。
 * </p>
 */
@Getter
public class ExecutionOutcome {

    private final boolean executed;
    private final ExecutionResult result;
    private final AuditStatus auditStatus;
    private final RiskDecision decision;
    private final String reason;

    private ExecutionOutcome(boolean executed, ExecutionResult result,
                             AuditStatus auditStatus, RiskDecision decision, String reason) {
        this.executed = executed;
        this.result = result;
        this.auditStatus = auditStatus;
        this.decision = decision;
        this.reason = reason;
    }

    public static ExecutionOutcome executed(ExecutionResult result) {
        return new ExecutionOutcome(true, result, null, null, null);
    }

    public static ExecutionOutcome failed(AuditStatus auditStatus, RiskDecision decision, String reason) {
        return new ExecutionOutcome(false, null, auditStatus, decision, reason);
    }

    /**
     * 第三段落库时使用的最终状态：
     * <ul>
     *   <li>executed=true 且 result.success → {@link PendingActionStatus#SUCCESS}</li>
     *   <li>executed=true 且 result.failure → {@link PendingActionStatus#FAILED}</li>
     *   <li>executed=false（risk recheck 失败 / 非 L2） → {@link PendingActionStatus#FAILED}</li>
     * </ul>
     */
    public PendingActionStatus nextStatus() {
        if (executed) {
            return result.isSuccess() ? PendingActionStatus.SUCCESS : PendingActionStatus.FAILED;
        }
        return PendingActionStatus.FAILED;
    }
}
