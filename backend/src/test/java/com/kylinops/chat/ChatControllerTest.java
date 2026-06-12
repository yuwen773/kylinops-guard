package com.kylinops.chat;

import com.kylinops.agent.AgentResult;
import com.kylinops.common.ApiResponse;
import com.kylinops.common.enums.RiskDecision;
import com.kylinops.common.enums.RiskLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ChatController HTTP 层测试
 * <p>
 * 覆盖 F-004：{@code ChatRequest.content} 长度上限 16KB。
 * </p>
 */
@WebMvcTest(ChatController.class)
@DisplayName("ChatController — POST /api/chat/send")
class ChatControllerTest {

    private static final int MAX_CONTENT_LENGTH = 16384;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ChatService chatService;

    /**
     * 构建一个普通的成功 AgentResult（让 mock service 返回，避免深入编排）
     */
    private AgentResult stubAgentResult() {
        return AgentResult.builder()
                .sessionId("session-1")
                .answer("ok")
                .riskLevel(RiskLevel.L0)
                .riskDecision(RiskDecision.ALLOW.name())
                .auditId("audit-1")
                .toolCalls(List.of())
                .build();
    }

    private String buildContent(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append('a');
        }
        return sb.toString();
    }

    private String jsonBody(String content) {
        return "{\"content\":\"" + content + "\"}";
    }

    // ==================== F-004: 长度上限 ====================

    @Test
    @DisplayName("content = 16385 字符 → 400 参数校验失败")
    void chatRequestRejectsContentLargerThan16KB() throws Exception {
        String overLimit = buildContent(MAX_CONTENT_LENGTH + 1);

        mockMvc.perform(post("/api/chat/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody(overLimit)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("16KB")));

        verify(chatService, never()).processMessage(anyString(), anyString());
    }

    @Test
    @DisplayName("content = 16384 字符（边界）→ 200 正常处理")
    void chatRequestAcceptsContentAtLimit() throws Exception {
        String atLimit = buildContent(MAX_CONTENT_LENGTH);
        when(chatService.processMessage(anyString(), any())).thenReturn(stubAgentResult());

        mockMvc.perform(post("/api/chat/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody(atLimit)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.answer").value("ok"));

        verify(chatService).processMessage(org.mockito.ArgumentMatchers.eq(atLimit), any());
    }

    @Test
    @DisplayName("content = 100 字符（普通输入）→ 200 正常处理")
    void chatRequestAcceptsNormalContent() throws Exception {
        String normal = buildContent(100);
        when(chatService.processMessage(anyString(), any())).thenReturn(stubAgentResult());

        mockMvc.perform(post("/api/chat/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody(normal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.answer").value("ok"));

        verify(chatService).processMessage(org.mockito.ArgumentMatchers.eq(normal), any());
    }
}
