package com.kylinops.audit;

import com.kylinops.agent.AgentOrchestrator;
import com.kylinops.agent.AgentResult;
import com.kylinops.agent.IntentClassifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kylinops.common.enums.AuditStatus;
import com.kylinops.common.enums.IntentType;
import com.kylinops.common.enums.RiskDecision;
import com.kylinops.common.enums.RiskLevel;
import com.kylinops.executor.ActionConfirmService;
import com.kylinops.executor.ExecutionPlan;
import com.kylinops.executor.ExecutionResult;
import com.kylinops.executor.PendingAction;
import com.kylinops.executor.PendingActionRepository;
import com.kylinops.executor.PendingActionStatus;
import com.kylinops.executor.SafeExecutor;
import com.kylinops.security.RiskCheckRecord;
import com.kylinops.security.RiskCheckRecordRepository;
import com.kylinops.tool.ToolCallRecord;
import com.kylinops.tool.ToolCallRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 审计日志集成测试
 * <p>
 * 验证审计链路的强制写入、生命周期状态流转和字段完整性。
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("AuditLog — 审计链路集成测试")
class AuditLogIntegrationTest {

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private AgentOrchestrator orchestrator;

    @Autowired
    private ActionConfirmService actionConfirmService;

    @Autowired
    private RiskCheckRecordRepository riskCheckRecordRepository;

    @Autowired
    private ToolCallRecordRepository toolCallRecordRepository;

    @Autowired
    private PendingActionRepository pendingActionRepository;

    @MockBean
    private SafeExecutor safeExecutor;

    @SpyBean
    private IntentClassifier intentClassifier;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUpExecutor() {
        reset(safeExecutor, intentClassifier);
        when(safeExecutor.execute(any(ExecutionPlan.class)))
                .thenReturn(ExecutionResult.ok(Map.of("restarted", "nginx"), "restart complete"));
    }

    @Test
    @DisplayName("创建审计日志 — 必填字段完整")
    void createAuditLog() {
        String auditId = UUID.randomUUID().toString();
        auditLogService.createAuditLog(auditId, "session-1", "查看系统状态",
                IntentType.SYSTEM_CHECK, AuditStatus.RECEIVED);

        AuditLog found = auditLogRepository.findByAuditId(auditId).orElse(null);
        assertThat(found).isNotNull();
        assertThat(found.getAuditId()).isEqualTo(auditId);
        assertThat(found.getSessionId()).isEqualTo("session-1");
        assertThat(found.getUserInput()).isEqualTo("查看系统状态");
        assertThat(found.getIntentType()).isEqualTo(IntentType.SYSTEM_CHECK);
        assertThat(found.getStatus()).isEqualTo(AuditStatus.RECEIVED);
    }

    @Test
    @DisplayName("审计状态流转：RECEIVED → RISK_CHECKED → SUCCESS")
    void auditLifecycleNormal() {
        String auditId = UUID.randomUUID().toString();

        auditLogService.createAuditLog(auditId, "session-2", "df -h",
                IntentType.DISK_DIAGNOSIS, AuditStatus.RECEIVED);

        auditLogService.updateAuditLog(auditId, RiskLevel.L0, RiskDecision.ALLOW,
                "disk_usage_tool", AuditStatus.RISK_CHECKED, "L0 信息查询");
        assertThat(auditLogRepository.findByAuditId(auditId).get().getStatus())
                .isEqualTo(AuditStatus.RISK_CHECKED);

        auditLogService.markCompleted(auditId);
        assertThat(auditLogRepository.findByAuditId(auditId).get().getStatus())
                .isEqualTo(AuditStatus.SUCCESS);
    }

    @Test
    @DisplayName("审计状态流转：RECEIVED → BLOCKED")
    void auditLifecycleBlocked() {
        String auditId = UUID.randomUUID().toString();

        auditLogService.createAuditLog(auditId, "session-3", "rm -rf /",
                IntentType.COMMAND_EXECUTION, AuditStatus.RECEIVED);

        auditLogService.updateAuditLog(auditId, RiskLevel.L4, RiskDecision.BLOCK,
                null, AuditStatus.BLOCKED, "绝对禁止的根目录递归删除操作");

        assertThat(auditLogRepository.findByAuditId(auditId).get().getStatus())
                .isEqualTo(AuditStatus.BLOCKED);
    }

