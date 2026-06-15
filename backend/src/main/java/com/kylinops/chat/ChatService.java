package com.kylinops.chat;

import com.kylinops.agent.AgentOrchestrator;
import com.kylinops.agent.AgentOrchestrator.AgentRequest;
import com.kylinops.agent.AgentResult;
import com.kylinops.executor.AuthenticatedOperator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Chat 业务服务
 * <p>
 * 处理 /api/chat/send 请求的核心业务逻辑。
 * 负责会话查找/创建、审计 ID 生成，并委派 AgentOrchestrator 执行业务编排。
 * </p>
 *
 * <h3>处理流程</h3>
 * <ol>
 *   <li>查找或创建会话</li>
 *   <li>生成审计 ID（auditId）</li>
 *   <li>调用 AgentOrchestrator 执行完整编排流程</li>
 *   <li>返回 Agent 执行结果</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final AgentOrchestrator agentOrchestrator;

    /**
     * 处理聊天消息。
     *
     * @param content   用户输入内容
     * @param sessionId 会话 ID（可选，不传则创建新会话）
     * @param operator  已认证操作者身份（用于 L2 动作归属校验）
     * @return Agent 执行结果
     */
    public AgentResult processMessage(String content, String sessionId, AuthenticatedOperator operator) {
        // 1. 生成 auditId
        String auditId = UUID.randomUUID().toString();

        // 2. 构建 Agent 请求
        AgentRequest request = AgentRequest.builder()
                .sessionId(sessionId)
                .userInput(content)
                .requestId(auditId)
                .operator(operator)
                .build();

        // 3. 调用 Agent 主编排器
        AgentResult result = agentOrchestrator.process(request);

        log.info("Chat 请求处理完成: sessionId={}, intent={}, decision={}, auditId={}",
                result.getSessionId(), result.getIntentType(), result.getRiskDecision(), auditId);

        return result;
    }
}
