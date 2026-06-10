package com.kylinops.tool;

import com.kylinops.common.enums.ToolStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 工具定义 Repository
 */
@Repository
public interface ToolDefinitionRepository extends JpaRepository<ToolDefinition, Long> {

    /** 根据工具名称查询 */
    Optional<ToolDefinition> findByToolName(String toolName);

    /** 查询所有启用状态下的工具 */
    List<ToolDefinition> findByToolStatus(ToolStatus toolStatus);

    /** 查询所有指定风险等级的工具 */
    List<ToolDefinition> findByRiskLevel(com.kylinops.common.enums.RiskLevel riskLevel);

    /** 查询工具是否存在 */
    boolean existsByToolName(String toolName);
}
