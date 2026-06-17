package com.kylinops.agent.intelligence;

import com.kylinops.agent.IntentClassifier;
import com.kylinops.common.enums.IntentType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HybridIntentServiceOfflineTest {

    @Test
    void llm_failure_triggers_offline_faq() {
        IntentClassifier classifier = new IntentClassifier();
        LlmIntentParser parser = mock(LlmIntentParser.class);
        when(parser.parse(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new LlmIntentParser.ParsedLlmIntent(
                        LlmIntentParser.ParsedLlmIntent.Outcome.INVALID,
                        IntentType.UNKNOWN, 0.0, java.util.Map.of()));
        OfflineFaqService faq = new OfflineFaqService();
        HybridIntentService service = new HybridIntentService(classifier, parser, faq);

        IntentResolution r = service.resolve("帮我重启 nginx");
        assertEquals(IntentType.SERVICE_DIAGNOSIS, r.getIntentType());
        assertEquals(IntentResolution.Source.RULE, r.getSource(),
                "FAQ 命中应保持 RULE 来源（便于审计追溯）");
    }

    @Test
    void llm_failure_no_faq_match_returns_unknown() {
        IntentClassifier classifier = new IntentClassifier();
        LlmIntentParser parser = mock(LlmIntentParser.class);
        when(parser.parse(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new LlmIntentParser.ParsedLlmIntent(
                        LlmIntentParser.ParsedLlmIntent.Outcome.INVALID,
                        IntentType.UNKNOWN, 0.0, java.util.Map.of()));
        OfflineFaqService faq = new OfflineFaqService();
        HybridIntentService service = new HybridIntentService(classifier, parser, faq);

        IntentResolution r = service.resolve("今天天气很好");
        assertEquals(IntentType.UNKNOWN, r.getIntentType());
        assertEquals(IntentResolution.Source.FALLBACK, r.getSource());
    }
}
