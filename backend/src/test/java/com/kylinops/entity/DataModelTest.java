package com.kylinops.entity;

import com.kylinops.audit.AuditLog;
import com.kylinops.audit.AuditLogRepository;
import com.kylinops.chat.Message;
import com.kylinops.chat.MessageRepository;
import com.kylinops.chat.Session;
import com.kylinops.chat.SessionRepository;
import com.kylinops.common.enums.AuditStatus;
import com.kylinops.common.enums.IntentType;
import com.kylinops.common.enums.PermissionType;
import com.kylinops.common.enums.RiskDecision;
import com.kylinops.common.enums.RiskLevel;
import com.kylinops.common.enums.ToolCallStatus;
import com.kylinops.common.enums.ToolStatus;
import com.kylinops.report.Report;
import com.kylinops.report.ReportRepository;
import com.kylinops.report.ReportType;
import com.kylinops.security.RiskCheckRecord;
import com.kylinops.security.RiskCheckRecordRepository;
import com.kylinops.tool.ToolCallRecord;
import com.kylinops.tool.ToolCallRecordRepository;
import com.kylinops.tool.ToolDefinition;
import com.kylinops.tool.ToolDefinitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 核心数据模型与数据库 — 综合测试
 * <p>
 * 覆盖 7 个 JPA 实体的 CRUD、关联关系、级联操作、auditId 全链路追踪。
 * </p>
 */
