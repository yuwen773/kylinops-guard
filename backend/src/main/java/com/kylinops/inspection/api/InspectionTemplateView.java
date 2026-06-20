package com.kylinops.inspection.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.kylinops.common.enums.RiskLevel;
import com.kylinops.inspection.model.InspectionTemplateType;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 巡检模板视图(P1-02 Task 7)。
 *
 * <p>GET /api/inspections/templates 列表项;前端根据 {@code fields} 动态渲染表单。
 * 字段定义与模板元数据解耦 — 风险等级来自 {@link com.kylinops.tool.ToolRegistry} 而非硬编码。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InspectionTemplateView(
        InspectionTemplateType templateType,
        String displayName,
        List<TemplateField> fields,
        Map<String, RiskLevel> riskLevels,
        Set<String> keyToolNames
) {
    /**
     * 表单字段定义,前端用于动态生成输入框(配合 Inspection 11.1)。
     */
    public record TemplateField(
            String name,
            String label,
            String type,        // string / number / select / list
            boolean required,
            String defaultValue,
            Map<String, Object> constraints
    ) {
    }
}
