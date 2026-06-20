package com.kylinops.inspection;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 巡检计划 Repository(P1-02 Plan 02 — Task 2)。
 *
 * <p>查询语义:</p>
 * <ul>
 *   <li>{@link #findByPlanId(String)} — 业务主键精确查询</li>
 *   <li>{@link #findByPlanIdForUpdate(String)} — 同一事务内对计划行加
 *       {@link LockModeType#PESSIMISTIC_WRITE},用于调度器与重入检测的串行化。
 *       调度流程:lock → existsByPlanIdAndStatus → 决定 RUNNING 还是 SKIPPED</li>
 *   <li>{@link #findByEnabledTrueAndNextRunAtLessThanEqual(LocalDateTime)} —
 *       调度器扫描到期计划;索引 idx_inspection_plan_due 加速,
 *       enabled=true 的过滤由 Service 层完成(避免 PostgreSQL-only partial index)</li>
 * </ul>
 */
@Repository
public interface InspectionPlanRepository extends JpaRepository<InspectionPlan, Long> {

    /** 业务主键精确查询。 */
    Optional<InspectionPlan> findByPlanId(String planId);

    /**
     * 对计划行加 {@code SELECT ... FOR UPDATE} 行锁,用于调度器的"申请执行权"步骤。
     * 调用方必须处于事务内,持锁期间其他事务的 {@code findByPlanIdForUpdate(planId)}
     * 将阻塞等待至本事务提交。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from InspectionPlan p where p.planId = :planId")
    Optional<InspectionPlan> findByPlanIdForUpdate(@Param("planId") String planId);

    /**
     * 调度器扫描:enabled=true 且 next_run_at <= now 的计划。
     * 索引 {@code idx_inspection_plan_due(next_run_at)} 覆盖排序与范围扫描。
     */
    List<InspectionPlan> findByEnabledTrueAndNextRunAtLessThanEqual(LocalDateTime cutoff);
}