@DataJpaTest
@DisplayName("Task 03 — 核心数据模型与数据库")
class DataModelTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private ToolDefinitionRepository toolDefinitionRepository;

    @Autowired
    private ToolCallRecordRepository toolCallRecordRepository;

    @Autowired
    private RiskCheckRecordRepository riskCheckRecordRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private ReportRepository reportRepository;

    // ———————— 通用审计 ID（全链路追踪用） ————————
    private final String auditId = java.util.UUID.randomUUID().toString();

    // ———————— 1. Session ————————

    @Test
    @DisplayName("创建和查询会话")
    void shouldSaveAndFindSession() {
        // given
        Session session = new Session();
        session.setTitle("系统健康检查");

        // when
        Session saved = sessionRepository.save(session);

        // then
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getSessionId()).isNotNull();
        assertThat(saved.getTitle()).isEqualTo("系统健康检查");
        assertThat(saved.getStatus()).isEqualTo("ACTIVE");
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();

        // 按 sessionId 查询
        Optional<Session> found = sessionRepository.findBySessionId(saved.getSessionId());
        assertThat(found).isPresent();
        assertThat(found.get().getTitle()).isEqualTo("系统健康检查");
    }

    @Test
    @DisplayName("查询活跃会话")
    void shouldFindActiveSessions() {
        // given
        Session active = new Session();
        active.setTitle("活跃会话");
        sessionRepository.save(active);

        Session closed = new Session();
        closed.setTitle("已关闭会话");
        closed.setStatus("CLOSED");
        sessionRepository.save(closed);

        // when
        List<Session> activeSessions = sessionRepository.findByStatusOrderByUpdatedAtDesc("ACTIVE");

        // then
        assertThat(activeSessions).hasSize(1);
        assertThat(activeSessions.get(0).getTitle()).isEqualTo("活跃会话");
    }

    // ———————— 2. Message ————————

    @Test
    @DisplayName("创建消息并与会话关联")
    void shouldSaveMessageWithSession() {
        // given
        Session session = new Session();
        session.setTitle("测试会话");
        em.persist(session);

        Message message = new Message();
        message.setSession(session);
        message.setRole("user");
        message.setContent("帮我检查系统健康状态");
        message.setIntentType(IntentType.SYSTEM_CHECK);
        message.setAuditId(auditId);

        // when
        Message saved = messageRepository.save(message);

        // then
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getMessageId()).isNotNull();
        assertThat(saved.getSession().getId()).isEqualTo(session.getId());
        assertThat(saved.getRole()).isEqualTo("user");
        assertThat(saved.getIntentType()).isEqualTo(IntentType.SYSTEM_CHECK);
        assertThat(saved.getAuditId()).isEqualTo(auditId);

        // 验证外键关联可追溯
        var found = messageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId());
        assertThat(found).hasSize(1);
    }

    @Test
    @DisplayName("查询某会话的所有消息")
    void shouldFindMessagesBySession() {
        // given
        Session session = new Session();
        em.persist(session);

        Message m1 = new Message();
        m1.setSession(session);
        m1.setRole("user");
        m1.setContent("第一条消息");
        em.persist(m1);

        Message m2 = new Message();
        m2.setSession(session);
        m2.setRole("assistant");
        m2.setContent("第二条消息");
        em.persist(m2);

        // when
        var messages = messageRepository.findBySessionSessionIdOrderByCreatedAtAsc(session.getSessionId());

        // then
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).getContent()).isEqualTo("第一条消息");
    }

    // ———————— 3. ToolDefinition ————————

    @Test
    @DisplayName("创建和查询工具定义")
    void shouldSaveAndFindToolDefinition() {
        // given
        ToolDefinition td = new ToolDefinition();
        td.setToolName("disk_usage_tool");
        td.setDescription("查看磁盘使用情况");
        td.setInputSchema("{\"type\": \"object\", \"properties\": {}}");
        td.setOutputSchema("{\"type\": \"object\", \"properties\": {}}");
        td.setRiskLevel(RiskLevel.L0);
        td.setPermissionType(PermissionType.READ);
        td.setToolStatus(ToolStatus.ENABLED);
        td.setTimeoutMs(5000L);
        td.setAuditRequired(false);

        // when
        ToolDefinition saved = toolDefinitionRepository.save(td);

        // then
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getToolName()).isEqualTo("disk_usage_tool");
        assertThat(saved.getRiskLevel()).isEqualTo(RiskLevel.L0);
        assertThat(saved.getPermissionType()).isEqualTo(PermissionType.READ);
        assertThat(saved.isAuditRequired()).isFalse();

        // 按名称查询
        Optional<ToolDefinition> found = toolDefinitionRepository.findByToolName("disk_usage_tool");
        assertThat(found).isPresent();

        // 按状态查询
        var enabledTools = toolDefinitionRepository.findByToolStatus(ToolStatus.ENABLED);
        assertThat(enabledTools).hasSize(1);
    }

    // ———————— 4. ToolCallRecord + RiskCheckRecord (外键关联) ————————

    @Test
    @DisplayName("创建工具调用记录和风险校验记录（一对一关联）")
    void shouldSaveToolCallAndRiskCheck() {
        // given — 先建会话和消息
        Session session = new Session();
        em.persist(session);

        Message message = new Message();
        message.setSession(session);
        message.setRole("user");
        message.setContent("检查磁盘");
        em.persist(message);

        // 工具调用记录
        ToolCallRecord tcr = new ToolCallRecord();
        tcr.setMessage(message);
        tcr.setToolName("disk_usage_tool");
        tcr.setInput("{\"path\": \"/\"}");
        tcr.setOutput("{\"usage\": \"86%\"}");
        tcr.setStatus(ToolCallStatus.SUCCESS);
        tcr.setDurationMs(150L);
        tcr.setAuditId(auditId);
        em.persist(tcr);

        // 风险校验记录（一对一关联 ToolCallRecord）
        RiskCheckRecord rcr = new RiskCheckRecord();
        rcr.setToolCallRecord(tcr);
        rcr.setRiskLevel(RiskLevel.L0);
        rcr.setRiskDecision(RiskDecision.ALLOW);
        rcr.setMatchedRules("[]");
        rcr.setReason("L0 信息查询，直接允许");
        rcr.setSafeSuggestion("无");
        rcr.setAuditId(auditId);
        RiskCheckRecord savedRcr = riskCheckRecordRepository.save(rcr);

        // 维护双向关联关系
        tcr.setRiskCheckRecord(savedRcr);

        // then — 验证双向关联
        assertThat(savedRcr.getId()).isNotNull();
        assertThat(savedRcr.getRiskCheckId()).isNotNull();
        assertThat(savedRcr.getToolCallRecord().getId()).isEqualTo(tcr.getId());
        assertThat(savedRcr.getToolCallRecord().getToolName()).isEqualTo("disk_usage_tool");

        // 验证 ToolCallRecord 可反向获取 RiskCheckRecord
        Optional<ToolCallRecord> tcrFound = toolCallRecordRepository.findByToolCallId(tcr.getToolCallId());
        assertThat(tcrFound).isPresent();
        assertThat(tcrFound.get().getRiskCheckRecord()).isNotNull();
        assertThat(tcrFound.get().getRiskCheckRecord().getRiskDecision()).isEqualTo(RiskDecision.ALLOW);
    }

    @Test
    @DisplayName("查询被阻断的工具调用记录")
    void shouldFindBlockedToolCalls() {
        // given
        Session session = new Session();
        em.persist(session);

        Message message = new Message();
        message.setSession(session);
        message.setRole("user");
        message.setContent("rm -rf /");
        em.persist(message);

        ToolCallRecord tcr = new ToolCallRecord();
        tcr.setMessage(message);
        tcr.setToolName("system_info_tool");
        tcr.setStatus(ToolCallStatus.BLOCKED);
        tcr.setErrorMessage("L4 严重风险 — 绝对阻断");
        tcr.setAuditId(auditId);
        toolCallRecordRepository.save(tcr);

        // when
        var blocked = toolCallRecordRepository.findByStatus(ToolCallStatus.BLOCKED);

        // then
        assertThat(blocked).hasSize(1);
        assertThat(blocked.get(0).getErrorMessage()).contains("阻断");
    }

    // ———————— 5. AuditLog ————————

    @Test
    @DisplayName("创建和查询审计日志")
    void shouldSaveAndFindAuditLog() {
        // given
        AuditLog log = new AuditLog();
        log.setAuditId(auditId);
        log.setSessionId("test-session-uuid");
        log.setUserInput("帮我检查磁盘状态");
        log.setIntentType(IntentType.DISK_DIAGNOSIS);
        log.setToolName("disk_usage_tool");
        log.setRiskLevel(RiskLevel.L0);
        log.setRiskDecision(RiskDecision.ALLOW);
        log.setStatus(AuditStatus.SUCCESS);
        log.setMessage("L0 信息查询，直接允许");
        log.setDurationMs(200L);

        // when
        AuditLog saved = auditLogRepository.save(log);

        // then
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getAuditId()).isEqualTo(auditId);
        assertThat(saved.getStatus()).isEqualTo(AuditStatus.SUCCESS);

        // 按 auditId 查询
        Optional<AuditLog> found = auditLogRepository.findByAuditId(auditId);
        assertThat(found).isPresent();
        assertThat(found.get().getUserInput()).contains("磁盘");

        // 按状态查询
        var successLogs = auditLogRepository.findByStatusOrderByCreatedAtDesc(AuditStatus.SUCCESS);
        assertThat(successLogs).isNotEmpty();
    }

    @Test
    @DisplayName("按风险等级和风险决策查询审计日志")
    void shouldFilterAuditLogsByRisk() {
        // given
        AuditLog allowed = new AuditLog();
        allowed.setAuditId(java.util.UUID.randomUUID().toString());
        allowed.setUserInput("查看系统信息");
        allowed.setStatus(AuditStatus.SUCCESS);
        allowed.setRiskLevel(RiskLevel.L0);
        allowed.setRiskDecision(RiskDecision.ALLOW);
        auditLogRepository.save(allowed);

        AuditLog blocked = new AuditLog();
        blocked.setAuditId(java.util.UUID.randomUUID().toString());
        blocked.setUserInput("rm -rf /");
        blocked.setStatus(AuditStatus.BLOCKED);
        blocked.setRiskLevel(RiskLevel.L4);
        blocked.setRiskDecision(RiskDecision.BLOCK);
        auditLogRepository.save(blocked);

        // when
        var l0Logs = auditLogRepository.findByRiskLevelOrderByCreatedAtDesc(RiskLevel.L0);
        var blockedLogs = auditLogRepository.findByRiskDecisionOrderByCreatedAtDesc(RiskDecision.BLOCK);

        // then
        assertThat(l0Logs).hasSize(1);
        assertThat(l0Logs.get(0).getUserInput()).isEqualTo("查看系统信息");
        assertThat(blockedLogs).hasSize(1);
        assertThat(blockedLogs.get(0).getUserInput()).contains("rm -rf");
    }

    @Test
    @DisplayName("按时间范围和关键词筛选审计日志")
    void shouldSearchAuditLogsByTimeAndKeyword() {
        // given
        AuditLog log = new AuditLog();
        log.setAuditId(auditId);
        log.setUserInput("帮我重启 nginx");
        log.setIntentType(IntentType.SERVICE_DIAGNOSIS);
        log.setRiskLevel(RiskLevel.L2);
        log.setRiskDecision(RiskDecision.CONFIRM);
        log.setStatus(AuditStatus.CONFIRM_PENDING);
        log.setMessage("L2 中等风险，需用户确认");
        auditLogRepository.save(log);

        // when — 时间范围（今天之内）
        LocalDateTime todayStart = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0);
        LocalDateTime todayEnd = todayStart.plusDays(1);
        var timeRangeLogs = auditLogRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(todayStart, todayEnd);

        // then
        assertThat(timeRangeLogs).isNotEmpty();

        // 关键词搜索
        var keywordLogs = auditLogRepository.findByUserInputContainingIgnoreCase("nginx");
        assertThat(keywordLogs).hasSize(1);
    }

    // ———————— 6. Report ————————

    @Test
    @DisplayName("创建和查询报告")
    void shouldSaveAndFindReport() {
        // given
        Report report = new Report();
        report.setReportId(java.util.UUID.randomUUID().toString());
        report.setSessionId("test-session-uuid");
        report.setAuditId("test-audit-uuid");
        report.setReportType(ReportType.HEALTH);
        report.setTitle("系统健康检查报告");
        report.setRiskLevel(RiskLevel.L0);
        report.setBodyMarkdown("# 系统健康检查报告\n\n## 概述\n系统运行正常。");

        // when
        Report saved = reportRepository.save(report);

        // then
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getReportId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getReportType()).isEqualTo(ReportType.HEALTH);

        // 按 reportId 查询
        Optional<Report> found = reportRepository.findByReportId(saved.getReportId());
        assertThat(found).isPresent();
        assertThat(found.get().getTitle()).contains("健康检查");
        assertThat(found.get().getBodyMarkdown()).contains("系统运行正常");
    }

    // ———————— 7. 全链路审计追踪 ————————

    @Test
    @DisplayName("AuditId 贯穿全链路追踪")
    void shouldTraceAuditIdAcrossFullChain() {
        String sharedAuditId = java.util.UUID.randomUUID().toString();

        // 1. 创建 Session
        Session session = new Session();
        session.setTitle("全链路追踪测试");
        em.persist(session);

        // 2. 创建 Message
        Message message = new Message();
        message.setSession(session);
        message.setRole("user");
        message.setContent("检查磁盘使用情况");
        message.setIntentType(IntentType.DISK_DIAGNOSIS);
        message.setAuditId(sharedAuditId);
        em.persist(message);

        // 3. 创建 ToolCallRecord
        ToolCallRecord tcr = new ToolCallRecord();
        tcr.setMessage(message);
        tcr.setToolName("disk_usage_tool");
        tcr.setInput("{\"path\": \"/\"}");
        tcr.setOutput("{\"usage\": \"86%\"}");
        tcr.setStatus(ToolCallStatus.SUCCESS);
        tcr.setDurationMs(120L);
        tcr.setAuditId(sharedAuditId);
        em.persist(tcr);

        // 4. 创建 RiskCheckRecord
        RiskCheckRecord rcr = new RiskCheckRecord();
        rcr.setToolCallRecord(tcr);
        rcr.setRiskLevel(RiskLevel.L0);
        rcr.setRiskDecision(RiskDecision.ALLOW);
        rcr.setMatchedRules("[]");
        rcr.setReason("L0 查询，无风险");
        rcr.setAuditId(sharedAuditId);
        em.persist(rcr);

        // 5. 创建 AuditLog
        AuditLog auditLog = new AuditLog();
        auditLog.setAuditId(sharedAuditId);
        auditLog.setSessionId(session.getSessionId());
        auditLog.setUserInput("检查磁盘使用情况");
        auditLog.setIntentType(IntentType.DISK_DIAGNOSIS);
        auditLog.setToolName("disk_usage_tool");
        auditLog.setRiskLevel(RiskLevel.L0);
        auditLog.setRiskDecision(RiskDecision.ALLOW);
        auditLog.setStatus(AuditStatus.SUCCESS);
        auditLog.setDurationMs(120L);
        em.persist(auditLog);

        // 验证：所有实体均可通过 auditId 追溯
        assertThat(messageRepository.findByAuditId(sharedAuditId)).isPresent();
        assertThat(toolCallRecordRepository.findByAuditId(sharedAuditId)).hasSize(1);
        assertThat(riskCheckRecordRepository.findByAuditId(sharedAuditId)).hasSize(1);
        assertThat(auditLogRepository.findByAuditId(sharedAuditId)).isPresent();
    }

    // ———————— 8. 级联删除 ————————

    @Test
    @DisplayName("删除会话时级联删除关联消息")
    void shouldCascadeDeleteSessionAndMessages() {
        // given — 通过 session 的消息列表级联持久化
        Session session = new Session();

        Message m1 = new Message();
        m1.setSession(session);
        m1.setRole("user");
        m1.setContent("消息1");

        Message m2 = new Message();
        m2.setSession(session);
        m2.setRole("assistant");
        m2.setContent("消息2");

        // 将消息添加到会话列表以启用级联
        session.getMessages().add(m1);
        session.getMessages().add(m2);

        // 级联持久化（CascadeType.ALL 会同时保存消息）
        em.persist(session);
        em.flush();

        Long sessionId = session.getId();
        assertThat(messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)).hasSize(2);

        // when — 级联删除 session
        sessionRepository.delete(session);
        em.flush();

        // then — 消息也应被级联删除
        assertThat(sessionRepository.findById(sessionId)).isEmpty();
        assertThat(messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)).isEmpty();
    }

    // ———————— 9. ToolDefinition 唯一约束 ————————

    @Test
    @DisplayName("工具名称具有唯一约束")
    void shouldEnforceUniqueToolName() {
        // given
        ToolDefinition td1 = new ToolDefinition();
        td1.setToolName("unique_tool");
        td1.setDescription("第一个工具");
        td1.setRiskLevel(RiskLevel.L0);
        td1.setPermissionType(PermissionType.READ);
        td1.setToolStatus(ToolStatus.ENABLED);
        toolDefinitionRepository.save(td1);

        // when — 尝试保存同名工具
        ToolDefinition td2 = new ToolDefinition();
        td2.setToolName("unique_tool");
        td2.setDescription("重复工具");
        td2.setRiskLevel(RiskLevel.L0);
        td2.setPermissionType(PermissionType.READ);
        td2.setToolStatus(ToolStatus.ENABLED);

        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () -> toolDefinitionRepository.saveAndFlush(td2)
        );
    }

    // ———————— 10. 默认值 ————————

    @Test
    @DisplayName("实体默认值正确初始化")
    void shouldInitializeDefaultValues() {
        // Session 默认 status = "ACTIVE"
        Session session = new Session();
        Session savedSession = sessionRepository.save(session);
        assertThat(savedSession.getStatus()).isEqualTo("ACTIVE");

        // ToolDefinition 默认 toolStatus = ENABLED, auditRequired = true, timeoutMs = 3000
        ToolDefinition td = new ToolDefinition();
        td.setToolName("default_test_tool");
        td.setDescription("测试默认值");
        td.setRiskLevel(RiskLevel.L0);
        td.setPermissionType(PermissionType.READ);
        ToolDefinition savedTd = toolDefinitionRepository.save(td);
        assertThat(savedTd.getToolStatus()).isEqualTo(ToolStatus.ENABLED);
        assertThat(savedTd.isAuditRequired()).isTrue();
        assertThat(savedTd.getTimeoutMs()).isEqualTo(3000L);
    }
}
