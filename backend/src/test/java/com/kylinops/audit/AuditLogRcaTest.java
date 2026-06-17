package com.kylinops.audit;

import com.kylinops.rca.RootCauseChain;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class AuditLogRcaTest {

    @Autowired
    private AuditLogService auditLogService;

    @Test
    void rca_serialize_deserialize_roundtrip() {
        RootCauseChain original = RootCauseChain.builder()
                .symptom("test").confidence(0.5)
                .evidence(java.util.List.of())
                .build();
        String json = auditLogService.serializeRca(original);
        assertNotNull(json);
        RootCauseChain back = auditLogService.deserializeRca(json);
        assertNotNull(back);
        assertEquals("test", back.getSymptom());
        assertEquals(0.5, back.getConfidence());
    }

    @Test
    void null_rca_returns_null() {
        assertNull(auditLogService.serializeRca(null));
        assertNull(auditLogService.deserializeRca(null));
    }
}