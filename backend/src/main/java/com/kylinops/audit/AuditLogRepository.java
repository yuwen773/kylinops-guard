package com.kylinops.audit;

import com.kylinops.common.enums.AuditStatus;
import com.kylinops.common.enums.RiskDecision;
import com.kylinops.common.enums.RiskLevel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 审计日志 Repository
 */
@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /** 根据 auditId 查询 */
    Optional<AuditLog> findByAuditId(String auditId);

    /** 查询某状态的所有审计日志，按创建时间降序排列 */
    List<AuditLog> findByStatusOrderByCreatedAtDesc(AuditStatus status);

    /** 查询某风险等级的所有审计日志 */
    List<AuditLog> findByRiskLevelOrderByCreatedAtDesc(RiskLevel riskLevel);

    /** 查询某风险决策的所有审计日志 */
    List<AuditLog> findByRiskDecisionOrderByCreatedAtDesc(RiskDecision riskDecision);

    /** 查询某时间范围内的审计日志 */
    List<AuditLog> findByCreatedAtBetweenOrderByCreatedAtDesc(LocalDateTime start, LocalDateTime end);

    /** 根据 sessionId 查询 */
    List<AuditLog> findBySessionIdOrderByCreatedAtDesc(String sessionId);

    /** 模糊搜索用户输入 */
    List<AuditLog> findByUserInputContainingIgnoreCase(String keyword);
}
