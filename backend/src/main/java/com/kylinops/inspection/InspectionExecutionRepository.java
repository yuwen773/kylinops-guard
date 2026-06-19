package com.kylinops.inspection;

import com.kylinops.inspection.model.InspectionExecutionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 巡检执行记录 Repository(P1-02 Plan 02 — Task 2)。
 *
 * <p>关键查询:</p>
 * <ul>
 *   <li>{@link #existsByPlanIdAndStatus(String, InspectionExecutionStatus)} —
 *       重入检测的核心谓词,见设计 §7 "数据库原子申请执行权"</li>
 *   <li>{@link #findAllByStatus(InspectionExecutionStatus)} —
 *       启动期恢复遗留 RUNNING,标记为 FAILED(设计 §7 启动恢复)</li>
 *   <li>{@link #findByPlanIdOrderByStartedAtDesc(String, Pageable)} —
 *       执行记录列表按 plan 筛选 + 时间倒序(分页)</li>
 * </ul>
 */
@Repository
public interface InspectionExecutionRepository extends JpaRepository<InspectionExecution, Long> {

    /** 业务主键精确查询。 */
    Optional<InspectionExecution> findByExecutionId(String executionId);

    /**
     * 重入检测:同一 plan 是否已存在指定状态的执行。
     * 调度器在 PESSIMISTIC_WRITE 锁内调用,以决定新插入 RUNNING 还是 SKIPPED。
     */
    boolean existsByPlanIdAndStatus(String planId, InspectionExecutionStatus status);

    /** 启动期恢复:列出所有 RUNNING 遗留执行(用于崩溃后的失败标记)。 */
    List<InspectionExecution> findAllByStatus(InspectionExecutionStatus status);

    /** 按 plan 查询执行历史(分页,最新在前)。 */
    Page<InspectionExecution> findByPlanIdOrderByStartedAtDesc(String planId, Pageable pageable);
}
