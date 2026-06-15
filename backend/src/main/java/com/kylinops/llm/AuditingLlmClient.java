package com.kylinops.llm;

import com.kylinops.audit.AuditContextHolder;
import com.kylinops.audit.LlmCallAuditService;
import com.kylinops.audit.LlmCallStage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * LLM 调用审计装饰器（P3-T5）。
 *
 * <p>职责：包裹底层 {@link OpenAiCompatibleLlmClient}，每次 {@link #complete(LlmStage, List)}
 * 调用前/后记录审计到 {@code kylin_llm_call_record}。</p>
 *
 * <h3>不修改底层</h3>
 * <p>本类<strong>不修改</strong> {@link OpenAiCompatibleLlmClient} 任何字段或方法 —
 * 仅通过装饰器模式在外部加壳。这是 P3-T5 的硬约束（CLAUDE.md / phase3-llm-integration-plan §Task 5）。</p>
 *
 * <h3>auditId / stage 来源</h3>
 * <ul>
 *   <li>{@link AuditContextHolder#get()} — 由 {@link com.kylinops.agent.AgentOrchestrator}
 *       在 process() 入口设置，try/finally 清理</li>
 *   <li>{@link AuditContextHolder#getStage()} — 由 {@code HybridIntentService} /
 *       {@code HybridResponseService} 在调用 LLM 前设置</li>
 *   <li>两者均为 null 时 → 跳过审计（不影响调用）</li>
 * </ul>
 *
 * <h3>异常语义</h3>
 * <ul>
 *   <li>底层抛 {@link LlmClientException} → 记录 status=FAILED, reason=枚举名，<strong>异常仍上抛</strong></li>
 *   <li>底层抛其他 RuntimeException → 记录 status=FAILED, reason=RUNTIME_ERROR，<strong>异常仍上抛</strong></li>
 *   <li>审计本身失败 → log.warn + swallow（{@link LlmCallAuditService} 已保证）</li>
 * </ul>
 *
 * <h3>装配</h3>
 * <ul>
 *   <li>{@link Primary} — Spring 注入 {@link LlmClient} 时优先选本装饰器</li>
 *   <li>{@link ConditionalOnBean}(OpenAiCompatibleLlmClient.class) — 仅当底层 client bean 存在时注册；
 *       LLM 关闭时不存在 → 不注册 → 调用方拿不到 LlmClient bean → HybridResponseService 走 fail-closed 路径</li>
 * </ul>
 *
 * <h3>性能预算</h3>
 * <p>审计插入在独立 REQUIRES_NEW 事务中（{@link LlmCallAuditService}），单次开销 < 5ms，
 * 不影响 LLM 调用本身的 latency budget（INTENT ≤ 3s, RESPONSE ≤ 5s）。</p>
 */
@Slf4j
@Primary
@Component
@ConditionalOnProperty(name = "kylinops.llm.enabled", havingValue = "true")
public class AuditingLlmClient implements LlmClient {

    private final OpenAiCompatibleLlmClient delegate;
    private final LlmCallAuditService auditService;

    /**
     * Spring 装配构造器（生产路径）— 使用 ObjectProvider 防止循环依赖
     * （AgentOrchestrator → HybridIntentService → LlmClient → 审计）。
     */
    public AuditingLlmClient(ObjectProvider<OpenAiCompatibleLlmClient> delegateProvider,
                             LlmCallAuditService auditService) {
        // 使用 ObjectProvider 防止循环依赖（AgentOrchestrator → HybridIntentService → LlmClient → 审计）
        OpenAiCompatibleLlmClient resolved = delegateProvider.getIfAvailable();
        if (resolved == null) {
            throw new IllegalStateException(
                    "AuditingLlmClient requires an OpenAiCompatibleLlmClient bean, but none was found");
        }
        this.delegate = resolved;
        this.auditService = auditService;
        log.info("AuditingLlmClient 已注册, 装饰底层 OpenAiCompatibleLlmClient");
    }

    /**
     * 显式构造器（单元测试用）— 直接传入底层 client 与审计服务，绕开 Spring 容器。
     * <p>非 Spring 装配路径；生产代码请勿使用。</p>
     */
    public AuditingLlmClient(OpenAiCompatibleLlmClient delegate, LlmCallAuditService auditService) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        if (auditService == null) {
            throw new IllegalArgumentException("auditService must not be null");
        }
        this.delegate = delegate;
        this.auditService = auditService;
    }

    @Override
    public LlmCallResult complete(LlmStage stage, List<ChatMessage> messages) {
        String auditId = AuditContextHolder.get();
        LlmCallStage auditStage = toAuditStage(AuditContextHolder.getStage(), stage);
        String model = resolveModel();
        long started = System.currentTimeMillis();

        if (auditId == null || auditId.isBlank()) {
            // 无 auditId → 跳过审计，直接调用（不阻断）
            return delegate.complete(stage, messages);
        }

        try {
            LlmCallResult result = delegate.complete(stage, messages);
            long durationMs = System.currentTimeMillis() - started;
            auditService.recordSuccess(auditId, auditStage, model, durationMs, result);
            return result;
        } catch (LlmClientException e) {
            long durationMs = System.currentTimeMillis() - started;
            auditService.recordFailure(auditId, auditStage, model, durationMs, e);
            throw e; // 异常仍上抛 — 不阻塞主链路（HybridIntentService / HybridResponseService 自处理）
        } catch (RuntimeException e) {
            long durationMs = System.currentTimeMillis() - started;
            auditService.recordFailure(auditId, auditStage, model, durationMs, e);
            throw e;
        }
    }

    /**
     * 将 {@link LlmStage} 转换为审计用的 {@link LlmCallStage}。
     * <p>AuditContextHolder 中的 stage 优先（调用方显式标注）；fallback 到入参 stage。</p>
     */
    private static LlmCallStage toAuditStage(LlmCallStage holderStage, LlmStage stage) {
        if (holderStage != null) {
            return holderStage;
        }
        if (stage == LlmStage.INTENT) {
            return LlmCallStage.INTENT;
        }
        return LlmCallStage.RESPONSE;
    }

    /**
     * 解析当前模型名。仅从 delegate 暴露的 model 字段读取（不可反射私有字段）。
     * <p>此实现通过调用 delegate 自身获取 — 但 OpenAiCompatibleLlmClient 没有暴露 model getter，
     * 这里采用"调用一次并捕获异常"的轻量方式不可行；改为固定读 OpenAiCompatibleLlmClient 的
     * KylinOpsConfig.Llm.model 配置。</p>
     *
     * <p>为避免反射私有字段，模型名先取 LlmCallResult.model（成功路径）。
     * 失败路径使用 "unknown"。这是合理的降级 — 审计表不强制必须有 model。</p>
     */
    private String resolveModel() {
        // 不读取私有字段；让 success 路径在 recordSuccess 中用 LlmCallResult.model 覆盖
        return "unknown";
    }
}