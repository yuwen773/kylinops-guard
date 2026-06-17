package com.kylinops.agent;

import com.kylinops.agent.ToolPlanningService.ExecutionMode;
import com.kylinops.agent.ToolPlanningService.ToolPlan;
import com.kylinops.agent.ToolPlanningService.ToolStep;
import com.kylinops.agent.intelligence.HybridIntentService;
import com.kylinops.agent.intelligence.HybridResponseService;
import com.kylinops.agent.intelligence.IntentResolution;
import com.kylinops.audit.AuditContextHolder;
import com.kylinops.audit.AuditLogService;
import com.kylinops.chat.Message;
import com.kylinops.chat.MessageRepository;
import com.kylinops.chat.Session;
import com.kylinops.chat.SessionRepository;
import com.kylinops.common.enums.AuditStatus;
import com.kylinops.common.enums.IntentType;
import com.kylinops.common.enums.RiskDecision;
import com.kylinops.common.enums.RiskLevel;
import com.kylinops.executor.ActionConfirmService;
import com.kylinops.executor.AuthenticatedOperator;
import com.kylinops.executor.PendingAction;
import com.kylinops.executor.PendingActionStatus;
import com.kylinops.notification.NotificationEventFactory;
import com.kylinops.notification.NotificationService;
import com.kylinops.rca.RootCauseAnalyzer;
import com.kylinops.rca.RootCauseChain;
import com.kylinops.security.PromptInjectionDetector;
import com.kylinops.security.RiskCheckResult;
import com.kylinops.security.RiskCheckService;
import com.kylinops.tool.ToolExecutor;
import com.kylinops.tool.ToolNotRegisteredException;
import com.kylinops.tool.ToolResult;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Agent 主编排器
 * <p>
 * 控制从自然语言输入到运维动作再到响应回复的完整闭环流程。
 * 编排顺序严格执行安全策略：
 * </p>
 *
 * <ol>
 *   <li>创建审计日志（先于一切，保证每请求有记录）</li>
 *   <li>Prompt 注入检测</li>
 *   <li>意图识别（规则优先）</li>
 *   <li>工具调用规划（意图 → 工具链）</li>
 *   <li>风险校验（执行前，决定 ALLOW / CONFIRM / BLOCK）</li>
 *   <li>ALLOW → 执行工具计划（并行 / 串行）</li>
 *   <li>CONFIRM → 生成 PendingAction，不执行工具</li>
 *   <li>BLOCK → 直接返回，不执行任何工具</li>
 *   <li>生成回复（基于工具结果）</li>
 *   <li>持久化消息和审计日志</li>
 * </ol>
 *
 * <h3>安全约束</h3>
 * <ul>
 *   <li>风险校验必须在工具执行之前（L2 不执行，L3/L4 不执行）</li>
 *   <li>Prompt 注入检测在意图识别之前</li>
 *   <li>审计日志在注入检测之前创建</li>
 *   <li>回复必须基于工具结果，不能编造</li>
 *   <li>未注册工具直接阻断，不执行任何操作</li>
 *   <li>每次请求必须写入审计日志</li>
 * </ul>
 */
@Slf4j
@Component
public class AgentOrchestrator {

    private final PromptInjectionDetector injectionDetector;
    private final IntentClassifier intentClassifier;
    private final HybridIntentService hybridIntentService;
    private final ToolPlanningService toolPlanningService;
    private final ToolExecutor toolExecutor;
    private final RiskCheckService riskCheckService;
    private final AgentResponseBuilder responseBuilder;
    private final HybridResponseService hybridResponseService;
    private final AuditLogService auditLogService;
    private final ActionConfirmService actionConfirmService;
    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;
    private final RootCauseAnalyzer rootCauseAnalyzer;
    // P1-01 Plan 01: 3 emit points. 旧 14 字段保持 final,新增 2 字段在老构造器中初始化为 null
    // (保留旧测试的 14 参构造器调用兼容),新 16 参构造器委托老构造器后再赋值(Fix-03 模式)。
    private final NotificationService notificationService;
    private final NotificationEventFactory notificationEventFactory;

