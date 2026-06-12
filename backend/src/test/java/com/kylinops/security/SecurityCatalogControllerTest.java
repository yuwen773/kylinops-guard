package com.kylinops.security;

import com.kylinops.common.enums.RiskDecision;
import com.kylinops.common.enums.RiskLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SecurityCatalogController MockMvc 测试。
 * <p>
 * 验证：
 * <ul>
 *   <li>/api/security/risk-levels 返回 5 项 L0–L4</li>
 *   <li>/api/security/rules 返回已加载规则不可变快照</li>
 *   <li>/api/security/events 仅 BLOCK，createdAt DESC，size clamp 100</li>
 *   <li>不存在 PUT/POST/DELETE 端点</li>
 * </ul>
 */
@WebMvcTest(SecurityCatalogController.class)
@DisplayName("SecurityCatalogController — Security Center 只读目录 API")
class SecurityCatalogControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SecurityCatalogService securityCatalogService;

    @BeforeEach
    void setUp() {
        // 默认：service.listRiskLevels() 委派给 SecurityCatalogService 内置静态数据
        when(securityCatalogService.listRiskLevels()).thenAnswer(inv -> List.of(
                RiskLevelView.builder().level(RiskLevel.L0).decision(RiskDecision.ALLOW)
                        .description("L0 描述").examples(List.of("df -h")).build(),
                RiskLevelView.builder().level(RiskLevel.L1).decision(RiskDecision.ALLOW)
                        .description("L1 描述").examples(List.of()).build(),
                RiskLevelView.builder().level(RiskLevel.L2).decision(RiskDecision.CONFIRM)
                        .description("L2 描述").examples(List.of()).build(),
                RiskLevelView.builder().level(RiskLevel.L3).decision(RiskDecision.BLOCK)
                        .description("L3 描述").examples(List.of()).build(),
                RiskLevelView.builder().level(RiskLevel.L4).decision(RiskDecision.BLOCK)
                        .description("L4 描述").examples(List.of()).build()
        ));

        when(securityCatalogService.listRules()).thenReturn(List.of(
                SecurityRuleView.builder()
                        .ruleId("block_rm_rf_root")
                        .name("block_rm_rf_root")
                        .description("绝对禁止的根目录递归删除操作")
                        .regex("(?i)rm\\s+-?\\s*rf\\s+/.*")
                        .targetTypes(List.of("command"))
                        .riskLevel(RiskLevel.L4)
                        .decision(RiskDecision.BLOCK)
                        .reason("绝对禁止的根目录递归删除操作")
                        .safeSuggestion("该操作可能导致系统损坏或数据丢失，已被系统禁止。")
                        .enabled(true)
                        .priority(100)
                        .build(),
                SecurityRuleView.builder()
                        .ruleId("disabled_rule")
                        .name("disabled_rule")
                        .description("已禁用")
                        .regex(".*")
                        .targetTypes(List.of("command"))
                        .riskLevel(RiskLevel.L1)
                        .decision(RiskDecision.ALLOW)
                        .reason("测试禁用")
                        .safeSuggestion(null)
                        .enabled(false)
                        .priority(1)
                        .build()
        ));
    }

    // ==================== /risk-levels ====================

    @Test
    @DisplayName("GET /api/security/risk-levels → 5 项 L0–L4")
    void riskLevelsReturnsFiveItems() throws Exception {
        mockMvc.perform(get("/api/security/risk-levels"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(5)))
                .andExpect(jsonPath("$[0].level").value("L0"))
                .andExpect(jsonPath("$[0].decision").value("ALLOW"))
                .andExpect(jsonPath("$[0].description").isString())
                .andExpect(jsonPath("$[0].examples").isArray())
                .andExpect(jsonPath("$[1].level").value("L1"))
                .andExpect(jsonPath("$[2].level").value("L2"))
                .andExpect(jsonPath("$[2].decision").value("CONFIRM"))
                .andExpect(jsonPath("$[3].level").value("L3"))
                .andExpect(jsonPath("$[3].decision").value("BLOCK"))
                .andExpect(jsonPath("$[4].level").value("L4"))
                .andExpect(jsonPath("$[4].decision").value("BLOCK"));
    }

    // ==================== /rules ====================

    @Test
    @DisplayName("GET /api/security/rules → 已加载规则不可变快照")
    void rulesReturnsSnapshot() throws Exception {
        mockMvc.perform(get("/api/security/rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].ruleId").value("block_rm_rf_root"))
                .andExpect(jsonPath("$[0].name").value("block_rm_rf_root"))
                .andExpect(jsonPath("$[0].description").isString())
                .andExpect(jsonPath("$[0].regex").isString())
                .andExpect(jsonPath("$[0].riskLevel").value("L4"))
                .andExpect(jsonPath("$[0].decision").value("BLOCK"))
                .andExpect(jsonPath("$[0].enabled").value(true))
                .andExpect(jsonPath("$[0].priority").value(100))
                .andExpect(jsonPath("$[1].ruleId").value("disabled_rule"))
                .andExpect(jsonPath("$[1].enabled").value(false));
    }

    @Test
    @DisplayName("GET /api/security/rules → 验证没有修改入口（Service 仅被 listRules 调用）")
    void rulesHasNoMutationEndpoint() throws Exception {
        mockMvc.perform(get("/api/security/rules")).andExpect(status().isOk());
        verify(securityCatalogService).listRules();
        verify(securityCatalogService, never()).listBlockEvents(anyInt(), anyInt());
    }

    // ==================== /events ====================

    @Test
    @DisplayName("GET /api/security/events 不带参数 → page=0, size=20")
    void eventsDefaultsPageAndSize() throws Exception {
        Page<SecurityEventView> empty = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        when(securityCatalogService.listBlockEvents(0, 20)).thenReturn(empty);

        mockMvc.perform(get("/api/security/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.size").value(20));

        verify(securityCatalogService).listBlockEvents(0, 20);
    }

    @Test
    @DisplayName("GET /api/security/events?size=200 → clamp 到 100")
    void eventsSizeClampedTo100() throws Exception {
        Page<SecurityEventView> page = new PageImpl<>(List.of(), PageRequest.of(0, 100), 0);
        when(securityCatalogService.listBlockEvents(0, 200)).thenReturn(page);

        mockMvc.perform(get("/api/security/events").param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));

        // service 收到的仍是原始 size=200，clamp 由 service 完成
        verify(securityCatalogService).listBlockEvents(0, 200);
    }

    @Test
    @DisplayName("GET /api/security/events?size=0 → 使用默认 20")
    void eventsSizeZeroFallbackDefault() throws Exception {
        Page<SecurityEventView> page = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        when(securityCatalogService.listBlockEvents(0, 0)).thenReturn(page);

        mockMvc.perform(get("/api/security/events").param("size", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(20));
    }

    @Test
    @DisplayName("GET /api/security/events → 只返回 BLOCK 事件，createdAt DESC")
    void eventsOnlyBlockAndDescOrder() throws Exception {
        LocalDateTime t1 = LocalDateTime.of(2026, 6, 12, 10, 0, 0);
        LocalDateTime t2 = LocalDateTime.of(2026, 6, 12, 9, 0, 0);
        LocalDateTime t3 = LocalDateTime.of(2026, 6, 12, 8, 0, 0);

        List<SecurityEventView> events = List.of(
                SecurityEventView.builder()
                        .auditId("a-1")
                        .riskLevel(RiskLevel.L4)
                        .decision(RiskDecision.BLOCK)
                        .matchedRules(List.of("block_rm_rf_root"))
                        .reason("绝对禁止的根目录递归删除操作")
                        .createdAt(t1)
                        .toolName(null)
                        .build(),
                SecurityEventView.builder()
                        .auditId("a-2")
                        .riskLevel(RiskLevel.L4)
                        .decision(RiskDecision.BLOCK)
                        .matchedRules(List.of("block_chmod_777_root"))
                        .reason("绝对禁止的根目录权限变更")
                        .createdAt(t2)
                        .toolName(null)
                        .build(),
                SecurityEventView.builder()
                        .auditId("a-3")
                        .riskLevel(RiskLevel.L3)
                        .decision(RiskDecision.BLOCK)
                        .matchedRules(List.of("block_protected_path_etc"))
                        .reason("受保护路径")
                        .createdAt(t3)
                        .toolName(null)
                        .build()
        );
        Page<SecurityEventView> page = new PageImpl<>(events, PageRequest.of(0, 20), events.size());
        when(securityCatalogService.listBlockEvents(0, 20)).thenReturn(page);

        mockMvc.perform(get("/api/security/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(3)))
                .andExpect(jsonPath("$.content[0].auditId").value("a-1"))
                .andExpect(jsonPath("$.content[0].decision").value("BLOCK"))
                .andExpect(jsonPath("$.content[0].riskLevel").value("L4"))
                .andExpect(jsonPath("$.content[0].matchedRules[0]").value("block_rm_rf_root"))
                .andExpect(jsonPath("$.content[0].reason").value("绝对禁止的根目录递归删除操作"))
                .andExpect(jsonPath("$.content[0].createdAt").isString())
                .andExpect(jsonPath("$.content[1].auditId").value("a-2"))
                .andExpect(jsonPath("$.content[2].auditId").value("a-3"));
    }

    @Test
    @DisplayName("GET /api/security/events?page=1 → 返回第二页")
    void eventsRespectsPage() throws Exception {
        Page<SecurityEventView> page = new PageImpl<>(List.of(), PageRequest.of(1, 20), 30);
        when(securityCatalogService.listBlockEvents(1, 20)).thenReturn(page);

        mockMvc.perform(get("/api/security/events").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value(1))
                .andExpect(jsonPath("$.totalElements").value(30));

        verify(securityCatalogService).listBlockEvents(1, 20);
    }

    // ==================== 不存在修改/重载端点 ====================

    @Test
    @DisplayName("PUT /api/security/rules → 405（不存在修改入口）")
    void putRulesNotAllowed() throws Exception {
        mockMvc.perform(put("/api/security/rules")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("POST /api/security/rules → 405")
    void postRulesNotAllowed() throws Exception {
        mockMvc.perform(post("/api/security/rules")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("DELETE /api/security/rules → 405")
    void deleteRulesNotAllowed() throws Exception {
        mockMvc.perform(delete("/api/security/rules"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("POST /api/security/events → 405（无新增事件入口）")
    void postEventsNotAllowed() throws Exception {
        mockMvc.perform(post("/api/security/events")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("PUT /api/security/events → 405")
    void putEventsNotAllowed() throws Exception {
        mockMvc.perform(put("/api/security/events")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("DELETE /api/security/events → 405")
    void deleteEventsNotAllowed() throws Exception {
        mockMvc.perform(delete("/api/security/events"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("PUT /api/security/risk-levels → 405")
    void putRiskLevelsNotAllowed() throws Exception {
        mockMvc.perform(put("/api/security/risk-levels")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isMethodNotAllowed());
    }

    // ==================== Service clamp 行为 ====================

    @Test
    @DisplayName("SecurityCatalogService.clampSize — 边界值")
    void clampSizeBoundaryChecks() {
        assertThat(SecurityCatalogService.clampSize(0))
                .isEqualTo(SecurityCatalogService.DEFAULT_PAGE_SIZE);
        assertThat(SecurityCatalogService.clampSize(-5))
                .isEqualTo(SecurityCatalogService.DEFAULT_PAGE_SIZE);
        assertThat(SecurityCatalogService.clampSize(1))
                .isEqualTo(1);
        assertThat(SecurityCatalogService.clampSize(50))
                .isEqualTo(50);
        assertThat(SecurityCatalogService.clampSize(100))
                .isEqualTo(100);
        assertThat(SecurityCatalogService.clampSize(101))
                .isEqualTo(100);
        assertThat(SecurityCatalogService.clampSize(999))
                .isEqualTo(100);
    }

    // ==================== Service 集成（独立单元测试） ====================
    // 注意：完整的 Service→Repository 集成测试写在
    // SecurityCatalogServiceTest（独立测试类，@ExtendWith(MockitoExtension.class)），
    // 本 @WebMvcTest 上下文只覆盖 HTTP 层。
}
