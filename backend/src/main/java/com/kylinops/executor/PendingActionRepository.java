package com.kylinops.executor;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * PendingAction Repository
 */
@Repository
public interface PendingActionRepository extends JpaRepository<PendingAction, Long> {

    /** 根据 actionId 查询 */
    Optional<PendingAction> findByActionId(String actionId);

    /** 查询某 session 的所有待确认动作 */
    List<PendingAction> findBySessionIdOrderByCreatedAtDesc(String sessionId);

    /** 查询某 auditId 关联的动作 */
    List<PendingAction> findByAuditId(String auditId);

    /** 查询某 session 的 WAITING 状态动作数量 */
    long countBySessionIdAndStatus(String sessionId, PendingActionStatus status);

    /** 查询所有已过期的 WAITING 动作 */
    List<PendingAction> findByStatusAndExpiresAtBefore(PendingActionStatus status, LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update PendingAction p
               set p.status = :targetStatus,
                   p.updatedAt = :now
             where p.actionId = :actionId
               and p.status = :expectedStatus
               and p.expiresAt > :now
            """)
    int transitionActiveAction(@Param("actionId") String actionId,
                               @Param("expectedStatus") PendingActionStatus expectedStatus,
                               @Param("targetStatus") PendingActionStatus targetStatus,
                               @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update PendingAction p
               set p.status = :expiredStatus,
                   p.updatedAt = :now
             where p.actionId = :actionId
               and p.status = :waitingStatus
               and p.expiresAt <= :now
            """)
    int expireWaitingAction(@Param("actionId") String actionId,
                            @Param("waitingStatus") PendingActionStatus waitingStatus,
                            @Param("expiredStatus") PendingActionStatus expiredStatus,
                            @Param("now") LocalDateTime now);
}