    /**
     * 老构造器(14 参)— 保留向后兼容(测试用),新 2 字段在通知关闭的路径上不调用。
     * 委托给本构造器的 16 参版本最后会覆盖这 2 个字段;此处设为 null 是为了让 final
     * 字段在每个构造器路径上都已被赋值(javac 要求)。
     */
    public AgentOrchestrator(
            PromptInjectionDetector injectionDetector,
            IntentClassifier intentClassifier,
            HybridIntentService hybridIntentService,
            ToolPlanningService toolPlanningService,
            ToolExecutor toolExecutor,
            RiskCheckService riskCheckService,
            AgentResponseBuilder responseBuilder,
            HybridResponseService hybridResponseService,
            AuditLogService auditLogService,
            ActionConfirmService actionConfirmService,
            SessionRepository sessionRepository,
            MessageRepository messageRepository,
            RootCauseAnalyzer rootCauseAnalyzer) {
        this(injectionDetector, intentClassifier, hybridIntentService, toolPlanningService,
                toolExecutor, riskCheckService, responseBuilder, hybridResponseService,
                auditLogService, actionConfirmService, sessionRepository, messageRepository,
                rootCauseAnalyzer, null, null);
    }

    /**
     * 新构造器(16 参)— Spring DI 优先选这个(参数最多)。委托老构造器初始化 14 老字段,
     * 再单独赋值 2 个新字段 — 老字段保持 final,新字段也保持 final(Fix-03 模式)。
     *
     * <p><b>@Autowired 必加</b>:类中存在两个构造器(14 参 + 16 参),Spring 不会自动选,
     * 必须显式标记 16 参为主构造器。老构造器仅供直接 new 调用(测试用例)。</p>
     */
    @org.springframework.beans.factory.annotation.Autowired
    public AgentOrchestrator(
            PromptInjectionDetector injectionDetector,
            IntentClassifier intentClassifier,
            HybridIntentService hybridIntentService,
            ToolPlanningService toolPlanningService,
            ToolExecutor toolExecutor,
            RiskCheckService riskCheckService,
            AgentResponseBuilder responseBuilder,
            HybridResponseService hybridResponseService,
            AuditLogService auditLogService,
            ActionConfirmService actionConfirmService,
            SessionRepository sessionRepository,
            MessageRepository messageRepository,
            RootCauseAnalyzer rootCauseAnalyzer,
            NotificationService notificationService,
            NotificationEventFactory notificationEventFactory) {
        this.injectionDetector = injectionDetector;
        this.intentClassifier = intentClassifier;
        this.hybridIntentService = hybridIntentService;
        this.toolPlanningService = toolPlanningService;
        this.toolExecutor = toolExecutor;
        this.riskCheckService = riskCheckService;
        this.responseBuilder = responseBuilder;
        this.hybridResponseService = hybridResponseService;
        this.auditLogService = auditLogService;
        this.actionConfirmService = actionConfirmService;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.rootCauseAnalyzer = rootCauseAnalyzer;
        this.notificationService = notificationService;
        this.notificationEventFactory = notificationEventFactory;
    }

    /**
     * 并行工具执行线程池。
     * <p>
     * 设计要点：
     * <ul>
     *   <li>corePool=4：日常系统健康检查（6 工具并行）足以覆盖；空闲线程 60s 后回收</li>
     *   <li>maxPool=16：覆盖未来 8-12 工具扩展 + 并发 chat 请求突发</li>
     *   <li>ArrayBlockingQueue(64)：有界缓冲，避免 OOM；容量 = 8 工具 × 8 并发请求</li>
     *   <li>CallerRunsPolicy：队列满时由提交线程同步执行，保证任务不丢、不抛 RejectedExecutionException</li>
     * </ul>
     */
    private final ExecutorService parallelExecutor = new ThreadPoolExecutor(
            4, 16, 60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64),
            r -> {
                Thread t = new Thread(r, "agent-parallel-");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy());

    // ==================== 主流程 ====================

