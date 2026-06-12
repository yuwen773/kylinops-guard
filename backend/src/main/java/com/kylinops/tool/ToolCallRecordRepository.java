package com.kylinops.tool;

import com.kylinops.common.enums.ToolCallStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
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

    /**
     * Grouped aggregate for the Tool Center statistics panel (Task 11).
     * <p>
     * Returns one {@link ToolStatsProjection} per toolName in a SINGLE query
     * (no N+1). Tools with zero records are absent from the result — the
     * caller defaults to callCount=0, successRate=null, lastCalledAt=null.
     * </p>
     *
     * <p>
     * Interface-based projection: Spring Data JPA binds JPQL aliases to
     * getter-style methods, no constructor expression required.
     * </p>
     *
     * <ul>
     *   <li>{@code callCount} — total record count (all statuses)</li>
     *   <li>{@code terminalCount} — count of records in terminal status
     *       (SUCCESS + FAILED + TIMEOUT + BLOCKED) — the successRate denominator</li>
     *   <li>{@code successCount} — count of SUCCESS records</li>
     *   <li>{@code lastCalledAt} — max createdAt across all records</li>
     * </ul>
     * The ToolController derives {@code successRate = successCount / terminalCount}
     * with the contract: null when terminalCount == 0.
     *
     * @param toolNames tool names to look up; never null/empty
     * @return one projection per toolName with at least one record
     */
    @Query("""
            SELECT t.toolName AS toolName,
                   COUNT(t) AS callCount,
                   SUM(CASE WHEN t.status IN (
                       com.kylinops.common.enums.ToolCallStatus.SUCCESS,
                       com.kylinops.common.enums.ToolCallStatus.FAILED,
                       com.kylinops.common.enums.ToolCallStatus.TIMEOUT,
                       com.kylinops.common.enums.ToolCallStatus.BLOCKED) THEN 1L ELSE 0L END) AS terminalCount,
                   SUM(CASE WHEN t.status = com.kylinops.common.enums.ToolCallStatus.SUCCESS THEN 1L ELSE 0L END) AS successCount,
                   MAX(t.createdAt) AS lastCalledAt
            FROM ToolCallRecord t
            WHERE t.toolName IN :toolNames
            GROUP BY t.toolName
            """)
    List<ToolStatsProjection> findStatsByToolNameIn(
            @Param("toolNames") Collection<String> toolNames);

    /**
     * Interface projection for {@link #findStatsByToolNameIn(Collection)}.
     * <p>
     * Spring Data JPA binds the JPQL aliases to these getters at runtime.
     * Aggregate return types use {@code Long} (boxed) because SUM/COUNT in
     * JPQL may return {@code BigInteger} depending on the dialect — Spring
     * Data widens to {@code Long} but never to a primitive {@code long}.
     * </p>
     */
    interface ToolStatsProjection {
        String getToolName();

        Long getCallCount();

        Long getTerminalCount();

        Long getSuccessCount();

        /** LocalDateTime because ToolCallRecord.createdAt is a LocalDateTime. */
        LocalDateTime getLastCalledAt();
    }
}