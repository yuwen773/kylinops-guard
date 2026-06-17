package com.kylinops.agent.intelligence;

import com.kylinops.common.enums.IntentType;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class OfflineFaqServiceTest {

    private final OfflineFaqService faq = new OfflineFaqService();

    @Test
    void fuzzy_match_restart_nginx_returns_service_diagnosis() {
        Optional<IntentResolution> r = faq.fuzzyMatch("帮我重启 nginx");
        assertTrue(r.isPresent());
        assertEquals(IntentType.SERVICE_DIAGNOSIS, r.get().getIntentType());
    }

    @Test
    void fuzzy_match_clear_log_returns_file_operation() {
        Optional<IntentResolution> r = faq.fuzzyMatch("清理系统日志");
        assertTrue(r.isPresent());
        assertEquals(IntentType.FILE_OPERATION, r.get().getIntentType());
    }

    @Test
    void fuzzy_match_unrelated_returns_empty() {
        assertTrue(faq.fuzzyMatch("今天天气很好").isEmpty());
    }
}