    @Test
    @DisplayName("审计详情字段写入 — matchedRules, actionPlan, confirmation")
    void auditDetailFields() {
        String auditId = UUID.randomUUID().toString();

        auditLogService.createAuditLog(auditId, "session-4", "重启 nginx",
                IntentType.SERVICE_DIAGNOSIS, AuditStatus.CONFIRM_PENDING);

        auditLogService.updateAuditDetails(auditId, "[\"confirm_service_restart\"]", "restart nginx");
        auditLogService.updateAuditConfirmation(auditId, true, "WAITING");
        auditLogService.updateAuditResult(auditId, "{\"status\":\"confirmed\"}", "服务重启已确认");

        AuditLog found = auditLogRepository.findByAuditId(auditId).get();
        assertThat(found.getMatchedRules()).contains("confirm_service_restart");
        assertThat(found.isConfirmationRequired()).isTrue();
        assertThat(found.getConfirmationStatus()).isEqualTo("WAITING");
        assertThat(found.getExecutionResult()).contains("confirmed");
    }

    @Test
    @DisplayName("审计警告 — 追加不覆盖")
    void auditWarningAppends() {
        String auditId = UUID.randomUUID().toString();

        auditLogService.createAuditLog(auditId, "session-5", "test",
                IntentType.UNKNOWN, AuditStatus.RECEIVED);

        auditLogService.addWarning(auditId, "第一个警告");
        auditLogService.addWarning(auditId, "第二个警告");

        AuditLog found = auditLogRepository.findByAuditId(auditId).get();
        assertThat(found.getWarning()).contains("第一个警告");
        assertThat(found.getWarning()).contains("第二个警告");
    }

    @Test
    @DisplayName("正常巡检通过真实 Agent 链路共享请求 auditId")
    void normalInspectionUsesRequestAuditIdAcrossDatabaseRecords() {
        String auditId = UUID.randomUUID().toString();

        AgentResult result = orchestrator.process(AgentOrchestrator.AgentRequest.builder()
                .sessionId(UUID.randomUUID().toString())
                .userInput("检查系统状态")
                .requestId(auditId)
                .build());

        AuditLog found = auditLogRepository.findByAuditId(auditId).get();
        List<RiskCheckRecord> riskChecks = riskCheckRecordRepository.findByAuditId(auditId);
        List<ToolCallRecord> toolCalls = toolCallRecordRepository.findByAuditId(auditId);

        assertThat(result.getAuditId()).isEqualTo(auditId);
        assertThat(found.getStatus()).isIn(AuditStatus.SUCCESS, AuditStatus.FAILED);
        assertThat(found.getRiskLevel()).isEqualTo(RiskLevel.L0);
        assertThat(found.getRiskDecision()).isEqualTo(RiskDecision.ALLOW);
        assertThat(riskChecks).isNotEmpty().allMatch(record -> auditId.equals(record.getAuditId()));
        assertThat(toolCalls).hasSize(6).allMatch(record -> auditId.equals(record.getAuditId()));
    }

