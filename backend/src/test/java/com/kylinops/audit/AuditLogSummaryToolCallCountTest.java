package com.kylinops.audit;

import com.kylinops.common.enums.AuditStatus;
import com.kylinops.common.enums.IntentType;
import com.kylinops.common.enums.RiskDecision;
import com.kylinops.common.enums.RiskLevel;
import com.kylinops.executor.PendingActionRepository;
import com.kylinops.notification.NotificationRecordRepository;
import com.kylinops.security.RiskCheckRecordRepository;
import com.kylinops.tool.ToolCallRecordRepository;
import com.kylinops.tool.ToolCallRecordRepository.ToolCallCountProjection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审计列表 toolCallCount 填充测试（F-007）。
 * <p>
 * 验证：
 * — AuditLogSummary.toolCallCount 字段存在且被正确填充。
 * — 一页 N 条审计只触发 1 次 grouped aggregate 查询（禁止 N+1）。
 * — grouped aggregate 的入参包含当前页所有 auditId（in 子句）。
 * </p>
 */
@DisplayName("AuditLogSummary toolCallCount & N+1 防护")
class AuditLogSummaryToolCallCountTest {

    private AuditLogRepository auditLogRepository;
    private ToolCallRecordRepository toolCallRecordRepository;
    private RiskCheckRecordRepository riskCheckRecordRepository;
    private PendingActionRepository pendingActionRepository;
    private NotificationRecordRepository notificationRecordRepository;
    private AuditLogService auditLogService;

    @BeforeEach
    void setUp() {
        auditLogRepository = mock(AuditLogRepository.class);
        toolCallRecordRepository = mock(ToolCallRecordRepository.class);
        riskCheckRecordRepository = mock(RiskCheckRecordRepository.class);
        pendingActionRepository = mock(PendingActionRepository.class);
        notificationRecordRepository = mock(NotificationRecordRepository.class);
        auditLogService = new AuditLogService(
                auditLogRepository,
                toolCallRecordRepository,
                riskCheckRecordRepository,
                pendingActionRepository,
                notificationRecordRepository);
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("一页 20 条审计只触发 1 次 grouped count 查询（无 N+1）")
    void pageOfTwentyAuditsTriggersExactlyOneGroupedCountQuery() {
        // Arrange — 20 条审计实体
        List<AuditLog> entities = IntStream.range(0, 20)
                .mapToObj(i -> buildAudit(UUID.randomUUID().toString()))
                .toList();
        Page<AuditLog> repoPage = new PageImpl<>(entities, PageRequest.of(0, 20), 20);
        when(auditLogRepository.findAll(any(Specification.class), any(PageRequest.class)))
                .thenReturn(repoPage);

        // 返回的 grouped aggregate 投影：每条 auditId 对应一个固定计数
        List<ToolCallCountProjection> projections = new ArrayList<>();
        Map<String, Long> expectedCounts = new HashMap<>();
        for (int i = 0; i < entities.size(); i++) {
            AuditLog entity = entities.get(i);
            long count = i + 1;
            ToolCallCountProjection p = mock(ToolCallCountProjection.class);
            when(p.getAuditId()).thenReturn(entity.getAuditId());
            when(p.getCount()).thenReturn(count);
            projections.add(p);
            expectedCounts.put(entity.getAuditId(), count);
        }
        when(toolCallRecordRepository.countByAuditIdInGrouped(anyCollection()))
                .thenReturn(projections);

        // Act
        Page<AuditLogSummary> page = auditLogService.queryLogs(
                null, null, null, null, null, 0, 20);

        // Assert — 每条 summary 的 toolCallCount 与 aggregate 一致
        assertThat(page.getContent()).hasSize(20);
        for (AuditLogSummary s : page.getContent()) {
            assertThat(s.getToolCallCount())
                    .as("auditId=%s 的 toolCallCount 应来自 aggregate", s.getAuditId())
                    .isEqualTo(expectedCounts.get(s.getAuditId()));
        }

        // 关键断言：grouped aggregate 必须只调用 1 次
        verify(toolCallRecordRepository, times(1))
                .countByAuditIdInGrouped(anyCollection());

        // 单次调用的入参必须包含当前页全部 20 个 auditId
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> idsCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(toolCallRecordRepository).countByAuditIdInGrouped(idsCaptor.capture());
        Collection<String> capturedIds = idsCaptor.getValue();
        assertThat(capturedIds)
                .as("grouped aggregate 的入参应包含全部 auditId")
                .hasSize(20)
                .containsAll(expectedCounts.keySet());

        // 反向断言：不应出现按单 auditId 调 countByAuditId 的 N+1 路径
        verify(toolCallRecordRepository, never()).findByAuditId(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("空页面不调用 aggregate（避免无谓查询）")
    void emptyPageDoesNotCallAggregate() {
        Page<AuditLog> repoPage = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        when(auditLogRepository.findAll(any(Specification.class), any(PageRequest.class)))
                .thenReturn(repoPage);

        Page<AuditLogSummary> page = auditLogService.queryLogs(
                null, null, null, null, null, 0, 20);

        assertThat(page.getContent()).isEmpty();
        verify(toolCallRecordRepository, never())
                .countByAuditIdInGrouped(anyCollection());
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("auditId 不在 aggregate 投影中时 toolCallCount 默认为 0（不会崩溃）")
    void summaryDefaultsToZeroWhenAuditIdAbsentFromAggregate() {
        AuditLog entity = buildAudit(UUID.randomUUID().toString());
        Page<AuditLog> repoPage = new PageImpl<>(List.of(entity), PageRequest.of(0, 20), 1);
        when(auditLogRepository.findAll(any(Specification.class), any(PageRequest.class)))
                .thenReturn(repoPage);
        // aggregate 返回空（没有 ToolCallRecord）
        when(toolCallRecordRepository.countByAuditIdInGrouped(anyCollection()))
                .thenReturn(List.of());

        Page<AuditLogSummary> page = auditLogService.queryLogs(
                null, null, null, null, null, 0, 20);

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getToolCallCount())
                .as("aggregate 缺失的 auditId 必须默认为 0，不可抛 NPE")
                .isZero();
    }

    private AuditLog buildAudit(String auditId) {
        AuditLog log = new AuditLog();
        log.setAuditId(auditId);
        log.setSessionId("session-" + auditId);
        log.setUserInput("健康巡检");
        log.setIntentType(IntentType.SYSTEM_CHECK);
        log.setRiskLevel(RiskLevel.L0);
        log.setRiskDecision(RiskDecision.ALLOW);
        log.setStatus(AuditStatus.SUCCESS);
        log.setCreatedAt(LocalDateTime.now());
        return log;
    }
}