    /**
     * 处理 Agent 请求的主流程。
     *
     * @param request Agent 请求
     * @return Agent 执行结果
     */
    public AgentResult process(AgentRequest request) {
        String auditId = request.getRequestId() != null
                ? request.getRequestId()
                : UUID.randomUUID().toString();

        log.info("===== Agent 请求开始 =====");
        log.info("sessionId={}, auditId={}, input='{}'",
                request.getSessionId(), auditId, truncate(request.getUserInput(), 100));

        // P3-T5: 在 ThreadLocal 中发布 auditId，供 AuditingLlmClient 读取
        // try/finally 保证清理，防止 ThreadLocal 泄漏到线程池下一个请求
        AuditContextHolder.set(auditId);
        try {
            // ── Step 0: 查找或创建 Session ──
            Session session = findOrCreateSession(request.getSessionId());

            // ── Step 1: 创建用户消息 ──
            Message userMessage = createUserMessage(session, request.getUserInput(), auditId);

            // ── Step 2: 创建审计日志（先于一切，保证每请求有记录） ──
            // 审计创建失败 → 不进 tool chain → 直接返回 BLOCK（fail-closed）
            try {
                auditLogService.createAuditLog(auditId, session.getSessionId(),
                        request.getUserInput(), IntentType.UNKNOWN, AuditStatus.RECEIVED);
            } catch (Exception e) {
                log.error("审计日志创建失败，阻断请求: auditId={}", auditId, e);
                return AgentResult.builder()
                        .sessionId(session.getSessionId())
                        .answer("审计日志创建失败，请求被阻断。")
                        .intentType(IntentType.UNKNOWN)
                        .toolCalls(List.of())
                        .riskLevel(RiskLevel.L4)
                        .riskDecision("BLOCK")
                        .auditId(auditId)
                        .errorMessage("audit creation failed: " + e.getMessage())
                        .build();
            }

            // ── Step 3: Prompt 注入检测 ──
            PromptInjectionDetector.DetectionResult injection =
                    injectionDetector.detect(request.getUserInput());
            if (injection.isInjectionDetected()) {
                return handleInjectionBlock(request, session, auditId, injection);
            }

            // ── Step 4: 意图识别（HybridIntentService: 规则优先 + LLM 后备） ──
            // P3-T2 接管意图识别入口；IntentClassifier 仍由 HybridIntentService 内部调用
            // （保留 IntentClassifier 注入以维持向后兼容与单元测试可用性）。
            IntentResolution intentResolution = hybridIntentService.resolve(request.getUserInput());
            IntentType intent = intentResolution.getIntentType();
            log.info("意图识别: source={}, intent={}, confidence={}, input='{}'",
                    intentResolution.getSource(), intent, intentResolution.getConfidence(),
                    truncate(request.getUserInput(), 40));

            // 更新审计日志的意图类型
            auditLogService.updateAuditLog(auditId, null, null,
                    null, null, null, intent);

            // ── Step 5: 工具规划 ──
            // params 优先取 LLM 提取（allowlist 已过滤），未提供时回退规则正则（PID/serviceName/operation）
            Map<String, Object> params = mergeParams(
                    intentResolution.getParams(),
                    extractParams(request.getUserInput(), intent));
            ToolPlan plan = toolPlanningService.createPlan(intent, params);
            log.info("工具计划: steps={}", plan.getSteps().size());

            // ── Step 6: 前置风险校验（先于工具执行） ──
            RiskCheckResult riskResult = riskCheckService.checkPlan(plan, request.getUserInput(), auditId);
            RiskLevel riskLevel = riskResult.getRiskLevel();
            RiskDecision decision = riskResult.getDecision();

            log.info("前置风险校验: level={}, decision={}", riskLevel, decision);

            // 更新审计日志的风险信息
            AuditStatus auditStatus = switch (decision) {
                case ALLOW -> AuditStatus.RISK_CHECKED;
                case CONFIRM -> AuditStatus.CONFIRM_PENDING;
                case BLOCK -> AuditStatus.BLOCKED;
            };
            auditLogService.updateAuditLog(auditId, riskLevel, decision,
                    null, auditStatus, riskResult.getReason(), intent);

            // ── Step 7: 根据决策分支执行 ──
            List<ToolResult> toolResults = List.of();
            boolean needConfirmation = false;
            AgentResult.PendingAction pendingAction = null;

            switch (decision) {
                case ALLOW -> {
                    // 执行工具计划
                    toolResults = executeToolPlan(plan, auditId);
                    AuditStatus executionStatus = toolResults.stream().allMatch(ToolResult::isSuccess)
                            ? AuditStatus.SUCCESS : AuditStatus.FAILED;
                    auditLogService.updateAuditLog(auditId, null, null,
                            null, executionStatus, null, intent);
                }
                case CONFIRM -> {
                    // L2: 持久化 PendingAction，不执行工具
                    needConfirmation = true;
                    if (plan.getAction() == null) {
                        throw new IllegalStateException("CONFIRM decision requires an action plan");
                    }
                    PendingAction pa = actionConfirmService.createAction(
                            auditId,
                            session.getSessionId(),
                            request.getOperator() != null ? request.getOperator() : AuthenticatedOperator.ANONYMOUS,
                            plan.getAction().getActionType(),
                            plan.getAction().getTarget(),
                            plan.getAction().getParams(),
                            riskLevel);
                    auditLogService.updateAuditDetails(
                            auditId, String.valueOf(riskResult.getMatchedRules()),
                            String.valueOf(plan.getAction()));
                    auditLogService.updateAuditConfirmation(
                            auditId, true, PendingActionStatus.WAITING.name());
                    pendingAction = AgentResult.PendingAction.builder()
                            .actionId(pa.getActionId())
                            .toolName(pa.getToolName())
                            .params(Map.of())
                            .description("待确认操作: " + pa.getActionType() + " - " + pa.getToolName())
                            .build();
                    // P1-01: 通知中心 emit(L2 待确认事件)
                    emitNotification(() -> notificationEventFactory.l2ConfirmRequired(
                            auditId, session.getSessionId(), intent, riskLevel,
                            pa.getActionId(), plan.getAction().getActionType()));
                    log.info("已创建待确认动作: actionId={}", pa.getActionId());
                }
                case BLOCK -> {
                    // L3/L4: 不执行任何工具
                    log.warn("请求被阻断: level={}, reason={}", riskLevel, riskResult.getReason());
                    // P1-01: 仅 L4 触发通知(规则命中的绝对阻断);L3 不发通知(由 RCA/Service 异常走专用通道)
                    if (riskLevel == RiskLevel.L4) {
                        emitNotification(() -> notificationEventFactory.l4Block(
                                auditId, session.getSessionId(), riskLevel, decision,
                                riskResult.getMatchedRules() != null && !riskResult.getMatchedRules().isEmpty()
                                        ? riskResult.getMatchedRules().get(0) : null,
                                riskResult.getReason()));
                    }
                }
            }

            // ── Step 7.5: 生成根因分析链（演示场景 1/2/3 重点） ──
            RootCauseChain rootCauseChain = null;
            if (decision == RiskDecision.ALLOW && !toolResults.isEmpty()) {
                rootCauseChain = rootCauseAnalyzer.analyze(intent, toolResults, decision);
                if (rootCauseChain != null) {
                    log.info("生成根因分析链: symptom={}, confidence={}",
                            rootCauseChain.getSymptom(), rootCauseChain.getConfidence());
                }
            }

            // ── Step 8: 生成回复 ──
            // P3-T4: HybridResponseService 接管生成回复
            // - BLOCK/CONFIRM/GENERAL_CHAT/UNKNOWN/空 results → 立即走 AgentResponseBuilder (fail-closed)
            // - ALLOW + 非空 results → 尝试 LLM 增强（校验失败回退模板）
            String answer = isDiscussionContext(request.getUserInput())
                    ? buildDiscussionAnswer(request.getUserInput())
                    : hybridResponseService.build(intent, toolResults, decision,
                            riskResult.getReason(), riskLevel);

            // ── Step 9: 创建助手消息 ──
            createAssistantMessage(session, answer, intent, auditId);

            // ── Step 10: 最终审计状态 ──
            if (decision == RiskDecision.BLOCK) {
                auditLogService.markBlocked(auditId);
            } else if (decision == RiskDecision.ALLOW
                    && !toolResults.isEmpty()
                    && toolResults.stream().allMatch(ToolResult::isSuccess)) {
                auditLogService.markCompleted(auditId);
            }

            log.info("Agent 请求处理完成: intent={}, decision={}, tools={}",
                    intent, decision, toolResults.size());
            log.info("===== Agent 请求结束 =====");

            return AgentResult.builder()
                    .sessionId(session.getSessionId())
                    .answer(answer)
                    .intentType(intent)
                    .toolCalls(buildToolCallInfo(toolResults))
                    .riskLevel(riskLevel)
                    .riskDecision(decision.name())
                    .needConfirmation(needConfirmation)
                    .pendingAction(pendingAction)
                    .auditId(auditId)
                    .rootCauseChain(rootCauseChain)
                    .build();

        } catch (Exception e) {
            log.error("Agent 请求处理异常: sessionId={}, auditId={}", request.getSessionId(), auditId, e);

            try {
                auditLogService.updateAuditLog(auditId, RiskLevel.L3, RiskDecision.BLOCK,
                        null, AuditStatus.FAILED, "系统内部错误: " + e.getMessage(), IntentType.UNKNOWN);
            } catch (Exception auditException) {
                log.error("Agent 异常审计更新失败: auditId={}", auditId, auditException);
            }

            return AgentResult.builder()
                    .sessionId(request.getSessionId())
                    .answer("抱歉，系统处理您的请求时出现内部错误，请稍后重试。")
                    .intentType(IntentType.UNKNOWN)
                    .toolCalls(List.of())
                    .riskLevel(RiskLevel.L3)
                    .riskDecision("BLOCK")
                    .needConfirmation(false)
                    .auditId(auditId)
                    .errorMessage(e.getMessage())
                    .build();
        } finally {
            // P3-T5: 清理 ThreadLocal 防止泄漏到下一个请求
            AuditContextHolder.clear();
        }
    }

