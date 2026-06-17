package com.kylinops.notification;

import com.kylinops.common.enums.IntentType;
import com.kylinops.common.enums.RiskDecision;
import com.kylinops.common.enums.RiskLevel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * NotificationEventFactory 单元测试(纯 POJO,无 Spring Context)。
 */
class NotificationEventFactoryTest {

    private final NotificationEventFactory factory = new NotificationEventFactory();

    @Test
    void l4Block_populatesAllFields() {
        NotificationEvent e = factory.l4Block("audit-1", "sess-1",
                RiskLevel.L4, RiskDecision.BLOCK, "rm_root_recursive", "rm -rf / detected");
        assertNotNull(e);
        assertSame(NotificationEventType.L4_BLOCK, e.getEventType());
        assertSame(NotificationSeverity.CRITICAL, e.getSeverity());
        assertEquals("L4 阻断告警", e.getTitle());
        assertNotNull(e.getSummary());
        assertEquals("rm_root_recursive", e.getMatchedRuleId());
        assertSame(RiskLevel.L4, e.getRiskLevel());
        assertSame(RiskDecision.BLOCK, e.getRiskDecision());
        assertSame(IntentType.COMMAND_EXECUTION, e.getIntentType());
        assertEquals("audit-1", e.getAuditId());
        assertEquals("sess-1", e.getSessionId());
        assertNull(e.getEventId(), "eventId 由 Service 补,factory 不设置");
        assertNull(e.getOccurredAt(), "occurredAt 由 Service 补,factory 不设置");
    }

    @Test
    void l4Block_handlesNullRuleId() {
        NotificationEvent e = factory.l4Block("a", "s", RiskLevel.L4, RiskDecision.BLOCK,
                null, "no rule id");
        assertNotNull(e.getSummary());
        assertNotNull(e.getDetail());
    }

    @Test
    void promptInjectionBlock_setsPattern() {
        NotificationEvent e = factory.promptInjectionBlock("a", "s", "ignore_rules", "injection hit");
        assertSame(NotificationEventType.PROMPT_INJECTION_BLOCK, e.getEventType());
        assertSame(NotificationSeverity.CRITICAL, e.getSeverity());
        assertEquals("ignore_rules", e.getPromptInjectionPattern());
        assertSame(RiskLevel.L4, e.getRiskLevel());
        assertSame(RiskDecision.BLOCK, e.getRiskDecision());
    }

    @Test
    void l2ConfirmRequired_usesWarningSeverity() {
        NotificationEvent e = factory.l2ConfirmRequired("a", "s",
                IntentType.SERVICE_DIAGNOSIS, RiskLevel.L2, "act-1", "重启 nginx");
        assertSame(NotificationEventType.L2_CONFIRM_REQUIRED, e.getEventType());
        assertSame(NotificationSeverity.WARNING, e.getSeverity());
        assertSame(RiskLevel.L2, e.getRiskLevel());
        assertSame(RiskDecision.CONFIRM, e.getRiskDecision());
        assertSame(IntentType.SERVICE_DIAGNOSIS, e.getIntentType());
    }

    @Test
    void serviceAbnormal_setsConfidenceAndService() {
        NotificationEvent e = factory.serviceAbnormal("a", "s", "nginx", 0.85, "端口未监听");
        assertSame(NotificationEventType.SERVICE_ABNORMAL, e.getEventType());
        assertSame(NotificationSeverity.INFO, e.getSeverity());
        assertEquals("nginx", e.getServiceName());
        assertEquals(0.85, e.getRcaConfidence());
    }

    @Test
    void diskRisk_setsPathAndPercent() {
        NotificationEvent e = factory.diskRisk("a", "s", "/var", 92.5, 0.80, "磁盘 92%");
        assertSame(NotificationEventType.DISK_RISK, e.getEventType());
        assertSame(NotificationSeverity.INFO, e.getSeverity());
        assertEquals("/var", e.getDiskPath());
        assertEquals(92.5, e.getDiskUsagePercent());
    }

    @Test
    void diskRisk_handlesNullPath() {
        NotificationEvent e = factory.diskRisk("a", "s", null, 95.0, 0.5, "X");
        assertNotNull(e.getSummary());
        assertEquals(95.0, e.getDiskUsagePercent());
    }
}
