package com.kylinops.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.MapType;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.kylinops.common.enums.RiskDecision;
import com.kylinops.common.enums.RiskLevel;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 风险规则引擎
 * <p>
 * 从 {@code rules/security-rules.yml} 加载安全规则，
 * 对输入内容执行归一化匹配，按合并策略输出综合风险等级和决策。
 * </p>
 *
 * <h3>合并策略</h3>
 * <ul>
 *   <li>多条规则命中时取最高 riskLevel</li>
 *   <li>同等级下 BLOCK > CONFIRM > ALLOW</li>
 *   <li>空内容或未命中任何规则 → L0 / ALLOW</li>
 * </ul>
 *
 * <h3>启动安全</h3>
 * <ul>
 *   <li>规则文件缺失、格式错误或为空时，应用启动失败</li>
 *   <li>运行期间规则不可变</li>
 * </ul>
 */
@Slf4j
@Component
public class RiskRuleEngine {

    /** 规则文件路径（classpath） */
    private static final String RULES_PATH = "/rules/security-rules.yml";

    /** 不可变规则列表 */
    private final List<RiskRule> rules;

    /** 规则是否已成功初始化 */
    private boolean initialized = false;

    /**
     * 默认构造器：从 classpath 加载 security-rules.yml
     * <p>
     * 用于 Spring 自动装配和生产环境启动。
     * 若规则加载失败，抛出 RuntimeException 阻止应用启动。
     * </p>
     */
    public RiskRuleEngine() {
        try {
            this.rules = Collections.unmodifiableList(loadRules(RULES_PATH));
            this.initialized = true;
            log.info("RiskRuleEngine 初始化完成: 共 {} 条规则", rules.size());
        } catch (Exception e) {
            log.error("安全规则加载失败 ({}): {}", RULES_PATH, e.getMessage());
            throw new RuntimeException("安全规则加载失败，应用启动中止: " + e.getMessage(), e);
        }
    }

    /**
     * 测试用构造器：直接指定规则列表。
     */
    RiskRuleEngine(List<RiskRule> rules) {
        this.rules = rules != null ? Collections.unmodifiableList(rules) : List.of();
        this.initialized = true;
    }

    @PostConstruct
    public void init() {
        if (!initialized) {
            throw new IllegalStateException("RiskRuleEngine 未正确初始化");
        }
        log.info("RiskRuleEngine 启动验证完成: {} 条规则已加载", rules.size());
        if (rules.isEmpty()) {
            throw new IllegalStateException("安全规则列表为空，应用启动中止");
        }
    }

    /**
     * 对评估上下文执行风险规则匹配。
     *
     * @param ctx 风险评估上下文
     * @return 综合风险校验结果（永远不会返回 null）
     */
    public RiskCheckResult evaluate(RiskEvaluationContext ctx) {
        if (ctx == null || ctx.getNormalizedContent() == null || ctx.getNormalizedContent().isBlank()) {
            return RiskCheckResult.allow(RiskLevel.L0, "无输入内容");
        }

        List<RiskRule> matched = new ArrayList<>();

        for (RiskRule rule : rules) {
            if (!rule.isEnabled()) continue;
            if (!rule.matchesTargetType(ctx.getTargetType())) continue;
            if (rule.matches(ctx.getNormalizedContent())) {
                matched.add(rule);
            }
        }

        if (matched.isEmpty()) {
            return RiskCheckResult.allow(RiskLevel.L0, "未命中任何风险规则");
        }

        // 合并：取最高 riskLevel
        RiskLevel maxLevel = matched.stream()
                .map(RiskRule::getRiskLevel)
                .max(Comparator.comparingInt(Enum::ordinal))
                .orElse(RiskLevel.L0);

        // 同等级下取最高 decision: BLOCK > CONFIRM > ALLOW
        List<RiskRule> sameLevelRules = matched.stream()
                .filter(r -> r.getRiskLevel() == maxLevel)
                .collect(Collectors.toList());

        RiskDecision mergedDecision = mergeDecisions(sameLevelRules);

        // 取第一个同等级规则的原因/建议
        RiskRule primary = sameLevelRules.get(0);
        List<String> matchedIds = matched.stream()
                .map(RiskRule::getId)
                .collect(Collectors.toList());

        return RiskCheckResult.builder()
                .riskLevel(maxLevel)
                .decision(mergedDecision)
                .matchedRules(matchedIds)
                .reason(primary.getReason())
                .safeSuggestion(primary.getSafeSuggestion())
                .build();
    }

    /**
     * 返回所有已加载的规则（不可变视图）。
     */
    public List<RiskRule> getRules() {
        return rules;
    }

    /**
     * 返回指定风险等级的规则列表。
     */
    public List<RiskRule> getRulesByLevel(RiskLevel level) {
        return rules.stream()
                .filter(r -> r.getRiskLevel() == level)
                .collect(Collectors.toList());
    }

