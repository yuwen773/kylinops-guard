package com.kylinops.agent;

import com.kylinops.agent.AgentOrchestrator.AgentRequest;
import com.kylinops.audit.AuditLogService;
import com.kylinops.chat.Message;
import com.kylinops.chat.MessageRepository;
import com.kylinops.chat.Session;
import com.kylinops.chat.SessionRepository;
import com.kylinops.common.enums.AuditStatus;
import com.kylinops.common.enums.IntentType;
import com.kylinops.common.enums.RiskDecision;
import com.kylinops.common.enums.RiskLevel;
import com.kylinops.executor.ActionConfirmService;
import com.kylinops.security.PromptInjectionDetector;
import com.kylinops.security.RiskCheckResult;
import com.kylinops.security.RiskCheckService;
import com.kylinops.tool.ToolExecutor;
import com.kylinops.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgentOrchestratorSecurityTest {

    @Mock private PromptInjectionDetector injectionDetector;
    @Mock private IntentClassifier intentClassifier;
    @Mock private ToolPlanningService toolPlanningService;
    @Mock private ToolExecutor toolExecutor;
    @Mock private RiskCheckService riskCheckService;
    @Mock private AgentResponseBuilder responseBuilder;
    @Mock private AuditLogService auditLogService;
    @Mock private ActionConfirmService actionConfirmService;
    @Mock private SessionRepository sessionRepository;
    @Mock private MessageRepository messageRepository;

    private AgentOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new AgentOrchestrator(
                injectionDetector, intentClassifier, toolPlanningService, toolExecutor,
                riskCheckService, responseBuilder, auditLogService, actionConfirmService,
                sessionRepository, messageRepository);
        when(injectionDetector.detect(anyString()))
                .thenReturn(PromptInjectionDetector.DetectionResult.builder()
                        .injectionDetected(false)
                        .matchedPatterns(List.of())
                        .riskLevel(RiskLevel.L0)
                        .reason("safe")
                        .build());
        Session session = new Session();
        session.setSessionId("session-1");
        when(sessionRepository.findBySessionId("session-1")).thenReturn(Optional.of(session));
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void passesRequestAuditIdToRiskCheckAndEveryToolExecution() {
        ToolPlanningService.ToolPlan plan = ToolPlanningService.ToolPlan.builder()
                .intent(IntentType.SYSTEM_CHECK)
                .steps(List.of(ToolPlanningService.ToolStep.builder()
                        .toolName("system_info_tool")
                        .params(Map.of())
                        .mode(ToolPlanningService.ExecutionMode.SEQUENTIAL)
                        .order(0)
                        .build()))
                .requiresRiskCheck(false)
                .build();
        when(intentClassifier.classify(anyString())).thenReturn(IntentType.SYSTEM_CHECK);
        when(toolPlanningService.createPlan(eq(IntentType.SYSTEM_CHECK), anyMap())).thenReturn(plan);
        when(riskCheckService.checkPlan(plan, "检查系统", "audit-123"))
                .thenReturn(RiskCheckResult.allow(RiskLevel.L0, "safe"));
        when(toolExecutor.execute("system_info_tool", Map.of(), "audit-123"))
                .thenReturn(ToolResult.success("system_info_tool", Map.of(), "ok", 1));
        when(responseBuilder.build(any(), anyList(), any(), any(), any())).thenReturn("ok");

        AgentResult result = orchestrator.process(AgentRequest.builder()
                .sessionId("session-1")
                .userInput("检查系统")
                .requestId("audit-123")
                .build());

        assertThat(result.getRiskDecision()).isEqualTo("ALLOW");
        verify(riskCheckService).checkPlan(plan, "检查系统", "audit-123");
        verify(toolExecutor).execute("system_info_tool", Map.of(), "audit-123");
    }

    @Test
    void internalExceptionReturnsFailedBlockAndStopsExecution() {
        when(intentClassifier.classify(anyString())).thenThrow(new IllegalStateException("classifier failed"));

        AgentResult result = orchestrator.process(AgentRequest.builder()
                .sessionId("session-1")
                .userInput("检查系统")
                .requestId("audit-failed")
                .build());

        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.L3);
        assertThat(result.getRiskDecision()).isEqualTo("BLOCK");
        assertThat(result.getToolCalls()).isEmpty();
        verify(auditLogService).updateAuditLog(
                eq("audit-failed"), eq(RiskLevel.L3), eq(RiskDecision.BLOCK),
                isNull(), eq(AuditStatus.FAILED), contains("classifier failed"), eq(IntentType.UNKNOWN));
        verifyNoInteractions(toolExecutor, actionConfirmService);
    }
}
