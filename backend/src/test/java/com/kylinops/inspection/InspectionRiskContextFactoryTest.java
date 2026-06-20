package com.kylinops.inspection;

import com.kylinops.security.RiskEvaluationContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1-02 Task 3 — Risk-context factory deterministic serialization tests.
 *
 * <p>The factory must produce {@link RiskEvaluationContext} whose {@code content}
 * matches the design contract exactly: {@code <toolName> <canonical-json(params)>}
 * with JSON keys in lexicographic order and secret-like keys rejected rather than
 * written through.</p>
 */
@DisplayName("P1-02 T3 — InspectionRiskContextFactory")
class InspectionRiskContextFactoryTest {

    private final InspectionRiskContextFactory factory = new InspectionRiskContextFactory();

    @Test
    @DisplayName("journal_log_tool with lines + serviceName: deterministic canonical content")
    void canonicalContentMatchesSpec() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("lines", 50);
        params.put("serviceName", "nginx");

        RiskEvaluationContext ctx = factory.create("journal_log_tool", params);

        assertThat(ctx.getTargetType()).isEqualTo("tool");
        assertThat(ctx.getToolName()).isEqualTo("journal_log_tool");
        assertThat(ctx.getContent()).isEqualTo("journal_log_tool {\"lines\":50,\"serviceName\":\"nginx\"}");
    }

    @Test
    @DisplayName("Reversed input order → same canonical content (key order is alpha)")
    void keyOrderIsLexicographic() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("serviceName", "nginx");
        a.put("lines", 50);

        Map<String, Object> b = new LinkedHashMap<>();
        b.put("lines", 50);
        b.put("serviceName", "nginx");

        RiskEvaluationContext ca = factory.create("journal_log_tool", a);
        RiskEvaluationContext cb = factory.create("journal_log_tool", b);

        assertThat(ca.getContent()).isEqualTo(cb.getContent());
        assertThat(ca.getContent()).isEqualTo("journal_log_tool {\"lines\":50,\"serviceName\":\"nginx\"}");
    }

    @Test
    @DisplayName("password/secret/token/apiKey/privateKey keys rejected at factory boundary")
    void secretLikeKeysAreRejected() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("password", "hunter2");

        assertThatThrownBy(() -> factory.create("journal_log_tool", params))
                .isInstanceOf(InspectionValidationException.class)
                .hasMessageContaining("password");
    }

    @Test
    @DisplayName("token + apiKey + privateKey rejected too")
    void allSecretLikeKeysRejected() {
        for (String key : new String[]{"secret", "token", "apiKey", "privateKey"}) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put(key, "value");
            assertThatThrownBy(() -> factory.create("journal_log_tool", params))
                    .as("key %s should be rejected", key)
                    .isInstanceOf(InspectionValidationException.class)
                    .hasMessageContaining(key);
        }
    }

    @Test
    @DisplayName("Empty params → content is '<toolName> {}'")
    void emptyParamsProducesEmptyObject() {
        RiskEvaluationContext ctx = factory.create("disk_usage_tool", Map.of());

        assertThat(ctx.getContent()).isEqualTo("disk_usage_tool {}");
        assertThat(ctx.getParams()).isEmpty();
    }

    @Test
    @DisplayName("Non-secret keys are copied into ctx.params verbatim")
    void nonSecretKeysCopiedToParams() {
        Map<String, Object> params = Map.of("serviceName", "nginx", "lines", 50);

        RiskEvaluationContext ctx = factory.create("journal_log_tool", params);

        assertThat(ctx.getParams()).containsEntry("serviceName", "nginx");
        assertThat(ctx.getParams()).containsEntry("lines", 50);
        assertThat(ctx.getParams()).doesNotContainKey("password");
    }
}