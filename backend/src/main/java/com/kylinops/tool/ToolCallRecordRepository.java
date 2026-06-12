package com.kylinops.tool;

import com.kylinops.common.enums.ToolCallStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
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

    /**
     * Grouped aggregate: count ToolCallRecord rows per auditId in a single query.
     * <p>
     * Used by AuditLogService to populate {@code AuditLogSummary.toolCallCount}
     * for a page of audit logs WITHOUT issuing one count query per row
     * (i.e. prevents the N+1 anti-pattern).
     * </p>
     * <p>
     * The projection interface provides {@code getAuditId()} / {@code getCount()}
     * for direct access from the service layer.
     * </p>
     *
     * @param auditIds a collection of audit IDs from the current page
     * @return one projection per auditId that has at least one ToolCallRecord;
     *         auditIds with zero records are NOT in the result — the caller
     *         must default to 0.
     */
    @Query("""
            SELECT t.auditId AS auditId, COUNT(t) AS count
            FROM ToolCallRecord t
            WHERE t.auditId IN :auditIds
            GROUP BY t.auditId
            """)
    List<ToolCallCountProjection> countByAuditIdInGrouped(
            @Param("auditIds") Collection<String> auditIds);

    /** Projection for {@link #countByAuditIdInGrouped(Collection)}. */
    interface ToolCallCountProjection {
        String getAuditId();

        long getCount();
    }
}