    // ==================== 阻断处理 ====================

    /**
     * 处理 Prompt 注入阻断场景。
     */
    private AgentResult handleInjectionBlock(AgentRequest request, Session session,
                                              String auditId,
                                              PromptInjectionDetector.DetectionResult injection) {
        log.warn("Prompt 注入阻断: patterns={}, level={}",
                injection.getMatchedPatterns(), injection.getRiskLevel());

        // P1-01: 通知中心 emit(注入拦截事件,工厂方法统一构造)
        emitNotification(() -> notificationEventFactory.promptInjectionBlock(
                auditId, session.getSessionId(),
                String.join(",", injection.getMatchedPatterns()),
                injection.getReason()));

        // 更新已有审计日志（BLOCKED）
        auditLogService.updateAuditLog(auditId, injection.getRiskLevel(), RiskDecision.BLOCK,
                null, AuditStatus.BLOCKED, injection.getReason(), IntentType.COMMAND_EXECUTION);

        String answer = responseBuilder.build(IntentType.COMMAND_EXECUTION, List.of(),
                RiskDecision.BLOCK, injection.getReason(), injection.getRiskLevel());

        createAssistantMessage(session, answer, IntentType.COMMAND_EXECUTION, auditId);

        log.info("===== Agent 请求结束 (INJECTION BLOCKED) =====");

        return AgentResult.builder()
                .sessionId(session.getSessionId())
                .answer(answer)
                .intentType(IntentType.COMMAND_EXECUTION)
                .toolCalls(List.of())
                .riskLevel(injection.getRiskLevel())
                .riskDecision("BLOCK")
                .needConfirmation(false)
                .auditId(auditId)
                .build();
    }

