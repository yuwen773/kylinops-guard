package com.kylinops.inspection.api;

import com.kylinops.auth.AdminAuthenticationService;
import com.kylinops.common.ApiResponse;
import com.kylinops.common.BusinessException;
import com.kylinops.inspection.CreatePlanInput;
import com.kylinops.inspection.InspectionExecution;
import com.kylinops.inspection.InspectionExecutionService;
import com.kylinops.inspection.InspectionPlan;
import com.kylinops.inspection.InspectionPlanService;
import com.kylinops.inspection.InspectionScheduler;
import com.kylinops.inspection.InspectionTemplateRegistry;
import com.kylinops.inspection.InspectionValidationException;
import com.kylinops.inspection.model.InspectionExecutionStatus;
import com.kylinops.inspection.model.InspectionTemplateType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 巡检管理 REST API 控制器(P1-02 Task 7)。
 *
 * <h3>职责</h3>
 * <p>所有端点都经过 {@code /api/inspections/**},由 {@code SecurityConfig} 强制
 * 管理员认证;写接口(POST/PUT/DELETE)由 Spring Security CsrfFilter 保护。
 * 控制器只做:</p>
 * <ul>
 *   <li>请求体 / 查询参数 / 路径参数解析</li>
 *   <li>DTO ↔ Service 入参(CreatePlanInput / InspectionPlan)转换</li>
 *   <li>异常透传:BusinessException / InspectionValidationException 透传为对应 code;
 *       资源不存在转为 404(由 {@code @ExceptionHandler} 兜底)</li>
 * </ul>
 *
 * <h3>红线 (来自 CLAUDE.md + 设计 §10)</h3>
 * <ul>
 *   <li>所有 /api/inspections/** 端点要求管理员 session 认证(由 SecurityConfig 兜底)</li>
 *   <li>{@code operator} 字段永远从 session 拿,绝不信任请求体</li>
 *   <li>不创建 / 确认 {@code PendingAction}(巡检是只读路径,见 InspectionExecutionService 红线)</li>
 *   <li>不绕过 RiskCheck / 审计</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/inspections")
@RequiredArgsConstructor
public class InspectionController {

    /** 列表分页 size 下界。 */
    private static final int MIN_PAGE_SIZE = 1;
    /** 列表分页 size 上界 — 与 ReportController 一致。 */
    private static final int MAX_PAGE_SIZE = 100;
    /** 列表分页默认 size。 */
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final InspectionPlanService planService;
    private final InspectionExecutionService executionService;
    private final InspectionScheduler scheduler;
    private final InspectionTemplateRegistry templateRegistry;
    private final AdminAuthenticationService authnService;

    // ==================== templates ====================

    @GetMapping("/templates")
    public ApiResponse<List<InspectionTemplateView>> listTemplates() {
        List<InspectionTemplateView> views = new ArrayList<>();
        for (InspectionTemplateRegistry.TemplateEntry entry : templateRegistry.getAllTemplates()) {
            views.add(toTemplateView(entry));
        }
        return ApiResponse.success(views);
    }

    // ==================== plans ====================

    @GetMapping("/plans")
    public ApiResponse<List<InspectionPlanSummary>> listPlans() {
        Page<InspectionPlan> page = planService.listPlans(
                PageRequest.of(0, MAX_PAGE_SIZE));
        List<InspectionPlanSummary> items = page.getContent().stream()
                .map(InspectionController::toSummary)
                .toList();
        return ApiResponse.success(items);
    }

    @PostMapping("/plans")
    public ApiResponse<InspectionPlanDetail> createPlan(
            @Valid @RequestBody InspectionPlanRequest req) {
        InspectionPlan plan = planService.createPlan(toCreateInput(req));
        return ApiResponse.success(toDetail(plan));
    }

    @GetMapping("/plans/{planId}")
    public ApiResponse<InspectionPlanDetail> getPlan(@PathVariable String planId) {
        InspectionPlan plan = planService.findPlan(planId)
                .orElseThrow(() -> BusinessException.notFound("[plan] 不存在: " + planId));
        return ApiResponse.success(toDetail(plan));
    }

    @PutMapping("/plans/{planId}")
    public ApiResponse<InspectionPlanDetail> updatePlan(
            @PathVariable String planId,
            @Valid @RequestBody InspectionPlanUpdateRequest req) {
        InspectionPlan existing = planService.findPlan(planId)
                .orElseThrow(() -> BusinessException.notFound("[plan] 不存在: " + planId));

        // 部分更新:仅覆盖请求中非 null 字段(避免误清空)
        if (req.description() != null) existing.setDescription(req.description());
        if (req.templateType() != null) existing.setTemplateType(req.templateType());
        if (req.templateParams() != null) {
            existing.setTemplateParamsJson(serializeOrEmpty(req.templateParams()));
        }
        if (req.thresholds() != null) {
            existing.setThresholdsJson(serializeOrEmpty(req.thresholds()));
        }
        if (req.scheduleType() != null) existing.setScheduleType(req.scheduleType());
        Map<String, Object> scheduleCfg = scheduleConfigFromUpdate(req);
        if (!scheduleCfg.isEmpty()) {
            existing.setScheduleConfigJson(serializeOrEmpty(scheduleCfg));
        }
        if (req.timezone() != null) existing.setTimezone(req.timezone());
        if (req.notificationPolicy() != null) existing.setNotificationPolicy(req.notificationPolicy());
        existing.setVersion(req.version());

        InspectionPlan updated = planService.updatePlan(existing);
        return ApiResponse.success(toDetail(updated));
    }

    @PostMapping("/plans/{planId}/enable")
    public ApiResponse<InspectionPlanDetail> enablePlan(@PathVariable String planId) {
        InspectionPlan plan = planService.enablePlan(planId);
        return ApiResponse.success(toDetail(plan));
    }

    @PostMapping("/plans/{planId}/disable")
    public ApiResponse<InspectionPlanDetail> disablePlan(@PathVariable String planId) {
        InspectionPlan plan = planService.disablePlan(planId);
        return ApiResponse.success(toDetail(plan));
    }

    @DeleteMapping("/plans/{planId}")
    public ApiResponse<Void> deletePlan(@PathVariable String planId) {
        planService.deletePlan(planId);
        return ApiResponse.success();
    }

    @PostMapping("/plans/{planId}/run")
    public ApiResponse<RunResponse> runPlan(@PathVariable String planId, HttpServletRequest request) {
        // 关键红线:operator 来自 session,绝不信任请求体
        String operator = authnService.currentPrincipal(request);
        if (operator == null || operator.isBlank()) {
            // 已通过 SecurityConfig 认证,但 session 属性缺失 — 视为未登录
            throw BusinessException.forbidden("无法识别操作者,请重新登录");
        }
        String executionId = scheduler.runNow(planId, operator);
        // 立刻回查当前 status(可能是 RUNNING 或已完成)
        InspectionExecution exec = executionService.findExecution(executionId)
                .orElseThrow(() -> BusinessException.notFound(
                        "execution 写入后无法回查: " + executionId));
        return ApiResponse.success(new RunResponse(executionId, exec.getStatus()));
    }

    // ==================== executions ====================

    @GetMapping("/executions")
    public ApiResponse<List<InspectionExecutionSummary>> listExecutions(
            @RequestParam(required = false) String planId,
            @RequestParam(required = false) InspectionExecutionStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(MIN_PAGE_SIZE, size));
        Pageable pageable = PageRequest.of(safePage, safeSize);
        Page<InspectionExecution> result = executionService.listExecutions(planId, status, pageable);
        List<InspectionExecutionSummary> items = result.getContent().stream()
                .map(InspectionController::toExecutionSummary)
                .toList();
        return ApiResponse.success(items);
    }

    @GetMapping("/executions/{executionId}")
    public ApiResponse<InspectionExecutionDetail> getExecution(@PathVariable String executionId) {
        InspectionExecution exec = executionService.findExecution(executionId)
                .orElseThrow(() -> BusinessException.notFound(
                        "[execution] 不存在: " + executionId));
        return ApiResponse.success(toExecutionDetail(exec));
    }

    // ==================== 异常兜底 ====================

    /**
     * 资源不存在 — 把 {@link java.util.NoSuchElementException} 映射为 HTTP 404。
     * 其它"找不到"语义由 Service 直接抛 {@link BusinessException#notFound}。
     */
    @org.springframework.web.bind.annotation.ExceptionHandler(java.util.NoSuchElementException.class)
    public ApiResponse<Void> handleNotFound(java.util.NoSuchElementException ex) {
        log.warn("资源不存在: {}", ex.getMessage());
        return ApiResponse.error(404, ex.getMessage());
    }

    /**
     * 业务校验失败 — InspectionValidationException 已经继承 BusinessException(400),
     * 但为清晰语义仍在此单独映射,便于后续扩展字段级错误响应。
     */
    @org.springframework.web.bind.annotation.ExceptionHandler(InspectionValidationException.class)
    public ApiResponse<Void> handleValidation(InspectionValidationException ex) {
        log.warn("巡检验证失败: {}", ex.getMessage());
        return ApiResponse.error(ex.getCode(), ex.getMessage());
    }

    // ==================== DTO 转换 ====================

    private static CreatePlanInput toCreateInput(InspectionPlanRequest req) {
        CreatePlanInput in = new CreatePlanInput();
        in.name = req.name();
        in.description = req.description();
        in.templateType = req.templateType();
        in.templateParams = req.templateParams();
        in.thresholds = req.thresholds();
        in.scheduleType = req.scheduleType();
        in.localTime = req.localTime();
        in.timezone = req.timezone();
        in.dayOfWeek = req.dayOfWeek();
        in.dayOfMonth = req.dayOfMonth();
        in.notificationPolicy = req.notificationPolicy();
        return in;
    }

    private static InspectionPlanSummary toSummary(InspectionPlan plan) {
        return new InspectionPlanSummary(
                plan.getPlanId(),
                plan.getName(),
                plan.getDescription(),
                plan.getTemplateType(),
                plan.getScheduleType(),
                plan.getTimezone(),
                plan.getNotificationPolicy(),
                plan.isEnabled(),
                plan.getNextRunAt(),
                plan.getLastRunAt(),
                plan.getVersion()
        );
    }

    private static InspectionPlanDetail toDetail(InspectionPlan plan) {
        return new InspectionPlanDetail(
                plan.getPlanId(),
                plan.getName(),
                plan.getDescription(),
                plan.getTemplateType(),
                plan.getScheduleType(),
                plan.getTimezone(),
                plan.getNotificationPolicy(),
                plan.isEnabled(),
                plan.getNextRunAt(),
                plan.getLastRunAt(),
                plan.getVersion(),
                plan.getTemplateParamsJson(),
                plan.getThresholdsJson(),
                plan.getScheduleConfigJson(),
                plan.getCreatedAt(),
                plan.getUpdatedAt()
        );
    }

    private static InspectionExecutionSummary toExecutionSummary(InspectionExecution e) {
        return new InspectionExecutionSummary(
                e.getPlanId(),
                e.getExecutionId(),
                e.getStatus(),
                e.getTriggerType(),
                e.getOperator(),
                e.getStartedAt(),
                e.getFinishedAt(),
                e.isAbnormal(),
                e.getSummary()
        );
    }

    private static InspectionExecutionDetail toExecutionDetail(InspectionExecution e) {
        return new InspectionExecutionDetail(
                e.getPlanId(),
                e.getExecutionId(),
                e.getStatus(),
                e.getTriggerType(),
                e.getOperator(),
                e.getStartedAt(),
                e.getFinishedAt(),
                e.isAbnormal(),
                e.getSummary(),
                e.getPlanSnapshotJson(),
                e.getAuditId(),
                e.getReportId(),
                // 失败原因取自 summary(FAILED 时由 Service 写入 "巡检 FAILED: ...")
                e.getStatus() == InspectionExecutionStatus.FAILED ? e.getSummary() : null
        );
    }

    /**
     * 把 UpdateRequest 中与调度配置相关的可选字段打包成嵌套 Map,
     * 供 Service 序列化 scheduleConfigJson。
     */
    private Map<String, Object> scheduleConfigFromUpdate(InspectionPlanUpdateRequest req) {
        Map<String, Object> map = new HashMap<>();
        if (req.localTime() != null) map.put("localTime", req.localTime());
        if (req.dayOfWeek() != null) map.put("dayOfWeek", req.dayOfWeek().name());
        if (req.dayOfMonth() != null) map.put("dayOfMonth", req.dayOfMonth());
        return map;
    }

    /**
     * 把模板条目转为前端友好的视图。
     * 字段定义 (per-template) 是静态契约,根据 InspectionTemplateType 分发。
     */
    private static InspectionTemplateView toTemplateView(InspectionTemplateRegistry.TemplateEntry entry) {
        List<InspectionTemplateView.TemplateField> fields = fieldsFor(entry.type());
        return new InspectionTemplateView(
                entry.type(),
                entry.displayName(),
                fields,
                entry.definition().riskLevels(),
                entry.definition().keyToolNames()
        );
    }

    /**
     * 三模板的字段定义(静态,前端根据 fields 动态生成表单)。
     * 与 {@link com.kylinops.inspection.InspectionPlanValidator} 的
     * 字段名严格一致,确保前后端契约统一。
     */
    private static List<InspectionTemplateView.TemplateField> fieldsFor(InspectionTemplateType type) {
        return switch (type) {
            case HEALTH -> List.of(
                    new InspectionTemplateView.TemplateField(
                            "serviceName", "服务名", "string", true, null,
                            Map.of("description", "必填且必须在 kylinops.inspection.allowed-services 列表中")),
                    new InspectionTemplateView.TemplateField(
                            "cpuWarningPercent", "CPU 警告百分比", "number", false, "80",
                            Map.of("min", 50, "max", 100)),
                    new InspectionTemplateView.TemplateField(
                            "memoryWarningPercent", "内存警告百分比", "number", false, "80",
                            Map.of("min", 50, "max", 100)),
                    new InspectionTemplateView.TemplateField(
                            "diskWarningPercent", "磁盘警告百分比", "number", false, "85",
                            Map.of("min", 50, "max", 100))
            );
            case DISK -> List.of(
                    new InspectionTemplateView.TemplateField(
                            "scanDir", "扫描路径", "string", true, null,
                            Map.of("description", "必填,在 BaseOSValidator.ALLOWED_SCAN_ROOTS 内")),
                    new InspectionTemplateView.TemplateField(
                            "logServiceName", "日志服务名(可选)", "string", false, null,
                            Map.of("description", "非空时追加 journal_log_tool 阶段")),
                    new InspectionTemplateView.TemplateField(
                            "diskWarningPercent", "磁盘警告百分比", "number", false, "85",
                            Map.of("min", 50, "max", 100)),
                    new InspectionTemplateView.TemplateField(
                            "largeFileMinMb", "大文件阈值 MB", "number", false, "1024",
                            Map.of("min", 100, "max", 1048576))
            );
            case SERVICE -> List.of(
                    new InspectionTemplateView.TemplateField(
                            "serviceName", "服务名", "string", true, null,
                            Map.of("description", "必填且在 allowlist 内")),
                    new InspectionTemplateView.TemplateField(
                            "expectedPort", "预期端口(可选)", "number", false, null,
                            Map.of("min", 1, "max", 65535))
            );
        };
    }

    private static String serializeOrEmpty(Object o) {
        if (o == null) return "{}";
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }
}
