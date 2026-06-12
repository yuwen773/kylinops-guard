package com.kylinops.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Security Center 只读目录 API。
 * <p>
 * 提供：
 * </p>
 * <ul>
 *   <li>GET /api/security/risk-levels — 风险等级目录（L0–L4）</li>
 *   <li>GET /api/security/rules — 已加载规则不可变快照</li>
 *   <li>GET /api/security/events — BLOCK 审计事件分页（createdAt DESC）</li>
 * </ul>
 *
 * <p>本控制器<strong>不</strong>暴露任何 PUT/POST/DELETE 规则入口；
 * 任何写入、修改或重新加载操作必须经过现有 ChatOrchestrator 链路。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/security")
@RequiredArgsConstructor
public class SecurityCatalogController {

    private final SecurityCatalogService securityCatalogService;

    /**
     * 风险等级目录。
     */
    @GetMapping("/risk-levels")
    public List<RiskLevelView> listRiskLevels() {
        log.debug("查询风险等级目录");
        return securityCatalogService.listRiskLevels();
    }

    /**
     * 已加载规则不可变快照。
     */
    @GetMapping("/rules")
    public List<SecurityRuleView> listRules() {
        log.debug("查询已加载规则快照");
        return securityCatalogService.listRules();
    }

    /**
     * 分页查询 BLOCK 审计事件（createdAt DESC，size clamp 1..100）。
     */
    @GetMapping("/events")
    public Page<SecurityEventView> listBlockEvents(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.debug("查询 BLOCK 事件: page={}, size={}", page, size);
        return securityCatalogService.listBlockEvents(page, size);
    }
}