    // ==================== 工具执行 ====================

    /**
     * 执行工具调用计划，按 order 分组执行。
     * 同 order 的 PARALLEL 工具并发执行，SEQUENTIAL 工具按序执行。
     */
    private void emitNotification(Supplier<com.kylinops.notification.NotificationEvent> eventSupplier) {
        if (notificationService == null || notificationEventFactory == null) {
            log.debug("notification dependencies unavailable, skip notification");
            return;
        }
        notificationService.emit(eventSupplier.get());
    }

    private List<ToolResult> executeToolPlan(ToolPlan plan, String auditId) {
        if (plan.getSteps().isEmpty()) {
            return List.of();
        }

        List<ToolResult> results = new ArrayList<>();

        // 按 order 分组（TreeMap 保证 order 升序）
        Map<Integer, List<ToolStep>> groupedByOrder = plan.getSteps().stream()
                .collect(Collectors.groupingBy(
                        ToolStep::getOrder,
                        TreeMap::new,
                        Collectors.toList()));

        for (Map.Entry<Integer, List<ToolStep>> entry : groupedByOrder.entrySet()) {
            List<ToolStep> steps = entry.getValue();
            boolean hasParallel = steps.stream().anyMatch(s -> s.getMode() == ExecutionMode.PARALLEL);

            if (hasParallel && steps.size() > 1) {
                // 同 order 的并行步骤同时执行
                List<CompletableFuture<ToolResult>> futures = steps.stream()
                        .map(step -> CompletableFuture.supplyAsync(
                                () -> executeToolSafely(step, auditId), parallelExecutor))
                        .collect(Collectors.toList());

                for (CompletableFuture<ToolResult> future : futures) {
                    try {
                        // 每个工具最多等 10s（含超时）
                        results.add(future.get(10000, TimeUnit.MILLISECONDS));
                    } catch (TimeoutException e) {
                        log.warn("并行工具执行超时");
                        results.add(ToolResult.timeout("parallel_execution", 10000));
                        future.cancel(true);
                    } catch (Exception e) {
                        log.warn("并行工具执行异常: {}", e.getMessage());
                        results.add(ToolResult.failed("parallel_execution", "并行执行异常: " + e.getMessage(), 0));
                    }
                }
            } else {
                // 串行执行
                for (ToolStep step : steps) {
                    ToolResult result = executeToolSafely(step, auditId);
                    results.add(result);

                    // 串行步骤失败，停止后续同 order 的串行步骤
                    if (!result.isSuccess()) {
                        log.warn("串行步骤执行失败，停止后续步骤: toolName={}", step.getToolName());
                        break;
                    }
                }
            }
        }

        return results;
    }

