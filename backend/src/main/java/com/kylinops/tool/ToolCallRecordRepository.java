package com.kylinops.tool;

import com.kylinops.common.enums.ToolCallStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 工具调用记录 Repository
 */
@Repository
public interface ToolCallRecordRepository extends JpaRepository<ToolCallRecord, Long> {

    /** 根据工具调用ID查询 */
    Optional<ToolCallRecord> findByToolCallId(String toolCallId);

    /** 查询某消息的所有工具调用记录 */
    List<ToolCallRecord> findByMessageIdOrderByCreatedAtAsc(Long messageId);

    /** 查询某工具的所有调用记录 */
    List<ToolCallRecord> findByToolNameOrderByCreatedAtDesc(String toolName);

    /** 查询某状态的所有调用记录 */
    List<ToolCallRecord> findByStatus(ToolCallStatus status);

    /** 根据 auditId 查询 */
    List<ToolCallRecord> findByAuditId(String auditId);
}
