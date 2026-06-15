package com.kylinops.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * LLM 调用审计记录 Repository（P3-T5）。
 *
 * <p>支持按 auditId 查询一次请求的所有 LLM 调用记录，用于审计回放。</p>
 */
public interface LlmCallRecordRepository extends JpaRepository<LlmCallRecord, Long> {

    List<LlmCallRecord> findByAuditId(String auditId);
}