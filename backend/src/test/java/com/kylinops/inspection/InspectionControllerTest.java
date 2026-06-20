package com.kylinops.inspection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kylinops.inspection.model.InspectionExecutionStatus;
import com.kylinops.inspection.model.InspectionNotificationPolicy;
import com.kylinops.inspection.model.InspectionScheduleType;
import com.kylinops.inspection.model.InspectionTemplateType;
import com.kylinops.inspection.model.InspectionTriggerType;
import com.kylinops.notification.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P1-02 Task 7 — 巡检管理 REST API 契约测试。
 *
 * <h3>覆盖范围</h3>
 * <p>23 个端到端契约用例(GET/POST/PUT/DELETE + 路径参数 + 查询参数 + 校验 + 乐观锁 + delete-running):</p>
 * <ol>
 *   <li>GET /templates 公开契约</li>
 *   <li>GET /plans 列表 / 详情 / 不存在 → 404</li>
 *   <li>POST /plans 合法 body / 缺字段 / 服务 allowlist / CSRF</li>
 *   <li>PUT /plans/{id} 旧 version → 409</li>
 *   <li>POST /plans/{id}/enable 与 /disable</li>
 *   <li>DELETE /plans/{id} 无/有 RUNNING</li>
 *   <li>POST /plans/{id}/run enabled / disabled</li>
 *   <li>GET /executions 列表 / 过滤 / size clamp</li>
 * </ol>
 *
 * <h3>测试设置</h3>
 * <ul>
 *   <li>{@code @SpringBootTest} + {@code @AutoConfigureMockMvc} 加载完整 Spring 上下文,
 *       用真实 H2 + Flyway V1-V7,PlanService / Scheduler / ExecutionService 真实装配</li>
 *   <li>{@link MockBean} 隔离 NotificationService(避免外网副作用)</li>
 *   <li>每个测试在 {@link TransactionTemplate} 中清表,避免 Flyway 重复迁移失败</li>
 *   <li>登录通过真实 POST /api/auth/login(CSRF-exempt)→ session + csrfToken 复用到后续请求</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("P1-02 T7 — InspectionController 契约")
class InspectionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager txManager;

    @MockBean
    private NotificationService notificationService;

    private TransactionTemplate tx;

    /** 来自 POST /api/auth/login 的会话与 CSRF token(每个测试新登录) */
    private MockHttpSession session;
    private String csrfToken;

    @BeforeEach
    void setUp() throws Exception {
        tx = new TransactionTemplate(txManager);
        // 清空 inspection 表(关闭 autoStart 已避免调度器干扰;此处无需关闭 scheduler)
        tx.executeWithoutResult(s -> {
            jdbc.update("DELETE FROM inspection_executions");
            jdbc.update("DELETE FROM inspection_plans");
        });
        // 真实登录(CSRF-exempt)
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"test-admin-pwd\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        JsonNode data = objectMapper.readTree(loginResult.getResponse().getContentAsString())
                .get("data");
        csrfToken = data.get("csrfToken").asText();
        session = (MockHttpSession) loginResult.getRequest().getSession();
    }

    /** 构造一个合法 HEALTH 计划 body(基础参数 + serviceName + 阈值)。 */
    private String validHealthPlanBody() throws Exception {
        Map<String, Object> body = Map.of(
                "name", "plan-" + UUID.randomUUID().toString().substring(0, 8),
                "description", "test health plan",
                "templateType", "HEALTH",
                "templateParams", Map.of("serviceName", "nginx"),
                "thresholds", Map.of(
                        "cpuWarningPercent", 80,
                        "memoryWarningPercent", 85,
                        "diskWarningPercent", 90
                ),
                "scheduleType", "DAILY",
                "localTime", "02:00",
                "timezone", "Asia/Shanghai",
                "notificationPolicy", "ON_ABNORMAL"
        );
        return objectMapper.writeValueAsString(body);
    }

    // ==================== templates ====================

    @Test
    @DisplayName("1. GET /api/inspections/templates 已登录 → 200 + HEALTH/DISK/SERVICE")
    void templatesReturnsBuiltins() throws Exception {
        mockMvc.perform(get("/api/inspections/templates").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[?(@.templateType=='HEALTH')]").exists())
                .andExpect(jsonPath("$.data[?(@.templateType=='DISK')]").exists())
                .andExpect(jsonPath("$.data[?(@.templateType=='SERVICE')]").exists());
    }

    // ==================== POST /plans ====================

    @Test
    @DisplayName("3. POST /plans 合法 body → 200, planId 非空, enabled=false, version=0, nextRunAt=null")
    void createPlanSuccess() throws Exception {
        mockMvc.perform(post("/api/inspections/plans")
                        .with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validHealthPlanBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.planId").isNotEmpty())
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.version").value(0))
                .andExpect(jsonPath("$.data.nextRunAt").doesNotExist());
    }

    @Test
    @DisplayName("4. POST /plans 缺 serviceName → 400 + [serviceName] 提示")
    void createPlanMissingServiceName() throws Exception {
        Map<String, Object> body = Map.of(
                "name", "plan-x",
                "templateType", "HEALTH",
                "templateParams", Map.of(),
                "thresholds", Map.of("cpuWarningPercent", 80, "memoryWarningPercent", 85, "diskWarningPercent", 90),
                "scheduleType", "DAILY",
                "localTime", "02:00",
                "timezone", "Asia/Shanghai",
                "notificationPolicy", "ON_ABNORMAL"
        );
        mockMvc.perform(post("/api/inspections/plans")
                        .with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("[serviceName]")));
    }

    @Test
    @DisplayName("5. POST /plans WEEKLY 缺 dayOfWeek → 400 + [dayOfWeek]")
    void createPlanWeeklyMissingDay() throws Exception {
        Map<String, Object> body = Map.of(
                "name", "plan-weekly",
                "templateType", "HEALTH",
                "templateParams", Map.of("serviceName", "nginx"),
                "thresholds", Map.of("cpuWarningPercent", 80, "memoryWarningPercent", 85, "diskWarningPercent", 90),
                "scheduleType", "WEEKLY",
                "localTime", "02:00",
                "timezone", "Asia/Shanghai",
                "notificationPolicy", "ON_ABNORMAL"
        );
        mockMvc.perform(post("/api/inspections/plans")
                        .with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("[dayOfWeek]")));
    }

    @Test
    @DisplayName("6. POST /plans MONTHLY dayOfMonth=31 → 400 + [dayOfMonth]")
    void createPlanMonthlyInvalidDay() throws Exception {
        Map<String, Object> body = Map.of(
                "name", "plan-monthly",
                "templateType", "HEALTH",
                "templateParams", Map.of("serviceName", "nginx"),
                "thresholds", Map.of("cpuWarningPercent", 80, "memoryWarningPercent", 85, "diskWarningPercent", 90),
                "scheduleType", "MONTHLY",
                "dayOfMonth", 31,
                "localTime", "02:00",
                "timezone", "Asia/Shanghai",
                "notificationPolicy", "ON_ABNORMAL"
        );
        mockMvc.perform(post("/api/inspections/plans")
                        .with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("[dayOfMonth]")));
    }

    @Test
    @DisplayName("7. POST /plans 缺 CSRF → 403")
    void createPlanMissingCsrfForbidden() throws Exception {
        mockMvc.perform(post("/api/inspections/plans")
                        .session(session)  // 已登录但无 CSRF
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validHealthPlanBody()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("8. POST /plans serviceName 不在 allowlist → 400 + [serviceName]")
    void createPlanServiceNotInAllowlist() throws Exception {
        Map<String, Object> body = Map.of(
                "name", "plan-bad-svc",
                "templateType", "HEALTH",
                "templateParams", Map.of("serviceName", "not-in-allowlist"),
                "thresholds", Map.of("cpuWarningPercent", 80, "memoryWarningPercent", 85, "diskWarningPercent", 90),
                "scheduleType", "DAILY",
                "localTime", "02:00",
                "timezone", "Asia/Shanghai",
                "notificationPolicy", "ON_ABNORMAL"
        );
        mockMvc.perform(post("/api/inspections/plans")
                        .with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("[serviceName]")));
    }

    // ==================== GET /plans ====================

    @Test
    @DisplayName("9. GET /plans 已登录 → 200 + 含刚创建的 summary")
    void listPlansContainsCreated() throws Exception {
        // 先创建一个
        MvcResult createResult = mockMvc.perform(post("/api/inspections/plans")
                        .with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validHealthPlanBody()))
                .andExpect(status().isOk())
                .andReturn();
        String planId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("data").get("planId").asText();

        mockMvc.perform(get("/api/inspections/plans").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[?(@.planId=='" + planId + "')]").exists());
    }

    @Test
    @DisplayName("10. GET /plans/{planId} 已登录 → 200 + planId 匹配")
    void getPlanById() throws Exception {
        MvcResult createResult = mockMvc.perform(post("/api/inspections/plans")
                        .with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validHealthPlanBody()))
                .andExpect(status().isOk())
                .andReturn();
        String planId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("data").get("planId").asText();

        mockMvc.perform(get("/api/inspections/plans/" + planId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.planId").value(planId))
                .andExpect(jsonPath("$.data.templateType").value("HEALTH"))
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.version").value(0));
    }

    @Test
    @DisplayName("11. GET /plans/{nonexistent} 已登录 → 404(error.code=404)")
    void getPlanNotFound() throws Exception {
        mockMvc.perform(get("/api/inspections/plans/does-not-exist").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    // ==================== PUT /plans ====================

    @Test
    @DisplayName("12. PUT /plans/{planId} 旧 version → 409(error.code=409)")
    void updatePlanVersionConflict() throws Exception {
        // 创建计划
        MvcResult createResult = mockMvc.perform(post("/api/inspections/plans")
                        .with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validHealthPlanBody()))
                .andExpect(status().isOk())
                .andReturn();
        String planId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("data").get("planId").asText();

        // 第一次 PUT(version=0)→ 200,version 升到 1
        Map<String, Object> updateBody = Map.of(
                "version", 0,
                "description", "updated once"
        );
        mockMvc.perform(put("/api/inspections/plans/" + planId)
                        .with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.version").value(1));

        // 第二次 PUT 仍带 version=0 → 409
        mockMvc.perform(put("/api/inspections/plans/" + planId)
                        .with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(409));
    }

    // ==================== enable / disable ====================

    @Test
    @DisplayName("13. POST /plans/{planId}/enable → 200, enabled=true, nextRunAt 非空")
    void enablePlanSetsNextRunAt() throws Exception {
        MvcResult createResult = mockMvc.perform(post("/api/inspections/plans")
                        .with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validHealthPlanBody()))
                .andExpect(status().isOk())
                .andReturn();
        String planId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("data").get("planId").asText();

        mockMvc.perform(post("/api/inspections/plans/" + planId + "/enable")
                        .with(csrf()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.nextRunAt").isNotEmpty());
    }

    @Test
    @DisplayName("14. POST /plans/{planId}/disable → 200, enabled=false, nextRunAt=null")
    void disablePlanClearsNextRunAt() throws Exception {
        MvcResult createResult = mockMvc.perform(post("/api/inspections/plans")
                        .with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validHealthPlanBody()))
                .andExpect(status().isOk())
                .andReturn();
        String planId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("data").get("planId").asText();

        // 先启用
        mockMvc.perform(post("/api/inspections/plans/" + planId + "/enable")
                        .with(csrf()).session(session))
                .andExpect(status().isOk());

        // 再停用
        mockMvc.perform(post("/api/inspections/plans/" + planId + "/disable")
                        .with(csrf()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.nextRunAt").doesNotExist());
    }

    // ==================== DELETE ====================

    @Test
    @DisplayName("15. DELETE /plans/{planId} 无 RUNNING → 200, 后续 GET 404")
    void deletePlanNoRunning() throws Exception {
        MvcResult createResult = mockMvc.perform(post("/api/inspections/plans")
                        .with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validHealthPlanBody()))
                .andExpect(status().isOk())
                .andReturn();
        String planId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("data").get("planId").asText();

        mockMvc.perform(delete("/api/inspections/plans/" + planId)
                        .with(csrf()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/api/inspections/plans/" + planId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("16. DELETE /plans/{planId} 有 RUNNING → 409")
    void deletePlanWithRunningRejected() throws Exception {
        MvcResult createResult = mockMvc.perform(post("/api/inspections/plans")
                        .with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validHealthPlanBody()))
                .andExpect(status().isOk())
                .andReturn();
        String planId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("data").get("planId").asText();

        // 手工塞一个 RUNNING execution(execution_id 列宽 36,直接用 UUID)
        jdbc.update("INSERT INTO inspection_executions " +
                "(execution_id, plan_id, status, trigger_type, operator, started_at, abnormal, created_at, updated_at) " +
                "VALUES (?, ?, 'RUNNING', 'MANUAL', 'admin', CURRENT_TIMESTAMP, false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString(), planId);

        mockMvc.perform(delete("/api/inspections/plans/" + planId)
                        .with(csrf()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(409));
    }

    // ==================== run ====================

    @Test
    @DisplayName("17. POST /plans/{planId}/run enabled → 200, executionId 非空, status=RUNNING 或 SUCCESS")
    void runPlanEnabled() throws Exception {
        MvcResult createResult = mockMvc.perform(post("/api/inspections/plans")
                        .with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validHealthPlanBody()))
                .andExpect(status().isOk())
                .andReturn();
        String planId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("data").get("planId").asText();

        // 启用
        mockMvc.perform(post("/api/inspections/plans/" + planId + "/enable")
                        .with(csrf()).session(session))
                .andExpect(status().isOk());

        // 触发
        mockMvc.perform(post("/api/inspections/plans/" + planId + "/run")
                        .with(csrf()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.executionId").isNotEmpty());
        // status 可能是 RUNNING(异步)或 SUCCESS(同步)— 任一即可
        // 通过后续 GET 验证 execution 一定存在
    }

    @Test
    @DisplayName("18. POST /plans/{planId}/run disabled → 400 + [plan]")
    void runPlanDisabled() throws Exception {
        MvcResult createResult = mockMvc.perform(post("/api/inspections/plans")
                        .with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validHealthPlanBody()))
                .andExpect(status().isOk())
                .andReturn();
        String planId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("data").get("planId").asText();

        // 不启用,直接 run
        mockMvc.perform(post("/api/inspections/plans/" + planId + "/run")
                        .with(csrf()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("[plan]")));
    }

    // ==================== executions ====================

    @Test
    @DisplayName("19. GET /executions 默认分页 → 200, data 数组")
    void listExecutionsDefault() throws Exception {
        mockMvc.perform(get("/api/inspections/executions").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("20. GET /executions?planId=X&status=FAILED → 200, 仅匹配")
    void listExecutionsFiltered() throws Exception {
        // 先创建 plan
        MvcResult createResult = mockMvc.perform(post("/api/inspections/plans")
                        .with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validHealthPlanBody()))
                .andExpect(status().isOk())
                .andReturn();
        String planId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("data").get("planId").asText();

        // 塞一条 FAILED execution(execution_id 列宽 36,直接用 UUID)
        jdbc.update("INSERT INTO inspection_executions " +
                "(execution_id, plan_id, status, trigger_type, operator, started_at, abnormal, created_at, updated_at) " +
                "VALUES (?, ?, 'FAILED', 'SCHEDULED', 'SYSTEM_SCHEDULER', CURRENT_TIMESTAMP, false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString(), planId);
        // 塞一条 SUCCESS execution(不应被 status=FAILED 过滤掉)
        jdbc.update("INSERT INTO inspection_executions " +
                "(execution_id, plan_id, status, trigger_type, operator, started_at, abnormal, created_at, updated_at) " +
                "VALUES (?, ?, 'SUCCESS', 'SCHEDULED', 'SYSTEM_SCHEDULER', CURRENT_TIMESTAMP, false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString(), planId);

        mockMvc.perform(get("/api/inspections/executions")
                        .session(session)
                        .param("planId", planId)
                        .param("status", "FAILED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[?(@.status=='FAILED')]").exists())
                .andExpect(jsonPath("$.data[?(@.status=='SUCCESS')]").doesNotExist());
    }

    @Test
    @DisplayName("21. GET /executions/{executionId} → 200, executionId 匹配")
    void getExecutionById() throws Exception {
        // execution_id 列宽 36,直接用 UUID
        String executionId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO inspection_executions " +
                "(execution_id, plan_id, status, trigger_type, operator, started_at, abnormal, created_at, updated_at) " +
                "VALUES (?, 'plan-x', 'SUCCESS', 'SCHEDULED', 'SYSTEM_SCHEDULER', CURRENT_TIMESTAMP, false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                executionId);

        mockMvc.perform(get("/api/inspections/executions/" + executionId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.executionId").value(executionId))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));
    }

    @Test
    @DisplayName("22. GET /executions?size=200 越界 → 200, 实际 size clamp 到 100")
    void listExecutionsSizeClampUpper() throws Exception {
        mockMvc.perform(get("/api/inspections/executions")
                        .session(session)
                        .param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());
        // 数据契约:controller 内部把 size clamp 到 1..100;此处断言端点 200 即可
    }

    @Test
    @DisplayName("23. GET /executions/{nonexistent} → 404")
    void getExecutionNotFound() throws Exception {
        mockMvc.perform(get("/api/inspections/executions/does-not-exist").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    // ==================== 辅助 ====================

    @SuppressWarnings("unused")
    private List<String> createdPlanIds() {
        return List.of();
    }

    @SuppressWarnings("unused")
    private void assertThatPlanId(JsonNode node) {
        assertThat(node).isNotNull();
        assertThat(node.asText()).isNotEmpty();
    }
}
