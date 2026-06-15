package com.kylinops.executor;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * ExecutionAttempt Repository
 */
@Repository
public interface ExecutionAttemptRepository extends JpaRepository<ExecutionAttempt, Long> {

    /** 按 attemptId 查询 */
    Optional<ExecutionAttempt> findByAttemptId(String attemptId);

    /** 按 auditId 查询（一个确认流程至多一条） */
    Optional<ExecutionAttempt> findByAuditId(String auditId);
}
