package com.kylinops.audit;

import com.kylinops.llm.LlmCallResult;
import com.kylinops.llm.LlmClientException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * LLM 调用审计服务（P3-T5）。
 *
 * <p>负责把每次 LLM 调用的 bounded summary 持久化到 {@code kylin_llm_call_record}。</p>
 *
 * <h3>安全红线</h3>
 * <ul>
 *   <li><strong>审计失败不阻塞 LLM 调用</strong>：任何异常 → log.warn + swallow</li>
 *   <li><strong>仅记 bounded summary</strong>：不存 apiKey / prompt / response / reasoning</li>
 *   <li><strong>reason 仅记枚举名</strong>：不存 LlmClientException.message（可能含敏感 token）</li>
 * </ul>
 *
 * <h3>事务</h3>
 * <p>{@link Propagation#REQUIRES_NEW} — 独立事务，避免审计失败回滚外层业务事务；
 * 但本服务本就不抛异常，事务边界主要是「审计记录提交独立于调用方事务」。</p>
 */
@Slf4j
@Service
public class LlmCallAuditService {

    private final LlmCallRecordRepository repository;

    public LlmCallAuditService(LlmCallRecordRepository repository) {
        this.repository = repository;
    }

    /**
     * 记录一次成功的 LLM 调用。
     *
     * @param auditId    审计 ID（必填；null 时跳过记录）
     * @param stage      调用阶段
     * @param model      默认模型名（来自装饰器侧已知配置；优先使用 {@code result.model()}）
     * @param durationMs 耗时
     * @param result     LLM 返回结果（取 token 数 + 实际模型名）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(String auditId, LlmCallStage stage, String model,
                              long durationMs, LlmCallResult result) {
        if (auditId == null || auditId.isBlank()) {
            return;
        }
        try {
            LlmCallRecord record = new LlmCallRecord();
            record.setAuditId(auditId);
            record.setStage(stage != null ? stage : LlmCallStage.RESPONSE);
            // model 优先取 LlmCallResult.model（实际返回的模型），fallback 到装饰器传入值
            String resolvedModel = (result != null && result.model() != null && !result.model().isBlank())
                    ? result.model()
                    : (model != null ? model : "unknown");
            record.setModel(resolvedModel);
            record.setDurationMs(Math.max(0L, durationMs));
            record.setStatus(LlmCallStatus.SUCCESS);
            record.setReason(null);
            if (result != null) {
                record.setPromptTokens(result.promptTokens());
                record.setCompletionTokens(result.completionTokens());
                record.setTotalTokens(result.totalTokens());
            }
            repository.save(record);
        } catch (Exception e) {
            // 审计失败 → log.warn + swallow，不抛
            log.warn("LLM 调用审计（成功）失败: auditId={}, err={}", auditId, e.getMessage());
        }
    }

    /**
     * 记录一次失败的 LLM 调用。
     *
     * @param auditId    审计 ID（必填；null 时跳过记录）
     * @param stage      调用阶段
     * @param model      模型名
     * @param durationMs 耗时
     * @param ex         异常（仅取 {@link LlmClientException#getReason()} 枚举名）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(String auditId, LlmCallStage stage, String model,
                              long durationMs, Throwable ex) {
        if (auditId == null || auditId.isBlank()) {
            return;
        }
        try {
            LlmCallRecord record = new LlmCallRecord();
            record.setAuditId(auditId);
            record.setStage(stage != null ? stage : LlmCallStage.RESPONSE);
            record.setModel(model != null ? model : "unknown");
            record.setDurationMs(Math.max(0L, durationMs));
            record.setStatus(LlmCallStatus.FAILED);
            record.setReason(extractReason(ex));
            // 失败时 token 计数为 null（不来自 LlmCallResult）
            repository.save(record);
        } catch (Exception e) {
            log.warn("LLM 调用审计（失败）失败: auditId={}, err={}", auditId, e.getMessage());
        }
    }

    /**
     * 从异常中提取 reason 枚举名（仅记 Reason.name()，不记 message 原文）。
     */
    private static String extractReason(Throwable ex) {
        if (ex == null) {
            return "UNKNOWN";
        }
        if (ex instanceof LlmClientException llmEx) {
            return llmEx.getReason() != null ? llmEx.getReason().name() : "UNKNOWN";
        }
        return "RUNTIME_ERROR";
    }
}