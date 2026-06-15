package com.kylinops.agent.intelligence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kylinops.common.enums.IntentType;
import com.kylinops.llm.ChatMessage;
import com.kylinops.llm.LlmCallResult;
import com.kylinops.llm.LlmClient;
import com.kylinops.llm.LlmClientException;
import com.kylinops.llm.LlmStage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * LLM 意图解析器（P3-T2）。
 *
 * <p>封装 LLM 调用 + JSON 解析 + 白名单校验 + 阈值判断。</p>
 *
 * <p><strong>安全约束</strong>：</p>
 * <ul>
 *   <li>系统 prompt 硬编码（中文），明确告知 LLM：仅输出 IntentType 枚举 + confidence + params</li>
 *   <li>IntentType 必须在白名单内（{@link IntentTypeAllowlist}）</li>
 *   <li>params 的 key 必须在 allowlist 内（{@link IntentParamAllowlist}）</li>
 *   <li>confidence &lt; threshold → 视为 FALLBACK（语义：模型不确定）</li>
 *   <li>任何 LLM 异常（{@link LlmClientException} 或其他 RuntimeException）→ 视为 FALLBACK，
 *       <strong>不向上抛</strong></li>
 *   <li>系统 prompt / user 输入不写入日志原文（仅截断 + 长度）</li>
 * </ul>
 */
