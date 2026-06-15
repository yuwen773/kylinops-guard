package com.kylinops.agent.intelligence;

import com.kylinops.agent.IntentClassifier;
import com.kylinops.common.enums.IntentType;
import com.kylinops.llm.ChatMessage;
import com.kylinops.llm.LlmCallResult;
import com.kylinops.llm.LlmClient;
import com.kylinops.llm.LlmClientException;
import com.kylinops.llm.LlmStage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * HybridIntentService 单元测试 (P3-T2).
 *
 * <p>覆盖以下契约：</p>
 * <ul>
 *   <li>规则命中 → 不调用 LLM，source=RULE, confidence=1.0</li>
 *   <li>规则未命中 → 调用 LLM 解析，解析成功 → source=LLM</li>
 *   <li>LLM 返回非白名单 IntentType → 回退 UNKNOWN, source=FALLBACK</li>
 *   <li>LLM 返回非法 JSON → 回退 UNKNOWN, source=FALLBACK</li>
 *   <li>LLM 抛 LlmClientException → 回退 UNKNOWN, source=FALLBACK, 不向上抛</li>
 *   <li>置信度 < 0.75 → 回退 UNKNOWN, source=FALLBACK</li>
 *   <li>Mock LlmClient 验证：从 LlmStage.INTENT 调用，messages 含 system + user</li>
 *   <li>LLM 关闭（null LlmClient）→ 仍能工作（仅规则路径）</li>
 *   <li>LLM 不得覆盖 COMMAND_EXECUTION（始终走规则路径）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HybridIntentService — 混合意图识别（规则优先 + LLM 后备）")
class HybridIntentServiceTest {

    @Mock
    private LlmClient llmClient;

    private HybridIntentService service;

    @BeforeEach
    void setUp() {
        IntentClassifier classifier = new IntentClassifier();
        LlmIntentParser parser = new LlmIntentParser(llmClient, 0.75);
        service = new HybridIntentService(classifier, parser);
    }

    // ==================== 规则命中 → 不调 LLM ====================

    @Test
    @DisplayName("规则命中 SYSTEM_CHECK → 不调用 LLM, source=RULE, confidence=1.0")
    void ruleHit_systemCheck_doesNotInvokeLlm() {
        IntentResolution res = service.resolve("帮我检查系统健康状态");

        assertThat(res.getIntentType()).isEqualTo(IntentType.SYSTEM_CHECK);
        assertThat(res.getSource()).isEqualTo(IntentResolution.Source.RULE);
        assertThat(res.getConfidence()).isEqualTo(1.0);

        verifyNoInteractions(llmClient);
    }

    @Test
    @DisplayName("规则命中 DISK_DIAGNOSIS → 不调用 LLM")
    void ruleHit_diskDiagnosis_doesNotInvokeLlm() {
        IntentResolution res = service.resolve("磁盘快满了");

        assertThat(res.getIntentType()).isEqualTo(IntentType.DISK_DIAGNOSIS);
        assertThat(res.getSource()).isEqualTo(IntentResolution.Source.RULE);
        assertThat(res.getConfidence()).isEqualTo(1.0);

        verifyNoInteractions(llmClient);
    }

    @Test
    @DisplayName("规则命中 COMMAND_EXECUTION（危险命令）→ 不调用 LLM（即使 LLM 在场）")
    void ruleHit_dangerousCommand_doesNotInvokeLlm() {
        // 关键安全约束：危险命令必须走规则路径，不允许 LLM 覆盖
        IntentResolution res = service.resolve("rm -rf /");

        assertThat(res.getIntentType()).isEqualTo(IntentType.COMMAND_EXECUTION);
        assertThat(res.getSource()).isEqualTo(IntentResolution.Source.RULE);
        assertThat(res.getConfidence()).isEqualTo(1.0);

        verifyNoInteractions(llmClient);
    }

    // ==================== LLM 路径：成功解析 ====================

