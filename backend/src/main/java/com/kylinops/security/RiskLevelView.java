package com.kylinops.security;

import com.kylinops.common.enums.RiskDecision;
import com.kylinops.common.enums.RiskLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 风险等级目录项（只读）。
 * <p>
 * 用于 GET /api/security/risk-levels 的响应；
 * 提供 L0–L4 的中文说明和典型示例，便于前端 Security Center 展示。
 * </p>
 */
@Data
@Builder
@AllArgsConstructor
public class RiskLevelView {

    /** 风险等级枚举名（L0 / L1 / L2 / L3 / L4） */
    private RiskLevel level;

    /** 默认决策（ALLOW / CONFIRM / BLOCK） */
    private RiskDecision decision;

    /** 中文说明 */
    private String description;

    /** 典型命令/场景示例（中文） */
    private List<String> examples;
}