@Slf4j
@Service
public class LlmIntentParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** LLM 未启用（client 为 null）时的固定 fallback 标记。 */
    private static final ParsedLlmIntent DISABLED = new ParsedLlmIntent(
            ParsedLlmIntent.Outcome.INVALID, null, 0.0, Map.of());

    @Nullable
    @Autowired(required = false)
    private LlmClient llmClient;

    private final double confidenceThreshold;

    /**
     * Spring 装配构造器。
     * <p>当 LlmClient 未启用时（{@code kylinops.llm.enabled=false}），
     * 容器中没有 LlmClient bean，{@code llmClient} 字段为 null，
     * 解析器自动走「仅规则」路径。</p>
     */
    @org.springframework.beans.factory.annotation.Autowired
    public LlmIntentParser(
            @org.springframework.beans.factory.annotation.Value("${kylinops.llm.confidence-threshold:0.75}")
            double confidenceThreshold) {
        this.confidenceThreshold = confidenceThreshold;
    }

    /**
     * 显式构造器（测试 / 不走 Spring 容器时用）。
     * <p>允许传入 null {@code llmClient} 表示 LLM 关闭。</p>
     */
    public LlmIntentParser(LlmClient llmClient, double confidenceThreshold) {
        this.llmClient = llmClient;
        this.confidenceThreshold = confidenceThreshold;
    }

    /**
     * 解析用户输入的意图。
     *
     * <p>实现要点：</p>
     * <ol>
     *   <li>组装 system + user messages</li>
     *   <li>调用 LlmClient.complete(LlmStage.INTENT, messages)</li>
     *   <li>解析返回 content 为 JSON</li>
     *   <li>校验 IntentType 必须在白名单内</li>
     *   <li>校验 confidence ∈ [0,1] 且 ≥ threshold</li>
     *   <li>校验 params key 必须在 allowlist 内</li>
     * </ol>
     *
     * <p>任意步骤失败 → 返回 INVALID；调用方应回退到 FALLBACK。</p>
     */
    public ParsedLlmIntent parse(String userInput) {
        if (llmClient == null) {
            return DISABLED;
        }
        if (userInput == null || userInput.isBlank()) {
            return DISABLED;
        }

        List<ChatMessage> messages = buildMessages(userInput);

        LlmCallResult result;
        try {
            result = llmClient.complete(LlmStage.INTENT, messages);
        } catch (LlmClientException e) {
            log.warn("LLM 意图解析失败 (reason={}): 不阻塞主链路, 回退规则匹配",
                    e.getReason());
            return DISABLED;
        } catch (RuntimeException e) {
            // 防御性：非 LlmClientException 的异常也吞掉，不阻塞主链路
            log.warn("LLM 意图解析抛未预期异常: {}", e.getMessage());
            return DISABLED;
        }

        if (result == null || result.content() == null || result.content().isBlank()) {
            log.warn("LLM 意图解析返回空 content, 回退规则匹配");
            return DISABLED;
        }

        return parseAndValidate(result.content());
    }

    /**
     * 解析 + 校验 JSON 内容。
     */
    private ParsedLlmIntent parseAndValidate(String content) {
        JsonNode root;
        try {
            root = JSON.readTree(content);
        } catch (Exception e) {
            log.warn("LLM 返回非 JSON, 回退规则匹配: {}", truncate(content, 80));
            return DISABLED;
        }

        if (root == null || !root.isObject()) {
            log.warn("LLM 返回非对象 JSON, 回退规则匹配");
            return DISABLED;
        }

        JsonNode intentNode = root.get("intent");
        if (intentNode == null || !intentNode.isTextual()) {
            log.warn("LLM JSON 缺 intent 字段, 回退规则匹配");
            return DISABLED;
        }

        String intentName = intentNode.asText();
        IntentType intent;
        try {
            intent = IntentType.valueOf(intentName);
        } catch (IllegalArgumentException e) {
            log.warn("LLM 返回非白名单 IntentType: {}, 回退规则匹配", intentName);
            return DISABLED;
        }

        if (!IntentTypeAllowlist.isAllowed(intent)) {
            log.warn("LLM 输出的 IntentType 命中黑名单: {}, 回退规则匹配", intent);
            return DISABLED;
        }

        // confidence
        JsonNode confNode = root.get("confidence");
        double confidence = 0.0;
        if (confNode != null && confNode.isNumber()) {
            confidence = confNode.asDouble();
            if (Double.isNaN(confidence) || Double.isInfinite(confidence)) {
                confidence = 0.0;
            } else if (confidence < 0.0) {
                confidence = 0.0;
            } else if (confidence > 1.0) {
                confidence = 1.0;
            }
        }

        if (confidence < confidenceThreshold) {
            log.info("LLM 置信度 {} 低于阈值 {}, 回退规则匹配", confidence, confidenceThreshold);
            return new ParsedLlmIntent(ParsedLlmIntent.Outcome.INVALID,
                    intent, confidence, Map.of());
        }

        // params allowlist 过滤
        Map<String, Object> params = extractParams(root, intent);

        return new ParsedLlmIntent(ParsedLlmIntent.Outcome.VALID, intent, confidence, params);
    }

    /**
     * 从 JSON 中提取 params，并按 allowlist 过滤。
     * <p>仅保留 value 是标量（字符串/数字/布尔）的 key；嵌套对象/数组一律丢弃。</p>
     */
    private Map<String, Object> extractParams(JsonNode root, IntentType intent) {
        Set<String> allowed = IntentParamAllowlist.keysFor(intent);
        if (allowed.isEmpty()) {
            return Map.of();
        }

        JsonNode paramsNode = root.get("params");
        if (paramsNode == null || !paramsNode.isObject()) {
            return Map.of();
        }

        Map<String, Object> filtered = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = paramsNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String key = field.getKey();
            JsonNode value = field.getValue();
            if (!allowed.contains(key)) {
                continue;
            }
            if (value == null || value.isNull()) {
                continue;
            }
            if (value.isTextual()) {
                filtered.put(key, value.asText());
            } else if (value.isNumber()) {
                filtered.put(key, value.numberValue());
            } else if (value.isBoolean()) {
                filtered.put(key, value.asBoolean());
            }
            // 数组 / 嵌套对象一律丢弃
        }
        return Collections.unmodifiableMap(filtered);
    }

    /**
     * 组装 system + user messages。
     */
    private List<ChatMessage> buildMessages(String userInput) {
        return List.of(
                ChatMessage.system(buildSystemPrompt()),
                ChatMessage.user(userInput)
        );
    }

    /**
     * 构造硬编码中文 system prompt。
     *
     * <p>明确告诉 LLM：</p>
     * <ul>
     *   <li>仅返回 JSON（intent/confidence/params）</li>
     *   <li>intent 必须在白名单内（黑名单 COMMAND_EXECUTION 已在提示中明列）</li>
     *   <li>不能给出 ToolPlan / RiskDecision / 命令</li>
     * </ul>
     */
    private String buildSystemPrompt() {
        return """
                你是意图分类助手。请将用户的运维问题分类为预定义 IntentType 之一。
                你必须且只能返回以下 JSON 结构（不要任何额外文字、代码块或解释）：

                {
                  "intent": "<IntentType 枚举名>",
                  "confidence": <0.0-1.0 之间的浮点数>,
                  "params": { "key": "value" }
                }

                允许的 IntentType（仅以下 8 个，枚举名严格匹配）：

                - SYSTEM_CHECK：系统健康检查 / 巡检
                - DISK_DIAGNOSIS：磁盘用量分析
                - SERVICE_DIAGNOSIS：服务状态 / 重启
                - PROCESS_QUERY：进程列表 / 详情
                - NETWORK_QUERY：端口 / 网络连接
                - FILE_OPERATION：文件清理 / 截断
                - LOG_QUERY：日志查看
                - GENERAL_CHAT：非运维类闲聊

                绝对禁止：
                - 输出 COMMAND_EXECUTION —— 危险命令必须由前置规则识别
                - 输出 UNKNOWN —— 置信度不足由调用方处理
                - 给出任何 ToolPlan / RiskDecision / 命令
                - 输出代码块、Markdown、解释文字

                如果输入含「执行命令 / rm / chmod / dd / 格式化」等危险信号，
                应回退为低 confidence（< 0.5）由调用方忽略。""";
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // ==================== 内部类型 ====================

    /**
     * LLM 解析结果。
     * <p>VALID = 解析成功且通过校验；INVALID = 任意步骤失败（调用方应回退 FALLBACK）。</p>
     */
    public static final class ParsedLlmIntent {

        public enum Outcome {
            VALID,
            INVALID
        }

        private final Outcome outcome;
        private final IntentType intent;
        private final double confidence;
        private final Map<String, Object> params;

        public ParsedLlmIntent(Outcome outcome, IntentType intent, double confidence,
                               Map<String, Object> params) {
            this.outcome = outcome;
            this.intent = intent;
            this.confidence = confidence;
            this.params = params == null ? Map.of() : Map.copyOf(params);
        }

        public boolean isValid() {
            return outcome == Outcome.VALID;
        }

        public Outcome getOutcome() {
            return outcome;
        }

        public IntentType getIntent() {
            return intent;
        }

        public double getConfidence() {
            return confidence;
        }

        public Map<String, Object> getParams() {
            return params;
        }
    }
}