    /**
     * 安全地执行单个工具，捕获所有异常。
     */
    private ToolResult executeToolSafely(ToolStep step, String auditId) {
        try {
            log.debug("执行工具: toolName={}, params={}", step.getToolName(), step.getParams());
            return toolExecutor.execute(step.getToolName(), step.getParams(), auditId);
        } catch (ToolNotRegisteredException e) {
            log.warn("工具未注册，跳过: toolName={}", step.getToolName());
            return ToolResult.failed(step.getToolName(), "工具未注册: " + e.getToolName(), 0);
        } catch (Exception e) {
            log.error("工具执行异常: toolName={}", step.getToolName(), e);
            return ToolResult.failed(step.getToolName(), "工具执行异常: " + e.getMessage(), 0);
        }
    }

    // ==================== 参数提取 ====================

    /**
     * 合并 LLM 提取的参数与规则正则兜底参数。
     * <p>LLM 提取的 key 优先级低于规则正则提取（规则正则更可信、确定性更高）。
     * 仅在规则正则未提取到时使用 LLM 的值。</p>
     */
    private Map<String, Object> mergeParams(Map<String, Object> llmParams,
                                            Map<String, Object> ruleParams) {
        if (llmParams == null || llmParams.isEmpty()) {
            return ruleParams;
        }
        if (ruleParams == null || ruleParams.isEmpty()) {
            return llmParams;
        }
        Map<String, Object> merged = new HashMap<>(llmParams);
        merged.putAll(ruleParams);
        return merged;
    }

