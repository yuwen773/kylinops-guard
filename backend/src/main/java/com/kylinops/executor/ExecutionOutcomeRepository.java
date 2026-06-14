package com.kylinops.executor;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * ExecutionOutcomeRecord Repository
 */
@Repository
public interface ExecutionOutcomeRepository extends JpaRepository<ExecutionOutcomeRecord, Long> {

    /** 按 outcomeId 查询 */
    Optional<ExecutionOutcomeRecord> findByOutcomeId(String outcomeId);

    /** 按 attemptId 查询（1:1 关系） */
    Optional<ExecutionOutcomeRecord> findByAttemptId(String attemptId);
}
