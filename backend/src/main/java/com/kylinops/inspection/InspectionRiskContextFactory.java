package com.kylinops.inspection;

import com.kylinops.security.RiskEvaluationContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 巡检 RiskCheck 上下文工厂(P1-02 Task 3,设计 §4)。
 *
 * <p>为每个工具调用构造 {@link RiskEvaluationContext},使现有只匹配 {@code content}
 * 的 {@link com.kylinops.security.RiskRuleEngine} 能继续检查路径和参数:</p>
 * <ul>
 *   <li>{@code targetType="tool"}</li>
 *   <li>{@code content="<toolName> <canonical-json(params)>"} — JSON key 字典序排序,
 *       与现有 {@code RiskRuleEngine} 的归一化匹配兼容</li>
 *   <li>secret-like keys(password/secret/token/apiKey/privateKey)直接拒绝,绝不写入
 *       {@code params}(防止密钥经审计日志泄漏)</li>
 * </ul>
 */
@Component
public class InspectionRiskContextFactory {

    /** 任何以这些名字结尾/包含的 key 一律拒绝,以大小写不敏感方式匹配。 */
    private static final Set<String> SECRET_KEYS = Set.of(
            "password", "secret", "token", "apikey", "privatekey");

    /** 使用 Jackson 但禁用缩进、关闭时间戳序列化以保证确定性。 */
    private final ObjectMapper canonicalMapper;

    public InspectionRiskContextFactory() {
        this.canonicalMapper = new ObjectMapper()
                .disable(SerializationFeature.INDENT_OUTPUT)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * 构造 RiskEvaluationContext。
     *
     * @param toolName 工具注册名
     * @param params   工具参数(已校验)
     * @return 包装好的上下文,content 已规范化
     * @throws InspectionValidationException 当 params 含 secret-like key
     */
    public RiskEvaluationContext create(String toolName, Map<String, Object> params) {
        if (toolName == null || toolName.isBlank()) {
            throw new InspectionValidationException("[toolName] 不能为空");
        }
        Map<String, Object> safeParams = params == null ? Map.of() : params;

        // 1) Secret-like key 拦截
        for (String key : safeParams.keySet()) {
            String lower = key == null ? "" : key.toLowerCase();
            for (String secret : SECRET_KEYS) {
                if (lower.contains(secret)) {
                    throw new InspectionValidationException(
                            "拒绝写入 secret-like 字段到 RiskEvaluationContext.params: " + key);
                }
            }
        }

        // 2) 字典序排序后再序列化(LinkedHashMap 保证顺序)
        Map<String, Object> ordered = new LinkedHashMap<>();
        safeParams.entrySet().stream()
                .filter(e -> e.getKey() != null)
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> ordered.put(e.getKey(), e.getValue()));

        String json;
        try {
            json = canonicalMapper.writeValueAsString(ordered);
        } catch (JsonProcessingException e) {
            throw new InspectionValidationException(
                    "参数无法序列化为 JSON: " + e.getMessage(), e);
        }

        String content = toolName + " " + json;
        return new RiskEvaluationContext("tool", content, toolName, ordered);
    }
}