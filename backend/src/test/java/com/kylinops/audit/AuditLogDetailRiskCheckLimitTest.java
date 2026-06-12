package com.kylinops.audit;

import com.kylinops.common.enums.AuditStatus;
import com.kylinops.common.enums.IntentType;
import com.kylinops.common.enums.RiskDecision;
import com.kylinops.common.enums.RiskLevel;
import com.kylinops.security.RiskCheckRecord;
import com.kylinops.security.RiskCheckRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计详情 RiskCheckRecord 上限测试（F-006）
 * <p>
 * 验证 /api/audit/logs/{auditId} 返回的详情中 RiskCheckRecord 列表：
 * — 单 auditId 详情最多 50 条 RiskCheckRecord
 * — 按 checkedAt 降序排列
 * — 不影响其他调用方使用的原 findByAuditId
 * </p>
 */
@SpringBootTest
@DisplayName("AuditLog 详情 RiskCheckRecord 上限（F-006）")
class AuditLogDetailRiskCheckLimitTest {

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private RiskCheckRecordRepository riskCheckRecordRepository;

    private String auditId;

    @BeforeEach
    void setUp() {
        // 准备一个 auditId，挂 100 条 RiskCheckRecord
        auditId = UUID.randomUUID().toString();

        AuditLog log = new AuditLog();
        log.setAuditId(auditId);
        log.setSessionId("sess-f006");
        log.setUserInput("F-006 详情分页上限测试");
        log.setIntentType(IntentType.SYSTEM_CHECK);
        log.setRiskLevel(RiskLevel.L0);
        log.setRiskDecision(RiskDecision.ALLOW);
        log.setStatus(AuditStatus.SUCCESS);
        auditLogRepository.save(log);

        // 插入 100 条 RiskCheckRecord，checkedAt 递增以便验证顺序
        LocalDateTime base = LocalDateTime.of(2026, 1, 1, 0, 0, 0);
        for (int i = 0; i < 100; i++) {
            RiskCheckRecord rcr = new RiskCheckRecord();
            rcr.setAuditId(auditId);
            rcr.setTargetType("content");
            rcr.setTargetContent("F-006 fixture " + i);
            rcr.setRiskLevel(RiskLevel.L0);
            rcr.setRiskDecision(RiskDecision.ALLOW);
            rcr.setMatchedRules("[]");
            rcr.setReason("F-006 测试记录 " + i);
            rcr.setSafeSuggestion("无");
            rcr.setCheckedAt(base.plusSeconds(i));
            riskCheckRecordRepository.save(rcr);
        }
    }

    @Test
    @DisplayName("详情页返回的 RiskCheckRecord 数量上限 50")
    void auditDetailLimitsRiskCheckRecordsTo50() {
        AuditLogDetail detail = auditLogService.getDetail(auditId).orElseThrow();

        assertThat(detail.getRiskChecks())
                .as("详情页 RiskCheckRecord 应被上限 50 条")
                .hasSizeLessThanOrEqualTo(50);
        assertThat(detail.getRiskChecks())
                .as("上限 50 时正好 50 条")
                .hasSize(50);
    }

    @Test
    @DisplayName("详情页 RiskCheckRecord 按 checkedAt 降序")
    void auditDetailReturnsLatestRiskChecksFirst() {
        AuditLogDetail detail = auditLogService.getDetail(auditId).orElseThrow();

        assertThat(detail.getRiskChecks()).isNotEmpty();
        // 详情只保留最近 50 条 — 即 checkedAt 在 [50, 100) 区间
        // 列表头部（最新）应对应原 100 条中第 99 条（base + 99s）
        // 列表尾部（最旧）应对应原 100 条中第 50 条（base + 50s）
        for (int i = 0; i < detail.getRiskChecks().size() - 1; i++) {
            LocalDateTime current = detail.getRiskChecks().get(i).getCheckedAt();
            LocalDateTime next = detail.getRiskChecks().get(i + 1).getCheckedAt();
            assertThat(current)
                    .as("第 %d 条应早于或等于第 %d 条（降序）", i, i + 1)
                    .isAfterOrEqualTo(next);
        }
        // 头部应是最新的（base + 99s）
        assertThat(detail.getRiskChecks().get(0).getCheckedAt())
                .isEqualTo(LocalDateTime.of(2026, 1, 1, 0, 1, 39));
        // 尾部应是最早被保留的（base + 50s）
        assertThat(detail.getRiskChecks().get(detail.getRiskChecks().size() - 1).getCheckedAt())
                .isEqualTo(LocalDateTime.of(2026, 1, 1, 0, 0, 50));
    }

    @Test
    @DisplayName("原 findByAuditId 仍返回所有 100 条（保留给其他调用方）")
    void originalFindByAuditIdUnchanged() {
        assertThat(riskCheckRecordRepository.findByAuditId(auditId))
                .as("原方法不受影响，应返回全部 100 条")
                .hasSize(100);
    }

    @Test
    @DisplayName("findByAuditIdAndTargetType 不受影响（其他 4 个方法之一）")
    void otherFindByAuditIdVariantUnchanged() {
        assertThat(riskCheckRecordRepository.findByAuditIdAndTargetType(auditId, "content"))
                .as("原方法不受影响，应返回全部 100 条")
                .hasSize(100);
    }
}