    @Test
    @DisplayName("规则未命中 → 触发 LLM 调用 → Mock 返回有效 JSON → 正确解析")
    void unknownInput_triggersLlmAndParsesValidJson() {
        String validJson = "{\"intent\":\"DISK_DIAGNOSIS\",\"confidence\":0.92,\"params\":{\"path\":\"/var\"}}";
        when(llmClient.complete(eq(LlmStage.INTENT), anyList()))
                .thenReturn(new LlmCallResult(validJson, "deepseek-chat", 10, 20, 30, "stop", "{}"));

        IntentResolution res = service.resolve("帮我看看那个目录咋回事");

        assertThat(res.getIntentType()).isEqualTo(IntentType.DISK_DIAGNOSIS);
        assertThat(res.getSource()).isEqualTo(IntentResolution.Source.LLM);
        assertThat(res.getConfidence()).isEqualTo(0.92);
        assertThat(res.getParams()).containsEntry("path", "/var");
    }

    @Test
    @DisplayName("LLM 调用验证：使用 LlmStage.INTENT，messages 含 system + user")
    void llmCallUsesIntentStageAndSystemUserMessages() {
        String validJson = "{\"intent\":\"SYSTEM_CHECK\",\"confidence\":0.88,\"params\":{}}";
        when(llmClient.complete(eq(LlmStage.INTENT), anyList()))
                .thenReturn(new LlmCallResult(validJson, "deepseek-chat", 10, 20, 30, "stop", "{}"));

        service.resolve("帮我看看这个系统咋样");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmClient).complete(eq(LlmStage.INTENT), captor.capture());

