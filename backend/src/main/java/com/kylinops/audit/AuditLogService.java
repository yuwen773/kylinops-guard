package com.kylinops.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kylinops.common.enums.AuditStatus;
import com.kylinops.common.enums.IntentType;
import com.kylinops.common.enums.RiskDecision;
import com.kylinops.common.enums.RiskLevel;
import com.kylinops.executor.PendingActionRepository;
import com.kylinops.notification.NotificationRecordRepository;
import com.kylinops.notification.NotificationRecordSummary;
import com.kylinops.rca.RootCauseChain;
import com.kylinops.security.RiskCheckRecordRepository;
import com.kylinops.tool.ToolCallRecordRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 审计日志服务
 * <p>
 * 负责审计日志的创建和更新。auditId 贯穿 Session → Message → ToolCallRecord → RiskCheckRecord 全链路，
 * 提供端到端的请求追踪能力。
 * </p>
 *
 * <h3>状态流转</h3>
 * <pre>
 * RECEIVED → RISK_CHECKED → SUCCESS
 *          → CONFIRM_PENDING → CONFIRMED / CANCELLED → SUCCESS
 *          → BLOCKED
 *          → FAILED
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;
    private final ToolCallRecordRepository toolCallRecordRepository;
    private final RiskCheckRecordRepository riskCheckRecordRepository;
    private final PendingActionRepository pendingActionRepository;
    private final NotificationRecordRepository notificationRecordRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public String serializeRca(RootCauseChain chain) {
        if (chain == null) return null;
        try {
            return objectMapper.writeValueAsString(chain);
        } catch (JsonProcessingException e) {
            log.warn("RCA 序列化失败: {}", e.getMessage());
            return null;
        }
    }

    public RootCauseChain deserializeRca(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, RootCauseChain.class);
        } catch (JsonProcessingException e) {
            log.warn("RCA 反序列化失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 创建一次 Dashboard / 工具批量采集的审计记录。
     * <p>
     * 与 {@link #createAuditLog(String, String, String, IntentType, AuditStatus)} 类似，
     * 但省略会话/意图等 Chat 上下文，专门服务于「一次刷新调用多个只读工具」的场景。
     * 采集流程应当：先调 {@code startCollection} 拿到 auditId，把 auditId 透传给所有
     * {@code ToolExecutor.execute(toolName, params, auditId)}；最后调
     * {@link #finalizeCollection(String, double, AuditStatus)} 收尾。
     * </p>
     *
     * @param userInput 触发采集的用户输入摘要（可为空）
     * @return 持久化后的审计日志（含 auditId）
     */
    @Transactional
    public AuditLog startCollection(String userInput) {
        AuditLog entry = new AuditLog();
        entry.setAuditId(java.util.UUID.randomUUID().toString());
        entry.setUserInput(userInput != null ? AuditSanitizer.truncateInput(userInput)
                : "dashboard_collection");
        entry.setIntentType(IntentType.SYSTEM_CHECK);
        entry.setStatus(AuditStatus.RECEIVED);
        AuditLog saved = auditLogRepository.save(entry);
        log.debug("启动采集审计: auditId={}, userInput={}", saved.getAuditId(), saved.getUserInput());
        return saved;
    }

    /**
     * 采集结束后的审计收尾：写入 coverage、最终状态与摘要。
     *
     * @param auditId  审计 ID
     * @param coverage 工具调用覆盖率（成功数 / 总数，0.0 - 1.0）
     * @param status   最终状态（SUCCESS / FAILED / BLOCKED 等）
     */
    @Transactional
    public void finalizeCollection(String auditId, double coverage, AuditStatus status) {
        auditLogRepository.findByAuditId(auditId).ifPresent(entry -> {
            // 摘要：覆盖数 / 总数（聚合信息；具体 metric 详情由 ToolCallRecord 提供）
            String coveragePct = String.format("%.0f%%", Math.max(0.0, Math.min(1.0, coverage)) * 100.0);
            entry.setMessage(AuditSanitizer.truncateInput(
                    "采集完成，覆盖率 " + coveragePct));
            if (status != null) {
                entry.setStatus(status);
            }
            auditLogRepository.save(entry);
            log.debug("收尾采集审计: auditId={}, coverage={}, status={}",
                    auditId, coveragePct, status);
        });
    }

    /**
     * 创建审计日志记录。
     *
     * @param auditId    审计 ID（贯穿全链路）
     * @param sessionId  会话 ID
     * @param userInput  用户输入
     * @param intentType 识别到的意图
     * @param status     初始状态
     * @return 持久化后的审计日志
     */
    @Transactional
    public AuditLog createAuditLog(String auditId, String sessionId, String userInput,
                                    IntentType intentType, AuditStatus status) {
        AuditLog logEntry = new AuditLog();
        logEntry.setAuditId(auditId);
        logEntry.setSessionId(sessionId);
        logEntry.setUserInput(userInput);
        logEntry.setIntentType(intentType);
        logEntry.setStatus(status != null ? status : AuditStatus.RECEIVED);
        AuditLog saved = auditLogRepository.save(logEntry);
        log.debug("创建审计日志: auditId={}, intent={}, status={}", auditId, intentType, saved.getStatus());
        return saved;
    }

    /**
     * 更新审计日志的风险决策信息。
     *
     * @param auditId     审计 ID
     * @param riskLevel   风险等级
     * @param riskDecision 风险决策
     * @param toolName    工具名称（可选）
     * @param status      当前状态
     * @param message     审计消息（可选）
     */
    @Transactional
    public void updateAuditLog(String auditId, RiskLevel riskLevel, RiskDecision riskDecision,
                                String toolName, AuditStatus status, String message) {
        updateAuditLog(auditId, riskLevel, riskDecision, toolName, status, message, null);
    }

    /**
     * 更新审计日志的风险决策信息和意图类型。
     *
     * @param auditId     审计 ID
     * @param riskLevel   风险等级
     * @param riskDecision 风险决策
     * @param toolName    工具名称（可选）
     * @param status      当前状态
     * @param message     审计消息（可选）
     * @param intentType  意图类型（可选）
     */
    @Transactional
    public void updateAuditLog(String auditId, RiskLevel riskLevel, RiskDecision riskDecision,
                                String toolName, AuditStatus status, String message,
                                IntentType intentType) {
        auditLogRepository.findByAuditId(auditId).ifPresent(logEntry -> {
            if (riskLevel != null) {
                logEntry.setRiskLevel(riskLevel);
            }
            if (riskDecision != null) {
                logEntry.setRiskDecision(riskDecision);
            }
            if (toolName != null) {
                logEntry.setToolName(toolName);
            }
            if (status != null) {
                logEntry.setStatus(status);
            }
            if (message != null) {
                logEntry.setMessage(AuditSanitizer.truncateInput(message));
            }
            if (intentType != null) {
                logEntry.setIntentType(intentType);
            }
            auditLogRepository.save(logEntry);
            log.debug("更新审计日志: auditId={}, status={}, decision={}", auditId, status, riskDecision);
        });
    }

    /**
     * 更新审计日志的匹配规则和执行计划。
     */
    @Transactional
    public void updateAuditDetails(String auditId, String matchedRules, String actionPlan) {
        auditLogRepository.findByAuditId(auditId).ifPresent(logEntry -> {
            if (matchedRules != null) {
                logEntry.setMatchedRules(AuditSanitizer.truncateInput(matchedRules));
            }
            if (actionPlan != null) {
                logEntry.setActionPlan(AuditSanitizer.truncateInput(actionPlan));
            }
            auditLogRepository.save(logEntry);
        });
    }

    /**
     * 更新审计日志的确认和执行信息。
     */
    @Transactional
    public void updateAuditConfirmation(String auditId, boolean confirmationRequired,
                                         String confirmationStatus) {
        auditLogRepository.findByAuditId(auditId).ifPresent(logEntry -> {
            logEntry.setConfirmationRequired(confirmationRequired);
            if (confirmationStatus != null) {
                logEntry.setConfirmationStatus(confirmationStatus);
            }
            auditLogRepository.save(logEntry);
        });
    }

    /**
     * 更新审计日志的执行结果和最终回复。
     */
    @Transactional
    public void updateAuditResult(String auditId, String executionResult, String finalAnswer) {
        auditLogRepository.findByAuditId(auditId).ifPresent(logEntry -> {
            if (executionResult != null) {
                logEntry.setExecutionResult(AuditSanitizer.truncateOutput(executionResult));
            }
            if (finalAnswer != null) {
                logEntry.setFinalAnswer(AuditSanitizer.truncateInput(finalAnswer));
            }
            auditLogRepository.save(logEntry);
        });
    }

    /**
     * 添加审计警告。
     */
    @Transactional
    public void addWarning(String auditId, String warning) {
        auditLogRepository.findByAuditId(auditId).ifPresent(logEntry -> {
            String existing = logEntry.getWarning();
            if (existing != null) {
                logEntry.setWarning(AuditSanitizer.truncateInput(existing + "; " + warning));
            } else {
                logEntry.setWarning(AuditSanitizer.truncateInput(warning));
            }
            auditLogRepository.save(logEntry);
        });
    }

    /**
     * 标记审计日志为成功完成。
     *
     * @param auditId 审计 ID
     */
    @Transactional
    public void markCompleted(String auditId) {
        auditLogRepository.findByAuditId(auditId).ifPresent(logEntry -> {
            logEntry.setStatus(AuditStatus.SUCCESS);
            auditLogRepository.save(logEntry);
            log.debug("审计日志标记完成: auditId={}", auditId);
        });
    }

    /**
     * 标记审计日志为已阻断。
     *
     * @param auditId 审计 ID
     */
    @Transactional
    public void markBlocked(String auditId) {
        auditLogRepository.findByAuditId(auditId).ifPresent(logEntry -> {
            logEntry.setStatus(AuditStatus.BLOCKED);
            auditLogRepository.save(logEntry);
            log.debug("审计日志标记阻断: auditId={}", auditId);
        });
    }

    /**
     * 根据 auditId 查询审计日志。
     *
     * @param auditId 审计 ID
     * @return 审计日志（可能为空）
     */
    public Optional<AuditLog> findByAuditId(String auditId) {
        return auditLogRepository.findByAuditId(auditId);
    }

    public AuditLog requireAuditLog(String auditId) {
        return auditLogRepository.findByAuditId(auditId)
                .orElseThrow(() -> new IllegalStateException(
                        "audit log not found: " + auditId));
    }

    /**
     * 组合查询审计日志列表（支持分页、排序和筛选）。
     *
     * @param riskLevel   风险等级筛选（可选）
     * @param status      状态筛选（可选）
     * @param keyword     关键词筛选（匹配 userInput，可选）
     * @param startTime   开始时间（可选）
     * @param endTime     结束时间（可选）
     * @param page        页码（从 0 开始）
     * @param size        每页大小
     * @return 分页的审计日志摘要
     */
    public Page<AuditLogSummary> queryLogs(RiskLevel riskLevel, AuditStatus status,
                                            String keyword, LocalDateTime startTime,
                                            LocalDateTime endTime, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        Specification<AuditLog> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (riskLevel != null) {
                predicates.add(cb.equal(root.get("riskLevel"), riskLevel));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (keyword != null && !keyword.isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("userInput")),
                        "%" + keyword.toLowerCase() + "%"));
            }
            if (startTime != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), startTime));
            }
            if (endTime != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), endTime));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Page<AuditLog> logsPage = auditLogRepository.findAll(spec, pageable);

        // Single grouped aggregate: one count query per page, NOT one per row.
        // Empty page → skip the aggregate entirely (no useful ids).
        Map<String, Long> countsByAuditId = new HashMap<>();
        List<AuditLog> entities = logsPage.getContent();
        if (!entities.isEmpty()) {
            List<String> ids = entities.stream()
                    .map(AuditLog::getAuditId)
                    .toList();
            for (var projection : toolCallRecordRepository.countByAuditIdInGrouped(ids)) {
                countsByAuditId.put(projection.getAuditId(), projection.getCount());
            }
        }

        return logsPage.map(log -> toSummary(log, countsByAuditId.getOrDefault(log.getAuditId(), 0L)));
    }

    /**
     * 获取审计日志详情（含关联的工具调用、风险校验和执行记录）。
     *
     * @param auditId 审计 ID
     * @return 审计详情 DTO（可能为空）
     */
    public Optional<AuditLogDetail> getDetail(String auditId) {
        return auditLogRepository.findByAuditId(auditId).map(this::toDetail);
    }

    // ==================== DTO 映射 ====================

    /**
     * 单条 summary 映射（toolCallCount 由调用方通过 grouped aggregate 提供）。
     */
    private AuditLogSummary toSummary(AuditLog log, long toolCallCount) {
        return AuditLogSummary.builder()
                .auditId(log.getAuditId())
                .sessionId(log.getSessionId())
                .userInput(AuditSanitizer.truncateInput(log.getUserInput()))
                .intentType(log.getIntentType())
                .riskLevel(log.getRiskLevel())
                .riskDecision(log.getRiskDecision())
                .status(log.getStatus())
                .confirmationRequired(log.isConfirmationRequired())
                .confirmationStatus(log.getConfirmationStatus())
                .message(log.getMessage())
                .toolCallCount(toolCallCount)
                .createdAt(log.getCreatedAt())
                .build();
    }

    private AuditLogDetail toDetail(AuditLog log) {
        // 查询关联记录
        List<AuditLogDetail.ToolCallInfo> toolCalls = toolCallRecordRepository
                .findByAuditId(log.getAuditId())
                .stream()
                .map(tcr -> AuditLogDetail.ToolCallInfo.builder()
                        .toolCallId(tcr.getToolCallId())
                        .toolName(tcr.getToolName())
                        .status(tcr.getStatus() != null ? tcr.getStatus().name() : null)
                        .input(AuditSanitizer.truncateInput(tcr.getInput()))
                        .output(AuditSanitizer.truncateOutput(tcr.getOutput()))
                        .errorMessage(tcr.getErrorMessage())
                        .durationMs(tcr.getDurationMs())
                        .build())
                .toList();

        List<AuditLogDetail.RiskCheckInfo> riskChecks = riskCheckRecordRepository
                .findTop50ByAuditIdOrderByCheckedAtDesc(log.getAuditId())
                .stream()
                .map(rcr -> AuditLogDetail.RiskCheckInfo.builder()
                        .riskCheckId(rcr.getRiskCheckId())
                        .targetType(rcr.getTargetType())
                        .riskLevel(rcr.getRiskLevel() != null ? rcr.getRiskLevel().name() : null)
                        .riskDecision(rcr.getRiskDecision() != null ? rcr.getRiskDecision().name() : null)
                        .matchedRules(rcr.getMatchedRules())
                        .reason(rcr.getReason())
                        .checkedAt(rcr.getCheckedAt())
                        .build())
                .toList();

        AuditLogDetail.PendingActionInfo pendingAction = pendingActionRepository
                .findByAuditId(log.getAuditId())
                .stream()
                .findFirst()
                .map(pa -> AuditLogDetail.PendingActionInfo.builder()
                        .actionId(pa.getActionId())
                        .actionType(pa.getActionType())
                        .toolName(pa.getToolName())
                        .status(pa.getStatus() != null ? pa.getStatus().name() : null)
                        .executionResult(AuditSanitizer.truncateOutput(pa.getExecutionResult()))
                        .build())
                .orElse(null);

        return AuditLogDetail.builder()
                .auditId(log.getAuditId())
                .sessionId(log.getSessionId())
                .userInput(AuditSanitizer.truncateInput(log.getUserInput()))
                .intentType(log.getIntentType())
                .riskLevel(log.getRiskLevel())
                .riskDecision(log.getRiskDecision())
                .status(log.getStatus())
                .message(log.getMessage())
                .matchedRules(log.getMatchedRules())
                .actionPlan(log.getActionPlan())
                .confirmationRequired(log.isConfirmationRequired())
                .confirmationStatus(log.getConfirmationStatus())
                .executionResult(AuditSanitizer.truncateOutput(log.getExecutionResult()))
                .finalAnswer(log.getFinalAnswer())
                .warning(log.getWarning())
                .createdAt(log.getCreatedAt())
                .updatedAt(log.getUpdatedAt())
                .toolCalls(toolCalls)
                .riskChecks(riskChecks)
                .pendingAction(pendingAction)
                .notificationRecords(notificationRecordRepository
                        .findByAuditIdOrderByCreatedAtDesc(log.getAuditId())
                        .stream()
                        .map(NotificationRecordSummary::from)
                        .toList())
                .rootCauseChain(deserializeRca(log.getRootCauseChainJson()))
                .build();
    }
}
