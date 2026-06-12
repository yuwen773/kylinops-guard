package com.kylinops.security;

import com.kylinops.audit.AuditLog;
import com.kylinops.audit.AuditLogRepository;
import com.kylinops.common.enums.RiskDecision;
import com.kylinops.common.enums.RiskLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 安全目录只读服务。
 * <p>
 * 为 Security Center 提供三类只读数据：
 * </p>
 * <ul>
 *   <li>风险等级目录（静态 L0–L4 元信息）</li>
 *   <li>已加载规则快照（来自 {@link RiskRuleEngine}）</li>
 *   <li>分页 BLOCK 审计事件（来自 {@link AuditLogRepository}）</li>
 * </ul>
 *
 * <p>本服务不暴露任何新增、修改、删除、重新加载规则的入口。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SecurityCatalogService {

    /** 单次分页最大 size，防止全表扫/内存打爆 */
    static final int MAX_PAGE_SIZE = 100;

    /** 单次分页默认 size */
    static final int DEFAULT_PAGE_SIZE = 20;

    private final RiskRuleEngine riskRuleEngine;
    private final AuditLogRepository auditLogRepository;

    /**
     * 返回 L0–L4 风险等级目录。
     */
    public List<RiskLevelView> listRiskLevels() {
        return Arrays.asList(
                RiskLevelView.builder()
                        .level(RiskLevel.L0)
                        .decision(RiskDecision.ALLOW)
                        .description("信息查询：纯只读操作，无副作用，不写审计。")
                        .examples(List.of("df -h", "查看 CPU 状态", "读取日志"))
                        .build(),
                RiskLevelView.builder()
                        .level(RiskLevel.L1)
                        .decision(RiskDecision.ALLOW)
                        .description("轻度风险：直接放行，但记录审计。")
                        .examples(List.of("查看服务状态", "查看进程详情"))
                        .build(),
                RiskLevelView.builder()
                        .level(RiskLevel.L2)
                        .decision(RiskDecision.CONFIRM)
                        .description("中等风险：必须用户确认后执行。")
                        .examples(List.of("重启 nginx 服务", "清理 /tmp 缓存预览"))
                        .build(),
                RiskLevelView.builder()
                        .level(RiskLevel.L3)
                        .decision(RiskDecision.BLOCK)
                        .description("高风险：直接阻断，生成审计记录。")
                        .examples(List.of("修改 /etc 下文件", "写入系统配置"))
                        .build(),
                RiskLevelView.builder()
                        .level(RiskLevel.L4)
                        .decision(RiskDecision.BLOCK)
                        .description("严重风险：绝对阻断（含绝对禁止命令清单）。")
                        .examples(List.of(
                                "rm -rf /",
                                "chmod -R 777 /",
                                "Prompt 注入 + 危险命令"))
                        .build()
        );
    }

    /**
     * 返回 RiskRuleEngine 已加载的规则不可变快照。
     */
    public List<SecurityRuleView> listRules() {
        return riskRuleEngine.getImmutableRulesSnapshot();
    }

    /**
     * 分页查询 BLOCK 审计事件（按 createdAt DESC）。
     * <p>
     * size 被强制 clamp 到 [1, {@link #MAX_PAGE_SIZE}]；
     * 负值或 0 视为使用默认值。
     * </p>
     *
     * @param page 页码（从 0 开始）
     * @param size 每页条数
     */
    @Transactional(readOnly = true)
    public Page<SecurityEventView> listBlockEvents(int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = clampSize(size);

        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<AuditLog> result = auditLogRepository.findByRiskDecision(RiskDecision.BLOCK, pageable);

        return result.map(this::toEventView);
    }

    /**
     * 将 size 限制在 [1, MAX_PAGE_SIZE] 之间；
     * 非正数回退到默认 20。
     */
    static int clampSize(int size) {
        if (size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private SecurityEventView toEventView(AuditLog log) {
        List<String> matched = parseMatchedRules(log.getMatchedRules());
        return SecurityEventView.builder()
                .auditId(log.getAuditId())
                .riskLevel(log.getRiskLevel())
                .decision(log.getRiskDecision())
                .matchedRules(matched)
                .reason(log.getMessage())
                .createdAt(log.getCreatedAt())
                .toolName(log.getToolName())
                .build();
    }

    /**
     * 解析持久化层存的 JSON 数组形式的 matchedRules。
     * <p>
     * 兼容纯 JSON 数组；解析失败回退为空列表，永不抛异常给前端。
     * </p>
     */
    private List<String> parseMatchedRules(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String trimmed = raw.trim();
        // 期望形如 ["rule_a","rule_b"] 或 [rule_a,rule_b]
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
            return List.of();
        }
        String body = trimmed.substring(1, trimmed.length() - 1).trim();
        if (body.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : body.split(",")) {
            String token = part.trim();
            if (token.isEmpty()) continue;
            // 去掉包裹的引号
            if (token.length() >= 2 && token.startsWith("\"") && token.endsWith("\"")) {
                token = token.substring(1, token.length() - 1);
            }
            if (!token.isEmpty()) {
                out.add(token);
            }
        }
        return out;
    }
}
