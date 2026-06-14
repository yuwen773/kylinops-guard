package com.kylinops.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * RiskCheckController 单元测试
 */
@WebMvcTest(RiskCheckController.class)
@WithMockUser
@DisplayName("RiskCheckController — 风险校验 API")
class RiskCheckControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private RiskCheckService riskCheckService;

    @Test
    @DisplayName("POST /api/security/risk-check → 返回风险校验结果")
    void riskCheckReturnsResult() throws Exception {
        RiskCheckResult mockResult = RiskCheckResult.builder()
                .riskLevel(com.kylinops.common.enums.RiskLevel.L4)
                .decision(com.kylinops.common.enums.RiskDecision.BLOCK)
                .matchedRules(List.of("block_rm_rf_root"))
                .reason("绝对禁止的根目录递归删除操作")
                .safeSuggestion("该操作可能导致系统损坏或数据丢失，已被系统禁止。")
                .build();

        when(riskCheckService.check(any(RiskEvaluationContext.class), anyString()))
                .thenReturn(mockResult);

        mockMvc.perform(post("/api/security/risk-check")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"command\",\"content\":\"rm -rf /\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.riskLevel").value("L4"))
                .andExpect(jsonPath("$.data.decision").value("BLOCK"))
                .andExpect(jsonPath("$.data.matchedRules[0]").value("block_rm_rf_root"))
                .andExpect(jsonPath("$.data.reason").value("绝对禁止的根目录递归删除操作"))
                .andExpect(jsonPath("$.data.safeSuggestion").isString())
                .andExpect(jsonPath("$.traceId").isString());

        verify(riskCheckService).check(any(RiskEvaluationContext.class),
                org.mockito.ArgumentMatchers.<String>argThat(id -> {
                    try {
                        UUID.fromString(id);
                        return true;
                    } catch (IllegalArgumentException e) {
                        return false;
                    }
                }));
    }

    @Test
    @DisplayName("POST /api/security/risk-check → 缺失 targetType 返回 400")
    void riskCheckMissingTargetType() throws Exception {
        mockMvc.perform(post("/api/security/risk-check")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"df -h\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/security/risk-check → 空白内容返回 400")
    void riskCheckBlankContent() throws Exception {
        mockMvc.perform(post("/api/security/risk-check")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"command\",\"content\":\"\"}"))
                .andExpect(status().isBadRequest());
    }
}
