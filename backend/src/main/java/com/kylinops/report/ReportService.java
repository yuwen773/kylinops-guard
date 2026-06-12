package com.kylinops.report;

import com.kylinops.audit.AuditLog;
import com.kylinops.audit.AuditLogDetail;
import com.kylinops.audit.AuditLogRepository;
import com.kylinops.audit.AuditLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 报告服务。
 * <p>
 * <strong>硬约束</strong>：
 * <ul>
 *   <li>报告 Markdown 仅由 {@link AuditLogDetail} 字段确定性组装 — 禁止调用 LLM 补事实。</li>
 *   <li>缺失字段必须显式写"数据不可用"，不得隐藏也不得编造。</li>
 *   <li>数据库中立：仅使用 Spring Data JPA 派生方法。</li>
 *   <li>所有入参边界由 service 自行 clamp（size ∈ [1, 100]）。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    /** 占位文本：用于缺失字段的显式标注。 */
    public static final String UNAVAILABLE = "数据不可用";

    /** 列表分页 size 的最小值。 */
    private static final int MIN_PAGE_SIZE = 1;

    /** 列表分页 size 的最大值。 */
    private static final int MAX_PAGE_SIZE = 100;

    private final AuditLogService auditLogService;
    private final AuditLogRepository auditLogRepository;
    private final ReportRepository reportRepository;

    /**
     * 生成报告并持久化。
     *
     * @param req 报告生成请求（auditId 与 sessionId 至少一个）
     * @return 报告详情
     * @throws IllegalArgumentException auditId 与 sessionId 都缺失，或来源查不到对应审计
     */
    @Transactional
    public ReportDetail generate(ReportGenerateRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("请求不能为空");
        }

        String auditId = resolveAuditId(req);
        AuditLogDetail detail = auditLogService.getDetail(auditId)
                .orElseThrow(() -> new IllegalArgumentException("审计不存在: " + auditId));

        ReportType reportType = req.getReportType() != null ? req.getReportType() : ReportType.AUDIT;

        Report entity = new Report();
        entity.setReportId(UUID.randomUUID().toString());
        entity.setReportType(reportType);
        entity.setTitle(buildTitle(reportType, detail));
        entity.setSessionId(detail.getSessionId());
        entity.setAuditId(detail.getAuditId());
        entity.setRiskLevel(detail.getRiskLevel());
        entity.setBodyMarkdown(buildMarkdown(reportType, detail));

        Report saved = reportRepository.save(entity);
        log.debug("生成报告: reportId={}, auditId={}, type={}",
                saved.getReportId(), saved.getAuditId(), saved.getReportType());
        return toDetail(saved);
    }

    /**
     * 分页查询报告。
     *
     * @param page 页码（从 0 开始）
     * @param size 每页条数，会被 clamp 到 [1, 100]
     */
    @Transactional(readOnly = true)
    public Page<ReportSummary> list(int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(MIN_PAGE_SIZE, size));
        Pageable pageable = PageRequest.of(safePage, safeSize,
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Report> reports = reportRepository.findAll(pageable);
        return reports.map(this::toSummary);
    }

    /**
     * 根据 reportId 查询报告详情。
     *
     * @throws IllegalArgumentException 报告不存在
     */
    @Transactional(readOnly = true)
    public ReportDetail getDetail(String reportId) {
        Report entity = reportRepository.findByReportId(reportId)
                .orElseThrow(() -> new IllegalArgumentException("报告不存在: " + reportId));
        return toDetail(entity);
    }

    // ==================== 来源解析 ====================

    /**
     * 解析请求中的 auditId 来源。
     * <ul>
     *   <li>auditId 优先（直接使用）</li>
     *   <li>仅 sessionId 时取该会话最新一条 audit</li>
     *   <li>两者都缺失 → 抛 IllegalArgumentException</li>
     * </ul>
     */
    private String resolveAuditId(ReportGenerateRequest req) {
        if (req.getAuditId() != null && !req.getAuditId().isBlank()) {
            return req.getAuditId();
        }
        if (req.getSessionId() != null && !req.getSessionId().isBlank()) {
            // AuditLogRepository.findBySessionIdOrderByCreatedAtDesc 已按 createdAt DESC 排序，
            // 首元素即该会话最新 audit；不引入额外分页以避免改变现有方法签名。
            Optional<String> latestId = auditLogRepository
                    .findBySessionIdOrderByCreatedAtDesc(req.getSessionId())
                    .stream()
                    .findFirst()
                    .map(AuditLog::getAuditId);
            return latestId.orElseThrow(() -> new IllegalArgumentException(
                    "会话无对应审计: " + req.getSessionId()));
        }
        throw new IllegalArgumentException("必须提供 auditId 或 sessionId");
    }

    // ==================== Markdown 组装 ====================

    /**
     * 构建报告标题（基于 reportType + 风险等级）。
     */
    String buildTitle(ReportType type, AuditLogDetail detail) {
        String prefix = switch (type) {
            case HEALTH -> "系统健康检查";
            case DISK -> "磁盘诊断";
            case SERVICE -> "服务诊断";
            case SECURITY -> "安全事件";
            case AUDIT -> "通用审计";
        };
        String risk = detail.getRiskLevel() != null ? detail.getRiskLevel().name() : UNAVAILABLE;
        return prefix + "报告（" + risk + "）";
    }

    /**
     * 构建报告 Markdown 正文（确定性组装，禁止 LLM 补事实）。
     */
    String buildMarkdown(ReportType type, AuditLogDetail detail) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(buildTitle(type, detail)).append('\n').append('\n');

        sb.append("## 元信息").append('\n');
        appendRow(sb, "源审计 ID", safe(detail.getAuditId()));
        appendRow(sb, "会话 ID", safe(detail.getSessionId()));
        appendRow(sb, "采集时间", detail.getCreatedAt() != null ? detail.getCreatedAt().toString() : UNAVAILABLE);
        appendRow(sb, "风险等级", detail.getRiskLevel() != null ? detail.getRiskLevel().name() : UNAVAILABLE);
        appendRow(sb, "审计状态", detail.getStatus() != null ? detail.getStatus().name() : UNAVAILABLE);
        sb.append('\n');

        sb.append("# 用户请求").append('\n');
        sb.append(present(detail.getUserInput())).append("\n\n");

        sb.append("# 意图").append('\n');
        sb.append(present(detail.getIntentType() != null ? detail.getIntentType().name() : null)).append("\n\n");

        sb.append("# 工具调用").append('\n');
        appendToolCalls(sb, detail.getToolCalls());

        sb.append("# 风险检查").append('\n');
        appendRiskChecks(sb, detail);

        sb.append("# 确认").append('\n');
        appendConfirmation(sb, detail);

        sb.append("# 执行").append('\n');
        appendExecution(sb, detail);

        sb.append("# 最终答复").append('\n');
        sb.append(present(detail.getFinalAnswer())).append('\n');

        return sb.toString();
    }

    private void appendToolCalls(StringBuilder sb, List<AuditLogDetail.ToolCallInfo> calls) {
        if (calls == null || calls.isEmpty()) {
            sb.append(UNAVAILABLE).append("\n\n");
            return;
        }
        for (AuditLogDetail.ToolCallInfo call : calls) {
            sb.append("- **工具**: `").append(safe(call.getToolName())).append("`");
            sb.append(" — 状态: ").append(safe(call.getStatus()));
            if (call.getDurationMs() != null) {
                sb.append(" — 耗时: ").append(call.getDurationMs()).append("ms");
            }
            sb.append('\n');
            if (call.getOutput() != null && !call.getOutput().isBlank()) {
                sb.append("  - 输出: ").append(truncate(call.getOutput(), 500)).append('\n');
            } else if (call.getErrorMessage() != null && !call.getErrorMessage().isBlank()) {
                sb.append("  - 错误: ").append(call.getErrorMessage()).append('\n');
            } else {
                sb.append("  - 输出: ").append(UNAVAILABLE).append('\n');
            }
        }
        sb.append('\n');
    }

    private void appendRiskChecks(StringBuilder sb, AuditLogDetail detail) {
        if (detail.getRiskDecision() == null && detail.getRiskLevel() == null
                && (detail.getRiskChecks() == null || detail.getRiskChecks().isEmpty())) {
            sb.append(UNAVAILABLE).append("\n\n");
            return;
        }
        if (detail.getRiskLevel() != null || detail.getRiskDecision() != null) {
            sb.append("- **总体决策**: ")
                    .append(safe(detail.getRiskLevel() != null ? detail.getRiskLevel().name() : null))
                    .append(" / ")
                    .append(safe(detail.getRiskDecision() != null ? detail.getRiskDecision().name() : null))
                    .append('\n');
        }
        if (detail.getMatchedRules() != null && !detail.getMatchedRules().isBlank()) {
            sb.append("- **匹配规则**: ").append(detail.getMatchedRules()).append('\n');
        }
        if (detail.getActionPlan() != null && !detail.getActionPlan().isBlank()) {
            sb.append("- **执行计划**: ").append(detail.getActionPlan()).append('\n');
        }
        if (detail.getRiskChecks() != null && !detail.getRiskChecks().isEmpty()) {
            sb.append("- **风险校验明细**:\n");
            for (AuditLogDetail.RiskCheckInfo rc : detail.getRiskChecks()) {
                sb.append("  - ")
                        .append(safe(rc.getCheckedAt() != null ? rc.getCheckedAt().toString() : null))
                        .append(" — 等级: ").append(safe(rc.getRiskLevel()))
                        .append(" — 决策: ").append(safe(rc.getRiskDecision()));
                if (rc.getReason() != null && !rc.getReason().isBlank()) {
                    sb.append(" — 原因: ").append(rc.getReason());
                }
                sb.append('\n');
            }
        } else if (detail.getRiskDecision() == null) {
            sb.append("- 风险校验明细: ").append(UNAVAILABLE).append('\n');
        }
        sb.append('\n');
    }

    private void appendConfirmation(StringBuilder sb, AuditLogDetail detail) {
        if (!detail.isConfirmationRequired()
                && detail.getPendingAction() == null
                && detail.getConfirmationStatus() == null) {
            sb.append("无需确认\n\n");
            return;
        }
        sb.append("- 需要确认: ").append(detail.isConfirmationRequired() ? "是" : "否").append('\n');
        sb.append("- 确认状态: ").append(safe(detail.getConfirmationStatus())).append('\n');
        if (detail.getPendingAction() != null) {
            AuditLogDetail.PendingActionInfo pa = detail.getPendingAction();
            sb.append("- 待确认动作 ID: ").append(safe(pa.getActionId())).append('\n');
            sb.append("- 动作类型: ").append(safe(pa.getActionType())).append('\n');
            sb.append("- 关联工具: `").append(safe(pa.getToolName())).append("`\n");
            sb.append("- 动作状态: ").append(safe(pa.getStatus())).append('\n');
        }
        sb.append('\n');
    }

    private void appendExecution(StringBuilder sb, AuditLogDetail detail) {
        if (detail.getExecutionResult() == null
                || detail.getExecutionResult().isBlank()) {
            sb.append(UNAVAILABLE).append("\n\n");
            return;
        }
        sb.append(truncate(detail.getExecutionResult(), 2000)).append("\n\n");
    }

    // ==================== 工具 ====================

    private void appendRow(StringBuilder sb, String key, String value) {
        sb.append("- ").append(key).append(": ").append(value).append('\n');
    }

    /** 字段缺失时返回"数据不可用"。 */
    private String present(String value) {
        return (value == null || value.isBlank()) ? UNAVAILABLE : value;
    }

    /** 安全取值（null → "数据不可用"）。 */
    private String safe(String value) {
        return present(value);
    }

    private String truncate(String s, int max) {
        if (s == null) return UNAVAILABLE;
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...[truncated]";
    }

    // ==================== DTO 映射 ====================

    private ReportSummary toSummary(Report entity) {
        return ReportSummary.builder()
                .reportId(entity.getReportId())
                .title(entity.getTitle())
                .reportType(entity.getReportType())
                .riskLevel(entity.getRiskLevel())
                .sessionId(entity.getSessionId())
                .auditId(entity.getAuditId())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    private ReportDetail toDetail(Report entity) {
        return ReportDetail.builder()
                .reportId(entity.getReportId())
                .title(entity.getTitle())
                .reportType(entity.getReportType())
                .riskLevel(entity.getRiskLevel())
                .sessionId(entity.getSessionId())
                .auditId(entity.getAuditId())
                .bodyMarkdown(entity.getBodyMarkdown())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}