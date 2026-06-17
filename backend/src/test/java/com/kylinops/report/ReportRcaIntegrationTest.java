package com.kylinops.report;

import com.kylinops.audit.AuditLogService;
import com.kylinops.rca.RootCauseChain;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class ReportRcaIntegrationTest {

    @Autowired
    private ReportService reportService;
    @Autowired
    private AuditLogService auditLogService;

    @Test
    void rca_three_layer_roundtrip() {
        RootCauseChain original = RootCauseChain.builder()
                .symptom("disk 86%").confidence(0.86).build();
        String json = auditLogService.serializeRca(original);
        RootCauseChain back = auditLogService.deserializeRca(json);
        assertNotNull(back);
        assertEquals(original.getSymptom(), back.getSymptom());
    }
}