    /**
     * 返回当前已加载规则的不可变快照视图（只读 DTO 列表）。
     * <p>
     * 用于 Security Center 的 GET /api/security/rules；
     * 每次调用都生成新的 {@link SecurityRuleView} 列表，避免外部持有
     * 可变引用的同时仍能反映当前的 enabled 状态。
     * </p>
     */
    public List<SecurityRuleView> getImmutableRulesSnapshot() {
        List<SecurityRuleView> snapshot = new ArrayList<>(rules.size());
        for (RiskRule r : rules) {
            snapshot.add(SecurityRuleView.builder()
                    .ruleId(r.getId())
                    .name(r.getId())
                    .description(r.getReason())
                    .regex(r.getPatternString())
                    .targetTypes(r.getTargetTypes())
                    .riskLevel(r.getRiskLevel())
                    .decision(r.getDecision())
                    .reason(r.getReason())
                    .safeSuggestion(r.getSafeSuggestion())
                    .enabled(r.isEnabled())
                    .priority(r.getPriority())
                    .build());
        }
        return Collections.unmodifiableList(snapshot);
    }

    // ==================== 内部方法 ====================

    /**
     * 从 classpath 加载 YAML 规则文件。
     */
    @SuppressWarnings("unchecked")
    private List<RiskRule> loadRules(String path) {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        MapType mapType = mapper.getTypeFactory()
                .constructMapType(HashMap.class, String.class, Object.class);

        try (InputStream is = getClass().getResourceAsStream(path)) {
            if (is == null) {
                throw new RuntimeException("规则文件不存在: " + path);
            }

            Map<String, Object> raw = mapper.readValue(is, mapType);
            if (raw == null || raw.isEmpty()) {
                throw new RuntimeException("规则文件为空: " + path);
            }

            List<Map<String, Object>> rawRules = (List<Map<String, Object>>) raw.get("rules");
            if (rawRules == null || rawRules.isEmpty()) {
                throw new RuntimeException("规则列表为空或格式错误: " + path);
            }

            List<RiskRule> parsed = new ArrayList<>();
            for (Map<String, Object> rawRule : rawRules) {
                parsed.add(parseRule(rawRule));
            }

            if (parsed.isEmpty()) {
                throw new RuntimeException("未解析到任何有效规则: " + path);
            }

            // 按优先级降序排列（高优先级在前）
            parsed.sort((a, b) -> Integer.compare(b.getPriority(), a.getPriority()));

            return parsed;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("规则文件解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从 Map 解析单条规则。
     */
    @SuppressWarnings("unchecked")
    private RiskRule parseRule(Map<String, Object> raw) {
        String id = (String) raw.get("id");
        if (id == null || id.isBlank()) {
            throw new RuntimeException("规则缺少 id");
        }

        List<String> targetTypes = (List<String>) raw.get("targetTypes");
        if (targetTypes == null || targetTypes.isEmpty()) {
            throw new RuntimeException("规则 " + id + " 缺少 targetTypes");
        }

        String pattern = (String) raw.get("pattern");
        if (pattern == null || pattern.isBlank()) {
            throw new RuntimeException("规则 " + id + " 缺少 pattern");
        }

        String riskLevelStr = (String) raw.get("riskLevel");
        RiskLevel riskLevel = RiskLevel.valueOf(riskLevelStr);
        if (riskLevel == null) {
            throw new RuntimeException("规则 " + id + " 无效 riskLevel: " + riskLevelStr);
        }

        String decisionStr = (String) raw.get("decision");
        RiskDecision decision = RiskDecision.valueOf(decisionStr);
        if (decision == null) {
            throw new RuntimeException("规则 " + id + " 无效 decision: " + decisionStr);
        }

        String reason = (String) raw.getOrDefault("reason", "");
        String safeSuggestion = (String) raw.get("safeSuggestion");
        boolean enabled = raw.get("enabled") != null ? Boolean.parseBoolean(raw.get("enabled").toString()) : true;
        int priority = raw.get("priority") != null ? Integer.parseInt(raw.get("priority").toString()) : 0;

        return new RiskRule(id, targetTypes, pattern, riskLevel, decision, reason, safeSuggestion, enabled, priority);
    }

    /**
     * 在同等级规则中合并决策。
     * 优先级: BLOCK > CONFIRM > ALLOW。
     */
    private RiskDecision mergeDecisions(List<RiskRule> rules) {
        boolean hasBlock = rules.stream().anyMatch(r -> r.getDecision() == RiskDecision.BLOCK);
        if (hasBlock) return RiskDecision.BLOCK;

        boolean hasConfirm = rules.stream().anyMatch(r -> r.getDecision() == RiskDecision.CONFIRM);
        if (hasConfirm) return RiskDecision.CONFIRM;

        return RiskDecision.ALLOW;
    }
}