    /**
     * 从用户输入中提取结构化的工具参数。
     */
    private Map<String, Object> extractParams(String userInput, IntentType intent) {
        Map<String, Object> params = new HashMap<>();
        if (userInput == null || userInput.isBlank()) return params;

        String normalized = userInput.toLowerCase();

        // 提取 PID（用于 PROCESS_QUERY 的 process_detail_tool）
        Pattern pidPattern = Pattern.compile("pid\\s*[=:：]?\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
        Matcher pidMatcher = pidPattern.matcher(userInput);
        if (pidMatcher.find()) {
            try {
                params.put("pid", Integer.parseInt(pidMatcher.group(1)));
            } catch (NumberFormatException e) {
                log.warn("PID 格式无效: {}", pidMatcher.group(1));
            }
        }

        // 提取服务名（用于 SERVICE_DIAGNOSIS 的 service_status_tool）
        if (intent == IntentType.SERVICE_DIAGNOSIS) {
            String[] knownServices = {"nginx", "mysql", "redis", "ssh", "docker", "kylin", "postgresql",
                    "sshd", "systemd-journald", "cron", "rsyslog"};
            for (String service : knownServices) {
                if (normalized.contains(service)) {
                    params.put("serviceName", service);
                    log.debug("提取服务名: {}", service);
                    break;
                }
            }
            if (normalized.contains("重启") || normalized.contains("restart")) {
                params.put("operation", "restart");
            }
        }

        return params;
    }

    /**
     * 从计划中提取服务名（用于 L2 PendingAction）。
     */
    private String extractServiceName(ToolPlan plan) {
        if (plan == null || plan.getSteps() == null) return "unknown";
        return plan.getSteps().stream()
                .filter(s -> "service_status_tool".equals(s.getToolName()))
                .findFirst()
                .map(s -> {
                    Object name = s.getParams().get("serviceName");
                    return name != null ? name.toString() : "unknown";
                })
                .orElse("unknown");
    }

    /**
     * 从计划中提取参数（用于 L2 PendingAction）。
     */
    private Map<String, Object> extractParams(ToolPlan plan) {
        if (plan == null || plan.getSteps() == null || plan.getSteps().isEmpty()) {
            return Map.of();
        }
        return plan.getSteps().get(0).getParams() != null
                ? plan.getSteps().get(0).getParams()
                : Map.of();
    }

    // ==================== 会话与消息管理 ====================

    private Session findOrCreateSession(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            Optional<Session> existing = sessionRepository.findBySessionId(sessionId);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        Session session = new Session();
        session.setSessionId(sessionId != null && !sessionId.isBlank() ? sessionId : UUID.randomUUID().toString());
        session.setTitle("新会话");
        session.setStatus("ACTIVE");
        Session saved = sessionRepository.save(session);
        log.info("创建新会话: sessionId={}", saved.getSessionId());
        return saved;
    }

    private Message createUserMessage(Session session, String content, String auditId) {
        Message message = new Message();
        message.setMessageId(UUID.randomUUID().toString());
        message.setSession(session);
        message.setRole("user");
        message.setContent(content);
        message.setAuditId(auditId);
        Message saved = messageRepository.save(message);
        log.debug("创建用户消息: messageId={}, auditId={}", saved.getMessageId(), auditId);
        return saved;
    }

    private Message createAssistantMessage(Session session, String content, IntentType intent, String auditId) {
        Message message = new Message();
        message.setMessageId(UUID.randomUUID().toString());
        message.setSession(session);
        message.setRole("assistant");
        message.setContent(content);
        message.setIntentType(intent);
        message.setAuditId(auditId);
        Message saved = messageRepository.save(message);
        log.debug("创建助手消息: messageId={}, auditId={}", saved.getMessageId(), auditId);
        return saved;
    }

    // ==================== 结果构建 ====================

    private List<AgentResult.ToolCallInfo> buildToolCallInfo(List<ToolResult> results) {
        return results.stream()
                .map(r -> AgentResult.ToolCallInfo.builder()
                        .toolName(r.getToolName())
                        .status(r.getStatus())
                        .summary(r.getSummary())
                        .durationMs(r.getDurationMs())
                        .build())
                .collect(Collectors.toList());
    }

    private String truncate(String str, int maxLen) {
        if (str == null) return null;
        return str.length() <= maxLen ? str : str.substring(0, maxLen) + "...";
    }

    private boolean isDiscussionContext(String input) {
        if (input == null) {
            return false;
        }
        String normalized = input.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("为什么")
                || normalized.startsWith("为何")
                || normalized.startsWith("解释")
                || normalized.startsWith("说明")
                || normalized.contains("是什么意思")
                || normalized.contains("文档");
    }

    private String buildDiscussionAnswer(String input) {
        if (input != null && input.contains("rm -rf /")) {
            return "rm -rf / 会递归删除根目录内容，可能导致系统立即不可用和数据永久丢失。"
                    + "这是解释性回复，系统不会执行该命令，也不会创建工具或受控动作计划。";
        }
        return "这是解释性请求，系统不会执行其中提到的命令或动作。";
    }

    // ==================== 请求类型 ====================

    @Data
    @Builder
    public static class AgentRequest {
        private String sessionId;
        private String userInput;
        private String requestId;
        /** 已认证操作者身份（L2 归属校验用），由 ChatService 从 HTTP 请求上下文提取 */
        private AuthenticatedOperator operator;
    }
}