        List<ChatMessage> messages = captor.getValue();
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).role()).isEqualTo("system");
        assertThat(messages.get(0).content()).isNotBlank();
        assertThat(messages.get(0).content().toLowerCase()).contains("intent", "json");
        assertThat(messages.get(1).role()).isEqualTo("user");
        assertThat(messages.get(1).content()).isEqualTo("帮我看看这个系统咋样");
    }

    // ==================== LLM 路径：非白名单 IntentType ====================

    @Test
    @DisplayName("LLM 返回非白名单 IntentType (COMMAND_EXECUTION) → 回退 UNKNOWN")
    void llmReturnsDisallowedIntentType_fallsBackToUnknown() {
        // 关键安全约束：COMMAND_EXECUTION 必须在白名单外
        String json = "{\"intent\":\"COMMAND_EXECUTION\",\"confidence\":0.99,\"params\":{}}";
        when(llmClient.complete(eq(LlmStage.INTENT), anyList()))
                .thenReturn(new LlmCallResult(json, "deepseek-chat", 10, 20, 30, "stop", "{}"));

        IntentResolution res = service.resolve("随便聊两句");

        assertThat(res.getIntentType()).isEqualTo(IntentType.UNKNOWN);
        assertThat(res.getSource()).isEqualTo(IntentResolution.Source.FALLBACK);
        assertThat(res.getConfidence()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("LLM 返回 enum 都不存在的 IntentType → 回退 UNKNOWN")
    void llmReturnsUnknownIntentType_fallsBackToUnknown() {
        String json = "{\"intent\":\"NOT_A_REAL_INTENT\",\"confidence\":0.95,\"params\":{}}";
        when(llmClient.complete(eq(LlmStage.INTENT), anyList()))
                .thenReturn(new LlmCallResult(json, "deepseek-chat", 10, 20, 30, "stop", "{}"));

        IntentResolution res = service.resolve("无意义的输入");

        assertThat(res.getIntentType()).isEqualTo(IntentType.UNKNOWN);
        assertThat(res.getSource()).isEqualTo(IntentResolution.Source.FALLBACK);
    }

    // ==================== LLM 路径：非法 JSON ====================

    @Test
    @DisplayName("LLM 返回非法 JSON → 回退 UNKNOWN, source=FALLBACK")
    void llmReturnsInvalidJson_fallsBackToUnknown() {
        String invalidJson = "this is not json { ]";
        when(llmClient.complete(eq(LlmStage.INTENT), anyList()))
                .thenReturn(new LlmCallResult(invalidJson, "deepseek-chat", 10, 20, 30, "stop", "{}"));

        IntentResolution res = service.resolve("乱码输入");

        assertThat(res.getIntentType()).isEqualTo(IntentType.UNKNOWN);
        assertThat(res.getSource()).isEqualTo(IntentResolution.Source.FALLBACK);
        assertThat(res.getConfidence()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("LLM 返回 JSON 但缺关键字段 → 回退 UNKNOWN")
    void llmReturnsJsonMissingFields_fallsBackToUnknown() {
        String missingFields = "{\"foo\":\"bar\"}";
        when(llmClient.complete(eq(LlmStage.INTENT), anyList()))
                .thenReturn(new LlmCallResult(missingFields, "deepseek-chat", 10, 20, 30, "stop", "{}"));

        IntentResolution res = service.resolve("缺字段的输入");

        assertThat(res.getIntentType()).isEqualTo(IntentType.UNKNOWN);
        assertThat(res.getSource()).isEqualTo(IntentResolution.Source.FALLBACK);
    }

    // ==================== LLM 路径：异常 ====================

    @Test
    @DisplayName("LLM 抛 LlmClientException(TIMEOUT) → 回退 UNKNOWN, 不向上抛")
    void llmThrowsTimeout_fallsBackToUnknown_doesNotPropagate() {
        when(llmClient.complete(eq(LlmStage.INTENT), anyList()))
                .thenThrow(new LlmClientException(LlmClientException.Reason.TIMEOUT, "intent timeout"));

        IntentResolution res = service.resolve("LLM 慢死了");

        assertThat(res.getIntentType()).isEqualTo(IntentType.UNKNOWN);
        assertThat(res.getSource()).isEqualTo(IntentResolution.Source.FALLBACK);
        assertThat(res.getConfidence()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("LLM 抛 LlmClientException(AUTH) → 回退 UNKNOWN, 不向上抛（fail-closed）")
    void llmThrowsAuth_fallsBackToUnknown_doesNotPropagate() {
        when(llmClient.complete(eq(LlmStage.INTENT), anyList()))
                .thenThrow(new LlmClientException(LlmClientException.Reason.AUTH, "401 invalid api key"));

        IntentResolution res = service.resolve("鉴权失败的输入");

        assertThat(res.getIntentType()).isEqualTo(IntentType.UNKNOWN);
        assertThat(res.getSource()).isEqualTo(IntentResolution.Source.FALLBACK);
    }

    @Test
    @DisplayName("LLM 抛任意 RuntimeException（非 LlmClientException）→ 回退 UNKNOWN, 不向上抛")
    void llmThrowsUnexpectedRuntimeException_fallsBackToUnknown_doesNotPropagate() {
        when(llmClient.complete(eq(LlmStage.INTENT), anyList()))
                .thenThrow(new RuntimeException("unexpected boom"));

        IntentResolution res = service.resolve("未知异常的输入");

        assertThat(res.getIntentType()).isEqualTo(IntentType.UNKNOWN);
        assertThat(res.getSource()).isEqualTo(IntentResolution.Source.FALLBACK);
    }

    // ==================== 置信度阈值 ====================

    @Test
    @DisplayName("置信度 = 0.75（等于阈值）→ 接受（>=）")
    void confidenceAtThreshold_isAccepted() {
        String json = "{\"intent\":\"SYSTEM_CHECK\",\"confidence\":0.75,\"params\":{}}";
        when(llmClient.complete(eq(LlmStage.INTENT), anyList()))
                .thenReturn(new LlmCallResult(json, "deepseek-chat", 10, 20, 30, "stop", "{}"));

        IntentResolution res = service.resolve("边界置信度");

        assertThat(res.getIntentType()).isEqualTo(IntentType.SYSTEM_CHECK);
        assertThat(res.getSource()).isEqualTo(IntentResolution.Source.LLM);
    }

    @Test
    @DisplayName("置信度 = 0.74（< 阈值）→ 回退 UNKNOWN")
    void confidenceBelowThreshold_fallsBackToUnknown() {
        String json = "{\"intent\":\"SYSTEM_CHECK\",\"confidence\":0.74,\"params\":{}}";
        when(llmClient.complete(eq(LlmStage.INTENT), anyList()))
                .thenReturn(new LlmCallResult(json, "deepseek-chat", 10, 20, 30, "stop", "{}"));

        IntentResolution res = service.resolve("低于阈值的置信度");

        assertThat(res.getIntentType()).isEqualTo(IntentType.UNKNOWN);
        assertThat(res.getSource()).isEqualTo(IntentResolution.Source.FALLBACK);
    }

    // ==================== LLM 关闭：null client ====================

    @Test
    @DisplayName("LlmClient 为 null（LLM 关闭）→ 仍能工作，规则未命中 → UNKNOWN, source=FALLBACK")
    void llmClientIsNull_worksViaRulePathOnly() {
        IntentClassifier classifier = new IntentClassifier();
        LlmIntentParser parser = new LlmIntentParser(null, 0.75);
        HybridIntentService localService = new HybridIntentService(classifier, parser);

        IntentResolution ruleHit = localService.resolve("帮我检查系统健康状态");
        assertThat(ruleHit.getIntentType()).isEqualTo(IntentType.SYSTEM_CHECK);
        assertThat(ruleHit.getSource()).isEqualTo(IntentResolution.Source.RULE);

        IntentResolution fallback = localService.resolve("规则无法识别的输入");
        assertThat(fallback.getIntentType()).isEqualTo(IntentType.UNKNOWN);
        assertThat(fallback.getSource()).isEqualTo(IntentResolution.Source.FALLBACK);
    }

    // ==================== 边界：空 / null 输入 ====================

    @Test
    @DisplayName("空字符串 → UNKNOWN, source=FALLBACK, 不调 LLM")
    void emptyInput_returnsUnknownWithoutInvokingLlm() {
        IntentResolution res = service.resolve("");

        assertThat(res.getIntentType()).isEqualTo(IntentType.UNKNOWN);
        assertThat(res.getSource()).isEqualTo(IntentResolution.Source.FALLBACK);
        assertThat(res.getConfidence()).isEqualTo(0.0);

        verify(llmClient, never()).complete(any(), anyList());
    }

    @Test
    @DisplayName("null 输入 → UNKNOWN, source=FALLBACK, 不调 LLM")
    void nullInput_returnsUnknownWithoutInvokingLlm() {
        IntentResolution res = service.resolve(null);

        assertThat(res.getIntentType()).isEqualTo(IntentType.UNKNOWN);
        assertThat(res.getSource()).isEqualTo(IntentResolution.Source.FALLBACK);

        verify(llmClient, never()).complete(any(), anyList());
    }

    // ==================== 参数过滤：allowlist ====================

    @Test
    @DisplayName("LLM 返回非白名单 param key → 仅保留白名单 key")
    void llmReturnsNonAllowlistedParamKey_dropsIt() {
        // 输入必须不命中任何规则 → 走 LLM 路径
        String json = "{\"intent\":\"SERVICE_DIAGNOSIS\",\"confidence\":0.9,"
                + "\"params\":{\"serviceName\":\"nginx\",\"evilKey\":\"rm -rf /\",\"__proto__\":{}}}";
        when(llmClient.complete(eq(LlmStage.INTENT), anyList()))
                .thenReturn(new LlmCallResult(json, "deepseek-chat", 10, 20, 30, "stop", "{}"));

        IntentResolution res = service.resolve("运维告警了");

        assertThat(res.getIntentType()).isEqualTo(IntentType.SERVICE_DIAGNOSIS);
        assertThat(res.getSource()).isEqualTo(IntentResolution.Source.LLM);
        // 仅有 serviceName 通过 allowlist；evilKey 与 __proto__ 应被丢弃
        assertThat(res.getParams()).containsOnlyKeys("serviceName");
        assertThat(res.getParams()).containsEntry("serviceName", "nginx");
    }
}
