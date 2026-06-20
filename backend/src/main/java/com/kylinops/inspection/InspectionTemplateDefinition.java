package com.kylinops.inspection;

import com.kylinops.common.enums.RiskLevel;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 巡检模板定义(P1-02 Task 3)。不可变 record,描述一个内置模板的:
 * <ul>
 *   <li>{@code stages} — 阶段化工具列表,每个内层列表内的工具并行执行,阶段间串行</li>
 *   <li>{@code keyToolNames} — 关键工具集合;任一失败即整次执行 FAILED(设计 §6.x 异常条件)</li>
 *   <li>{@code riskLevels} — 模板内每个工具声明的风险等级(由 {@link InspectionTemplateRegistry} 启动期从
 *       {@link com.kylinops.tool.ToolRegistry} 读取,避免硬编码)</li>
 * </ul>
 *
 * <p>设计约束:任何模板只允许引用 L0/L1 + PermissionType.READ 的已注册工具。{@link InspectionTemplateRegistry}
 * 在启动期校验,若发现违规则启动失败。</p>
 */
public record InspectionTemplateDefinition(
        List<List<String>> stages,
        Set<String> keyToolNames,
        Map<String, RiskLevel> riskLevels) {

    public InspectionTemplateDefinition {
        stages = List.copyOf(stages);
        keyToolNames = Set.copyOf(keyToolNames);
        riskLevels = Map.copyOf(riskLevels);
    }
}