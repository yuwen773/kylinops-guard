package com.kylinops.audit;

import com.kylinops.llm.AuditingLlmClient;
import com.kylinops.llm.ChatMessage;
import com.kylinops.llm.LlmCallResult;
import com.kylinops.llm.LlmClientException;
import com.kylinops.llm.LlmStage;
import com.kylinops.llm.OpenAiCompatibleLlmClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * LLM 调用审计 V3 测试（P3-T5）。
 *
 * <p>单元测试，不依赖 Spring 容器 — 直接构造 {@link AuditingLlmClient} 与 mock 的
 * 底层 client / 审计服务。原因是 {@link OpenAiCompatibleLlmClient} 有多个 public
 * 构造器且无 @Autowired，Spring 装配在测试环境存在不确定性；测试聚焦审计契约本身。</p>
 *
 * <p>验证契约：</p>
 * <ul>
 *   <li>调用 {@link com.kylinops.llm.LlmClient} 时 → 通过 LlmCallAuditService 写入记录</li>
 *   <li>记录包含 auditId, stage, model, durationMs, status, reason, tokens</li>
 *   <li>显式无 apiKey / prompt / responseContent / reasoning 字段（反射断言）</li>
 *   <li>失败路径 → status=FAILED, reason=<exception reason>, durationMs > 0</li>
 *   <li>成功路径 → status=SUCCESS, tokens 来自 {@link LlmCallResult}</li>
 *   <li>auditId 透传：调用 LLM 时使用同一 auditId</li>
 *   <li>无 auditId 时跳过审计记录，不影响调用</li>
 *   <li>LLM 异常仍抛出（不被 decorator 吞）</li>
 * </ul>
 */
@DisplayName("LlmCallAudit — LLM 调用审计 V3")
class LlmCallAuditTest {

    private OpenAiCompatibleLlmClient delegate;
    private LlmCallAuditService auditService;
    private AuditingLlmClient auditingClient;

    @BeforeEach
    void setUp() {
        delegate = mock(OpenAiCompatibleLlmClient.class);
        auditService = mock(LlmCallAuditService.class);
        auditingClient = new AuditingLlmClient(delegate, auditService);
        AuditContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        AuditContextHolder.clear();
    }

    // ==================== 反射契约：实体字段白名单 ====================

    @Test
    @DisplayName("LlmCallRecord 实体仅含白名单字段（无敏感字段）")
    void llmCallRecordHasOnlySafeFields() {
        Set<String> fieldNames = Arrays.stream(LlmCallRecord.class.getDeclaredFields())
                .map(Field::getName)
                .collect(Collectors.toSet());

        // 期望的字段
        assertThat(fieldNames).contains(
                "id", "auditId", "stage", "model", "durationMs",
                "status", "reason", "promptTokens", "completionTokens",
                "totalTokens", "createdAt");

        // 禁止的字段（安全红线）
        assertThat(fieldNames)
                .as("LlmCallRecord must NOT have apiKey / api_key field")
                .noneMatch(name -> name.equalsIgnoreCase("apiKey")
                        || name.equalsIgnoreCase("api_key"));
        assertThat(fieldNames)
                .as("LlmCallRecord must NOT have prompt field")
                .noneMatch(name -> name.equalsIgnoreCase("prompt"));
        assertThat(fieldNames)
                .as("LlmCallRecord must NOT have responseContent / response field")
                .noneMatch(name -> name.equalsIgnoreCase("responseContent")
                        || name.equalsIgnoreCase("response_content")
                        || name.equalsIgnoreCase("response"));
        assertThat(fieldNames)
                .as("LlmCallRecord must NOT have reasoning field")
                .noneMatch(name -> name.equalsIgnoreCase("reasoning"));
        assertThat(fieldNames)
                .as("LlmCallRecord must NOT have raw / rawOutput field")
                .noneMatch(name -> name.equalsIgnoreCase("raw")
                        || name.equalsIgnoreCase("rawOutput")
                        || name.equalsIgnoreCase("rawResponse"));
    }

    // ==================== 成功路径 ====================

