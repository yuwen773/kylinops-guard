package com.kylinops.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * LLM 调用摘要审计记录实体（P3-T5）。
 *
 * <p>每次 LLM 调用（INTENT / RESPONSE 阶段）一行。记录仅含 <strong>bounded
 * summary</strong>，绝不存储 prompt / response / reasoning / apiKey / 原始工具输出。</p>
 *
 * <h3>字段约束（安全红线）</h3>
 * <ul>
 *   <li><strong>显式无</strong> {@code apiKey} 字段 — 防止 API key 泄漏到数据库</li>
 *   <li><strong>显式无</strong> {@code prompt} 字段 — 防止用户输入写入 DB</li>
 *   <li><strong>显式无</strong> {@code responseContent} / {@code raw} 字段 — 防止模型输出泄漏</li>
 *   <li><strong>显式无</strong> {@code reasoning} 字段 — 防止 chain-of-thought 泄漏</li>
 * </ul>
 *
 * <p>{@link #reason} 仅记录 {@link LlmClientException.Reason} 枚举名，不记 message 原文，
 * 防止异常 message 中可能的敏感片段（如 token 提示）入库。</p>
 *
 * <h3>索引</h3>
 * <ul>
 *   <li>{@code audit_id} — 按审计 ID 关联现有 AuditLog</li>
 *   <li>{@code created_at} — 按时间窗排查</li>
 * </ul>
 */
@Entity
@Table(name = "kylin_llm_call_record")
public class LlmCallRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 审计 ID（与 kylin_audit_log.audit_id 关联） */
    @Column(nullable = false, length = 64)
    private String auditId;

    /** 调用阶段（INTENT / RESPONSE） */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private LlmCallStage stage;

    /** 模型名（来自 LlmCallResult.model 或默认配置） */
    @Column(nullable = false, length = 64)
    private String model;

    /** 调用耗时（毫秒） */
    @Column(nullable = false)
    private Long durationMs;

    /** 调用结果（SUCCESS / DEGRADED / FAILED） */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private LlmCallStatus status;

    /** 失败原因枚举名（仅记 Reason 枚举名，不记 message） */
    @Column(length = 64)
    private String reason;

    /** prompt tokens（可选，模型可能不返回） */
    private Integer promptTokens;

    /** completion tokens（可选） */
    private Integer completionTokens;

    /** total tokens（可选） */
    private Integer totalTokens;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public LlmCallRecord() {
    }

    @jakarta.persistence.PrePersist
    void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
        if (this.durationMs == null) {
            this.durationMs = 0L;
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getAuditId() { return auditId; }
    public void setAuditId(String auditId) { this.auditId = auditId; }

    public LlmCallStage getStage() { return stage; }
    public void setStage(LlmCallStage stage) { this.stage = stage; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }

    public LlmCallStatus getStatus() { return status; }
    public void setStatus(LlmCallStatus status) { this.status = status; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public Integer getPromptTokens() { return promptTokens; }
    public void setPromptTokens(Integer promptTokens) { this.promptTokens = promptTokens; }

    public Integer getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(Integer completionTokens) { this.completionTokens = completionTokens; }

    public Integer getTotalTokens() { return totalTokens; }
    public void setTotalTokens(Integer totalTokens) { this.totalTokens = totalTokens; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}