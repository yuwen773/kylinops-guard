package com.kylinops.chat;

import com.kylinops.agent.AgentResult;
import com.kylinops.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

/**
 * Chat 控制器
 * <p>
 * 提供 /api/chat/send 端点，接收用户自然语言输入，返回 Agent 结构化响应。
 * 这是麒麟安全智能运维 Agent 的主要入口端点。
 * </p>
 *
 * <h3>安全约束</h3>
 * <ul>
 *   <li>所有输入必然经过 PromptInjectionDetector 检测</li>
 *   <li>写操作必然经过 RiskCheckService 校验</li>
 *   <li>每次请求写入审计日志</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    /**
     * POST /api/chat/send — 发送聊天消息
     * <p>
     * 主入口端点。接收用户自然语言输入，经过完整 Agent 编排流程后返回结构化结果。
     * </p>
     *
     * @param request 聊天请求（content 必填，sessionId 可选）
     * @return Agent 执行结果
     */
    @PostMapping(value = "/send", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<AgentResult> send(@Valid @RequestBody ChatRequest request) {
        log.info("POST /api/chat/send: content='{}', sessionId={}",
                truncate(request.getContent(), 80), request.getSessionId());

        AgentResult result = chatService.processMessage(request.getContent(), request.getSessionId());

        log.info("POST /api/chat/send 响应: sessionId={}, intent={}, decision={}, auditId={}",
                result.getSessionId(), result.getIntentType(), result.getRiskDecision(), result.getAuditId());

        return ApiResponse.success(result);
    }

    /**
     * 截断长字符串用于日志。
     */
    private String truncate(String str, int maxLen) {
        if (str == null) return null;
        return str.length() <= maxLen ? str : str.substring(0, maxLen) + "...";
    }

    // ==================== 请求体 ====================

    /**
     * Chat 请求体
     */
    @Data
    public static class ChatRequest {
        /** 用户输入内容（必填） */
        @NotBlank(message = "消息内容不能为空")
        @Size(max = 16384, message = "单次输入不超过 16KB")
        private String content;

        /** 会话 ID（可选，不传则创建新会话） */
        private String sessionId;
    }
}