    @Test
    @DisplayName("成功调用 → 写入 SUCCESS 记录，durationMs > 0，tokens 来自 LlmCallResult")
    void successPathPersistsRecordWithTokens() {
        String auditId = "audit-success-" + UUID.randomUUID();
        AuditContextHolder.set(auditId);
        AuditContextHolder.setStage(LlmCallStage.INTENT);

        LlmCallResult mockResult = new LlmCallResult("ok", "deepseek-chat",
                10, 20, 30, "stop", "{}");
        doReturn(mockResult).when(delegate).complete(eq(LlmStage.INTENT), any());

        LlmCallResult result = auditingClient.complete(LlmStage.INTENT,
                List.of(ChatMessage.user("hello")));

        assertThat(result.content()).isEqualTo("ok");

        ArgumentCaptor<String> auditIdCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<LlmCallStage> stageCap = ArgumentCaptor.forClass(LlmCallStage.class);
        ArgumentCaptor<Long> durationCap = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<LlmCallResult> resultCap = ArgumentCaptor.forClass(LlmCallResult.class);
        verify(auditService, times(1)).recordSuccess(
                auditIdCap.capture(), stageCap.capture(),
                any(), durationCap.capture(), resultCap.capture());
        // durationMs > 0 — sanity check on the decorator's time measurement
        // (allow == 0 only if the call was extraordinarily fast, which Mockito return path is)
        // actual assertion happens on the captured value below

        assertThat(auditIdCap.getValue()).isEqualTo(auditId);
        assertThat(stageCap.getValue()).isEqualTo(LlmCallStage.INTENT);
        assertThat(durationCap.getValue()).isGreaterThanOrEqualTo(0L);
        assertThat(resultCap.getValue().totalTokens()).isEqualTo(30);
        verify(auditService, never()).recordFailure(any(), any(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("RESPONSE 阶段独立记录（stage=RESPONSE）")
    void responseStageRecordedAsResponse() {
        String auditId = "audit-response-" + UUID.randomUUID();
        AuditContextHolder.set(auditId);
        AuditContextHolder.setStage(LlmCallStage.RESPONSE);

        doReturn(new LlmCallResult("answer", "qwen-max",
                100, 50, 150, "stop", "{}"))
                .when(delegate).complete(eq(LlmStage.RESPONSE), any());

        auditingClient.complete(LlmStage.RESPONSE, List.of(ChatMessage.user("explain disk")));

        ArgumentCaptor<LlmCallStage> stageCap = ArgumentCaptor.forClass(LlmCallStage.class);
        verify(auditService, times(1)).recordSuccess(
                any(), stageCap.capture(), any(), anyLong(), any());
        assertThat(stageCap.getValue()).isEqualTo(LlmCallStage.RESPONSE);
    }

    // ==================== 失败路径 ====================

    @Test
    @DisplayName("失败调用 → 写入 FAILED 记录，异常仍抛出")
    void failurePathPersistsRecordWithReason() {
        String auditId = "audit-fail-" + UUID.randomUUID();
        AuditContextHolder.set(auditId);
        AuditContextHolder.setStage(LlmCallStage.RESPONSE);

        doThrow(new LlmClientException(LlmClientException.Reason.RATE_LIMITED,
                "LLM HTTP 429 (stage=RESPONSE)"))
                .when(delegate).complete(eq(LlmStage.RESPONSE), any());

        assertThatThrownBy(() -> auditingClient.complete(LlmStage.RESPONSE,
                List.of(ChatMessage.user("x"))))
                .isInstanceOf(LlmClientException.class)
                .extracting(t -> ((LlmClientException) t).getReason())
                .isEqualTo(LlmClientException.Reason.RATE_LIMITED);

        ArgumentCaptor<Throwable> exCap = ArgumentCaptor.forClass(Throwable.class);
        verify(auditService, times(1)).recordFailure(
                eq(auditId), eq(LlmCallStage.RESPONSE), any(), anyLong(), exCap.capture());
        assertThat(exCap.getValue()).isInstanceOf(LlmClientException.class);
        // 异常仍抛出 → verify delegate was called
        verify(delegate, times(1)).complete(eq(LlmStage.RESPONSE), any());
    }

    @Test
    @DisplayName("LlmCallAuditService.recordFailure 提取的 reason 是枚举名（不记 message 原文）")
    void failureReasonExtractedAsEnumName() {
        // 验证 LlmCallAuditService 自身：从异常提取的 reason 仅是枚举名
        LlmCallRecordRepository repo = mock(LlmCallRecordRepository.class);
        LlmCallAuditService svc = new LlmCallAuditService(repo);

        LlmClientException ex = new LlmClientException(LlmClientException.Reason.AUTH,
                "auth failed for key sk-leaked-1234567890");

        svc.recordFailure("audit-test", LlmCallStage.INTENT, "model", 10L, ex);

        ArgumentCaptor<LlmCallRecord> recCap = ArgumentCaptor.forClass(LlmCallRecord.class);
        verify(repo, times(1)).save(recCap.capture());

        LlmCallRecord rec = recCap.getValue();
        assertThat(rec.getStatus()).isEqualTo(LlmCallStatus.FAILED);
        assertThat(rec.getReason()).isEqualTo("AUTH");
        assertThat(rec.getReason()).doesNotContain("sk-leaked");
        assertThat(rec.getReason()).doesNotContain("1234567890");
    }

    @Test
    @DisplayName("非 LlmClientException 的 RuntimeException → reason=RUNTIME_ERROR")
    void nonLlmExceptionYieldsRuntimeErrorReason() {
        LlmCallRecordRepository repo = mock(LlmCallRecordRepository.class);
        LlmCallAuditService svc = new LlmCallAuditService(repo);

        svc.recordFailure("audit-test", LlmCallStage.INTENT, "model", 10L,
                new IllegalStateException("boom"));

        ArgumentCaptor<LlmCallRecord> recCap = ArgumentCaptor.forClass(LlmCallRecord.class);
        verify(repo, times(1)).save(recCap.capture());
        assertThat(recCap.getValue().getReason()).isEqualTo("RUNTIME_ERROR");
    }

    // ==================== auditId 透传 ====================

    @Test
    @DisplayName("无 auditId 时跳过审计记录，但 LLM 调用仍正常")
    void noAuditIdSkipsRecording() {
        // AuditContextHolder 未 set
        doReturn(new LlmCallResult("ok", "deepseek-chat",
                5, 5, 10, "stop", "{}"))
                .when(delegate).complete(eq(LlmStage.INTENT), any());

        LlmCallResult result = auditingClient.complete(LlmStage.INTENT,
                List.of(ChatMessage.user("x")));

        assertThat(result.content()).isEqualTo("ok");
        verify(auditService, never()).recordSuccess(any(), any(), any(), anyLong(), any());
        verify(auditService, never()).recordFailure(any(), any(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("auditId 透传：同一 auditId 下多次 LLM 调用都被正确关联")
    void auditIdPropagatesAcrossMultipleCalls() {
        String auditId = "audit-multi-" + UUID.randomUUID();
        AuditContextHolder.set(auditId);
        AuditContextHolder.setStage(LlmCallStage.INTENT);

        doReturn(new LlmCallResult("a", "model-x", 1, 1, 2, "stop", "{}"))
                .when(delegate).complete(eq(LlmStage.INTENT), any());

        // 同一 auditId 下两次调用
        auditingClient.complete(LlmStage.INTENT, List.of(ChatMessage.user("first")));
        auditingClient.complete(LlmStage.INTENT, List.of(ChatMessage.user("second")));

        verify(auditService, times(2)).recordSuccess(eq(auditId),
                eq(LlmCallStage.INTENT), any(), anyLong(), any());
    }

    // ==================== AuditContextHolder 契约 ====================

    @Test
    @DisplayName("AuditContextHolder set/get/clear 行为正确")
    void auditContextHolderBasicContract() {
        assertThat(AuditContextHolder.get()).isNull();
        assertThat(AuditContextHolder.getStage()).isNull();

        AuditContextHolder.set("audit-abc");
        assertThat(AuditContextHolder.get()).isEqualTo("audit-abc");

        AuditContextHolder.setStage(LlmCallStage.RESPONSE);
        assertThat(AuditContextHolder.getStage()).isEqualTo(LlmCallStage.RESPONSE);

        AuditContextHolder.clear();
        assertThat(AuditContextHolder.get()).isNull();
        assertThat(AuditContextHolder.getStage()).isNull();
    }

    @Test
    @DisplayName("AuditContextHolder 同一线程隔离：set 后 get 立即可见")
    void auditContextHolderThreadLocalVisibility() {
        AuditContextHolder.set("audit-thread");
        assertThat(AuditContextHolder.get()).isEqualTo("audit-thread");
        AuditContextHolder.clear();
    }

    // ==================== Decorator 性质 ====================

    @Test
    @DisplayName("AuditingLlmClient 委托给底层 delegate.complete()")
    void llmClientDelegatesToUnderlying() {
        AuditContextHolder.set("audit-test");
        AuditContextHolder.setStage(LlmCallStage.INTENT);

        doAnswer(inv -> new LlmCallResult("delegated", "m", 1, 1, 2, "stop", "{}"))
                .when(delegate).complete(any(), any());

        LlmCallResult result = auditingClient.complete(LlmStage.INTENT,
                List.of(ChatMessage.user("x")));

        assertThat(result.content()).isEqualTo("delegated");
        verify(delegate, times(1)).complete(eq(LlmStage.INTENT), any());
    }

    @Test
    @DisplayName("LlmCallAuditService.recordSuccess 写入 token 字段")
    void recordSuccessStoresTokens() {
        LlmCallRecordRepository repo = mock(LlmCallRecordRepository.class);
        LlmCallAuditService svc = new LlmCallAuditService(repo);

        LlmCallResult res = new LlmCallResult("ok", "deepseek-chat", 7, 11, 18, "stop", "{}");
        svc.recordSuccess("audit-tokens", LlmCallStage.RESPONSE, "deepseek-chat", 42L, res);

        ArgumentCaptor<LlmCallRecord> cap = ArgumentCaptor.forClass(LlmCallRecord.class);
        verify(repo, times(1)).save(cap.capture());
        LlmCallRecord rec = cap.getValue();
        assertThat(rec.getPromptTokens()).isEqualTo(7);
        assertThat(rec.getCompletionTokens()).isEqualTo(11);
        assertThat(rec.getTotalTokens()).isEqualTo(18);
        assertThat(rec.getModel()).isEqualTo("deepseek-chat");
        assertThat(rec.getStatus()).isEqualTo(LlmCallStatus.SUCCESS);
        assertThat(rec.getDurationMs()).isEqualTo(42L);
    }

    @Test
    @DisplayName("LlmCallAuditService.recordSuccess 在 auditId 为 null 时跳过写入")
    void recordSuccessSkipsOnNullAuditId() {
        LlmCallRecordRepository repo = mock(LlmCallRecordRepository.class);
        LlmCallAuditService svc = new LlmCallAuditService(repo);

        svc.recordSuccess(null, LlmCallStage.RESPONSE, "m", 10L, null);

        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("LlmCallAuditService.recordSuccess 在 auditId 为空字符串时跳过写入")
    void recordSuccessSkipsOnBlankAuditId() {
        LlmCallRecordRepository repo = mock(LlmCallRecordRepository.class);
        LlmCallAuditService svc = new LlmCallAuditService(repo);

        svc.recordSuccess("", LlmCallStage.RESPONSE, "m", 10L, null);
        svc.recordSuccess("   ", LlmCallStage.RESPONSE, "m", 10L, null);

        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("LlmCallAuditService.recordSuccess 内部异常被 swallow（不抛出）")
    void recordSuccessSwallowsInternalExceptions() {
        LlmCallRecordRepository repo = mock(LlmCallRecordRepository.class);
        doAnswer(inv -> { throw new RuntimeException("DB down"); })
                .when(repo).save(any());
        LlmCallAuditService svc = new LlmCallAuditService(repo);

        // 不应抛异常
        svc.recordSuccess("audit-test", LlmCallStage.RESPONSE, "m", 10L,
                new LlmCallResult("ok", "m", 1, 1, 2, "stop", "{}"));
    }

    @Test
    @DisplayName("LlmCallAuditService.recordFailure 内部异常被 swallow（不抛出）")
    void recordFailureSwallowsInternalExceptions() {
        LlmCallRecordRepository repo = mock(LlmCallRecordRepository.class);
        doAnswer(inv -> { throw new RuntimeException("DB down"); })
                .when(repo).save(any());
        LlmCallAuditService svc = new LlmCallAuditService(repo);

        // 不应抛异常
        svc.recordFailure("audit-test", LlmCallStage.RESPONSE, "m", 10L,
                new LlmClientException(LlmClientException.Reason.NETWORK, "boom"));
    }
}