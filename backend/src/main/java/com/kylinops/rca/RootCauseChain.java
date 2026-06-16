package com.kylinops.rca;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 根因分析链 — 跨 Agent / Audit / Report 三层共享。
 * 业务字段稳定（不含 LLM 自由生成字段），便于审计回放与合规。
 *
 * <p>字段命名（hypotheses 复数）前后端统一。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RootCauseChain {

    /** 现象（人类可读） */
    private String symptom;

    /** 工具证据列表 */
    private List<Evidence> evidence;

    /** 候选根因列表（含确认标记 + 概率） */
    private List<Hypothesis> hypotheses;

    /** 明确排除的根因（结构化对象） */
    private List<ExcludedCause> excludedCauses;

    /** 最终结论 */
    private String conclusion;

    /** 置信度 0.0-1.0 */
    private double confidence;

    /** 可执行的下一步建议 */
    private List<String> suggestions;

    /** 风险提示 */
    private List<String> riskTips;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Evidence {
        /** 证据唯一 ID（ExcludedCause.evidenceIds 引用此字段） */
        private String evidenceId;
        /** 产出该证据的工具名 */
        private String source;
        /** 关联的 ToolCallRecord ID（如有），用于审计深链 */
        private String sourceToolCallId;
        /** 人类可读的观察描述 */
        private String observation;
        /** 数值（如有） */
        private Double numericValue;
        /** 数值单位 */
        private String unit;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Hypothesis {
        /** 候选根因描述 */
        private String cause;
        /** 概率 0.0-1.0 */
        private double probability;
        /** 是否确认根因 */
        private boolean confirmed;
        /** 推理依据 */
        private String reasoning;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ExcludedCause {
        /** 被排除的根因描述 */
        private String cause;
        /** 排除原因 */
        private String reason;
        /** 关联的证据 ID 列表 */
        private List<String> evidenceIds;
    }
}