    @Test
    @DisplayName("L2 重启通过真实 Agent 和确认服务共享原始 auditId")
    void confirmedRestartUsesOriginalAuditIdAcrossDatabaseRecords() {
        String auditId = UUID.randomUUID().toString();

        AgentResult planned = orchestrator.process(AgentOrchestrator.AgentRequest.builder()
                .sessionId(UUID.randomUUID().toString())
                .userInput("重启 nginx 服务")
                .requestId(auditId)
                .build());

        assertThat(planned.getRiskDecision()).isEqualTo("CONFIRM");
        assertThat(planned.getToolCalls()).isEmpty();
        verifyNoInteractions(safeExecutor);

        PendingAction waiting = pendingActionRepository.findByAuditId(auditId).get(0);
        assertThat(waiting.getStatus()).isEqualTo(PendingActionStatus.WAITING);
        assertThat(waiting.getAuditId()).isEqualTo(auditId);

        PendingAction executed = actionConfirmService.confirmAction(waiting.getActionId(), true);

        assertThat(executed.getStatus()).isEqualTo(PendingActionStatus.SUCCESS);
        assertThat(executed.getExecutionResult()).contains("restart complete");
        verify(safeExecutor, times(1)).execute(any(ExecutionPlan.class));

        AuditLog audit = auditLogRepository.findByAuditId(auditId).orElseThrow();
        List<RiskCheckRecord> riskChecks = riskCheckRecordRepository.findByAuditId(auditId);
        assertThat(audit.getStatus()).isEqualTo(AuditStatus.SUCCESS);
        assertThat(audit.getConfirmationStatus()).isEqualTo(PendingActionStatus.SUCCESS.name());
        assertThat(riskChecks).hasSizeGreaterThanOrEqualTo(2)
                .allMatch(record -> auditId.equals(record.getAuditId()));
        assertThat(pendingActionRepository.findByAuditId(auditId))
                .allMatch(action -> auditId.equals(action.getAuditId()));
    }

    @Test
    @DisplayName("危险命令通过真实 Agent 链路阻断且无工具或动作记录")
    void dangerousCommandIsBlockedWithoutExecutionRecords() {
        String auditId = UUID.randomUUID().toString();

        AgentResult result = orchestrator.process(AgentOrchestrator.AgentRequest.builder()
                .sessionId(UUID.randomUUID().toString())
                .userInput("rm -rf /")
                .requestId(auditId)
                .build());

        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.L4);
        assertThat(result.getRiskDecision()).isEqualTo("BLOCK");
        assertThat(toolCallRecordRepository.findByAuditId(auditId)).isEmpty();
        assertThat(pendingActionRepository.findByAuditId(auditId)).isEmpty();
        assertThat(riskCheckRecordRepository.findByAuditId(auditId))
                .isNotEmpty()
                .allMatch(record -> auditId.equals(record.getAuditId()));
        verifyNoInteractions(safeExecutor);
    }

    @Test
    @DisplayName("Agent 内部异常持久化 FAILED/BLOCK 且不执行")
    void internalAgentFailurePersistsFailedBlockAndStopsExecution() {
        String auditId = UUID.randomUUID().toString();
        doThrow(new IllegalStateException("classifier failed"))
                .when(intentClassifier).classify("检查系统状态");

        AgentResult result = orchestrator.process(AgentOrchestrator.AgentRequest.builder()
                .sessionId(UUID.randomUUID().toString())
                .userInput("检查系统状态")
                .requestId(auditId)
                .build());

        AuditLog audit = auditLogRepository.findByAuditId(auditId).orElseThrow();
        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.L3);
        assertThat(result.getRiskDecision()).isEqualTo("BLOCK");
        assertThat(audit.getStatus()).isEqualTo(AuditStatus.FAILED);
        assertThat(audit.getRiskLevel()).isEqualTo(RiskLevel.L3);
        assertThat(audit.getRiskDecision()).isEqualTo(RiskDecision.BLOCK);
        assertThat(toolCallRecordRepository.findByAuditId(auditId)).isEmpty();
        assertThat(pendingActionRepository.findByAuditId(auditId)).isEmpty();
        verifyNoInteractions(safeExecutor);
    }

    @Test
    @DisplayName("独立风险 API 返回并持久化同一 auditId")
    void standaloneRiskCheckReturnsPersistedAuditId() throws Exception {
        String body = mockMvc.perform(post("/api/security/risk-check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"command\",\"content\":\"rm -rf /\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode response = objectMapper.readTree(body);
        String auditId = response.path("traceId").asText();
        assertThat(auditId).isNotBlank();
        assertThat(riskCheckRecordRepository.findByAuditId(auditId))
                .isNotEmpty()
                .allMatch(record -> auditId.equals(record.getAuditId()));
    }
}
