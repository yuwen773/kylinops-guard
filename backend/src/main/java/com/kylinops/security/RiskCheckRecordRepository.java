package com.kylinops.security;

import com.kylinops.common.enums.RiskDecision;
import com.kylinops.common.enums.RiskLevel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 风险校验记录 Repository
 */
@Repository
public interface RiskCheckRecordRepository extends JpaRepository<RiskCheckRecord, Long> {

    /** 根据风险校验ID查询 */
    Optional<RiskCheckRecord> findByRiskCheckId(String riskCheckId);

    /** 根据工具调用记录ID查询 */
    Optional<RiskCheckRecord> findByToolCallRecordId(Long toolCallRecordId);

    /** 查询某风险等级的所有记录 */
    List<RiskCheckRecord> findByRiskLevel(RiskLevel riskLevel);

    /** 查询某风险决策的所有记录 */
    List<RiskCheckRecord> findByRiskDecision(RiskDecision riskDecision);

    /** 根据 auditId 查询（用于审计详情聚合） */
    List<RiskCheckRecord> findByAuditId(String auditId);

    /** 根据 auditId 查询最新的 50 条记录（按 checkedAt 降序）— 详情页防 OOM 上限 */
    List<RiskCheckRecord> findTop50ByAuditIdOrderByCheckedAtDesc(String auditId);

    /** 根据 auditId 和目标类型查询 */
    List<RiskCheckRecord> findByAuditIdAndTargetType(String auditId, String targetType);
}
