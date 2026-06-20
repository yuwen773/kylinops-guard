package com.kylinops.inspection;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kylinops.inspection.model.InspectionExecutionStatus;
import com.kylinops.inspection.model.InspectionScheduleType;
import jakarta.persistence.OptimisticLockException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * 巡检计划生命周期服务(P1-02 Task 6)。
 *
 * <h3>主入口</h3>
 * <ul>
 *   <li>{@link #createPlan(CreatePlanInput)} — 新建计划(默认 enabled=false)</li>
 *   <li>{@link #updatePlan(InspectionPlan)} — 乐观锁更新</li>
 *   <li>{@link #enablePlan(String)} — 启用 + 计算 nextRunAt</li>
 *   <li>{@link #disablePlan(String)} — 停用 + 清空 nextRunAt</li>
 *   <li>{@link #deletePlan(String)} — 物理删除(有 RUNNING 则拒绝)</li>
 *   <li>{@link #getPlan(String)} — 查询单条</li>
 * </ul>
 *
 * <h3>设计约束(来自设计 §5.1 + §7)</h3>
 * <ul>
 *   <li>新建计划默认 enabled=false, version=0, nextRunAt=null
 *   <li>启用计划时调 InspectionScheduleCalculator.nextRun 计算下次时间(Asia/Shanghai 等 IANA 时区)
 *   <li>停用计划时清空 nextRunAt 防止调度器误拉取
 *   <li>删除计划时若 executions 表存在 RUNNING 拒绝(防止丢失活跃执行记录)
 *   <li>乐观锁冲突抛 {@link InspectionValidationException}(409)
 * </ul>
 */
@Service
public class InspectionPlanService {

    private final InspectionPlanRepository planRepository;
    private final InspectionExecutionRepository executionRepository;
    private final InspectionPlanValidator validator;
    private final InspectionScheduleCalculator scheduleCalculator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public InspectionPlanService(InspectionPlanRepository planRepository,
                                  InspectionExecutionRepository executionRepository,
                                  InspectionPlanValidator validator,
                                  InspectionScheduleCalculator scheduleCalculator) {
        this.planRepository = planRepository;
        this.executionRepository = executionRepository;
        this.validator = validator;
        this.scheduleCalculator = scheduleCalculator;
    }

    /**
     * 新建计划(默认停用)。
     *
     * <p>校验失败抛 {@link InspectionValidationException};唯一约束冲突由 DB 抛
     * DataIntegrityViolationException,Service 不做转换。</p>
     */
    @Transactional
    public InspectionPlan createPlan(CreatePlanInput input) {
        if (input == null) {
            throw new InspectionValidationException("[input] 不能为空");
        }
        InspectionScheduleConfig cfg = new InspectionScheduleConfig(
                input.localTime, input.dayOfWeek, input.dayOfMonth);
        validator.validate(input.templateType,
                input.templateParams,
                input.thresholds,
                input.scheduleType,
                cfg,
                input.timezone);

        InspectionPlan plan = new InspectionPlan();
        plan.setPlanId(UUID.randomUUID().toString());
        plan.setName(input.name);
        plan.setDescription(input.description);
        plan.setTemplateType(input.templateType);
        plan.setTemplateParamsJson(serializeOrEmpty(input.templateParams));
        plan.setThresholdsJson(serializeOrEmpty(input.thresholds));
        plan.setScheduleType(input.scheduleType);
        plan.setScheduleConfigJson(serializeScheduleConfig(input));
        plan.setTimezone(input.timezone);
        plan.setNotificationPolicy(input.notificationPolicy);
        plan.setEnabled(false);     // 新建默认停用(设计 §5.1)
        plan.setNextRunAt(null);    // 启用时才计算
        plan.setLastRunAt(null);

        // @Version 默认 0(Lombok @NoArgsConstructor + BaseEntity 不显式初始化)
        // InspectionPlan 没有显式 setVersion,默认 long=0
        LocalDateTime now = LocalDateTime.now();
        plan.setCreatedAt(now);
        plan.setUpdatedAt(now);

        return planRepository.saveAndFlush(plan);
    }

    /**
     * 更新计划。乐观锁冲突(基于旧 version)抛 409 异常。
     *
     * <p>{@code plan.getVersion()} 必须为调用方读到的当前 version;若数据库版本已变则冲突。</p>
     */
    @Transactional
    public InspectionPlan updatePlan(InspectionPlan plan) {
        if (plan == null || plan.getPlanId() == null) {
            throw new InspectionValidationException("[plan] 不能为空");
        }
        InspectionPlan existing = planRepository.findByPlanId(plan.getPlanId())
                .orElseThrow(() -> new NoSuchElementException(
                        "巡检计划不存在: " + plan.getPlanId()));

        // 乐观锁: 入参 version 必须 == 数据库当前 version
        if (plan.getVersion() != existing.getVersion()) {
            throw new com.kylinops.common.BusinessException(409,
                    "[plan] 版本冲突,已被其它事务更新: " + plan.getPlanId()
                            + " (current=" + existing.getVersion()
                            + ", provided=" + plan.getVersion() + ")");
        }

        // 业务字段更新(允许部分字段更新)
        if (plan.getDescription() != null) existing.setDescription(plan.getDescription());
        if (plan.getThresholdsJson() != null) existing.setThresholdsJson(plan.getThresholdsJson());
        if (plan.getTemplateParamsJson() != null) existing.setTemplateParamsJson(plan.getTemplateParamsJson());
        if (plan.getScheduleConfigJson() != null) existing.setScheduleConfigJson(plan.getScheduleConfigJson());
        if (plan.getTimezone() != null) existing.setTimezone(plan.getTimezone());
        if (plan.getNotificationPolicy() != null) existing.setNotificationPolicy(plan.getNotificationPolicy());
        if (plan.getTemplateType() != null) existing.setTemplateType(plan.getTemplateType());
        if (plan.getScheduleType() != null) existing.setScheduleType(plan.getScheduleType());
        existing.setUpdatedAt(LocalDateTime.now());

        try {
            return planRepository.saveAndFlush(existing);
        } catch (OptimisticLockException | ObjectOptimisticLockingFailureException e) {
            throw new com.kylinops.common.BusinessException(409,
                    "[plan] 版本冲突,已被其它事务更新: " + existing.getPlanId());
        }
    }

    /**
     * 启用计划并计算 nextRunAt。
     *
     * <p>计算基准:从当前 UTC Instant 计算下一个未来触发时刻(InspectionScheduleCalculator 严格 afterExclusive)。
     * 计算结果转 LocalDateTime(UTC)持久化。</p>
     */
    @Transactional
    public InspectionPlan enablePlan(String planId) {
        InspectionPlan plan = getPlan(planId);
        if (plan.isEnabled()) {
            return plan; // 已启用,幂等返回
        }
        plan.setEnabled(true);
        plan.setNextRunAt(computeNextRunAt(plan, Instant.now()));
        plan.setUpdatedAt(LocalDateTime.now());
        try {
            return planRepository.saveAndFlush(plan);
        } catch (OptimisticLockException | ObjectOptimisticLockingFailureException e) {
            throw new InspectionValidationException(
                    "[plan] 版本冲突: " + planId);
        }
    }

    /**
     * 停用计划并清空 nextRunAt(避免调度器误拉取)。
     */
    @Transactional
    public InspectionPlan disablePlan(String planId) {
        InspectionPlan plan = getPlan(planId);
        plan.setEnabled(false);
        plan.setNextRunAt(null);
        plan.setUpdatedAt(LocalDateTime.now());
        try {
            return planRepository.saveAndFlush(plan);
        } catch (OptimisticLockException | ObjectOptimisticLockingFailureException e) {
            throw new InspectionValidationException(
                    "[plan] 版本冲突: " + planId);
        }
    }

    /**
     * 物理删除计划。存在 RUNNING execution 时拒绝(409),保护活跃执行记录。
     */
    @Transactional
    public void deletePlan(String planId) {
        InspectionPlan plan = getPlan(planId);
        if (executionRepository.existsByPlanIdAndStatus(planId, InspectionExecutionStatus.RUNNING)) {
            throw new com.kylinops.common.BusinessException(409,
                    "[plan] 存在 RUNNING 执行,无法删除: " + planId);
        }
        planRepository.deleteById(plan.getId());
        planRepository.flush();
    }

    /**
     * 查询计划。不存在抛 {@link InspectionValidationException}(404)。
     */
    @Transactional(readOnly = true)
    public InspectionPlan getPlan(String planId) {
        return planRepository.findByPlanId(planId)
                .orElseThrow(() -> new InspectionValidationException(
                        "[plan] 不存在: " + planId));
    }

    /**
     * 查询计划(返回 Optional)。P1-02 Task 7 列表/详情 API 用 —
     * 由 controller 把 {@code Optional.empty()} 映射为 HTTP 404(避免与
     * 校验异常的 400 混淆)。
     */
    @Transactional(readOnly = true)
    public java.util.Optional<InspectionPlan> findPlan(String planId) {
        if (planId == null || planId.isBlank()) {
            return java.util.Optional.empty();
        }
        return planRepository.findByPlanId(planId);
    }

    /**
     * 列出所有计划(按 createdAt DESC,分页)。P1-02 Task 7 列表 API 用。
     *
     * <p>size 由 controller 在 [1, 100] 内 clamp,Service 层不再校验;
     * 排序按创建时间倒序(最新在前),与"计划管理 → 列表"前端预期一致。</p>
     */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<InspectionPlan> listPlans(
            org.springframework.data.domain.Pageable pageable) {
        return planRepository.findAll(
                org.springframework.data.domain.PageRequest.of(
                        pageable.getPageNumber(),
                        pageable.getPageSize(),
                        org.springframework.data.domain.Sort.by(
                                org.springframework.data.domain.Sort.Direction.DESC, "createdAt")));
    }

    // ==================== 内部 ====================

    /**
     * 调 InspectionScheduleCalculator 计算 nextRunAt(UTC Instant → LocalDateTime)。
     * 用于 enablePlan 与后续调度器的轮询重算。
     */
    LocalDateTime computeNextRunAt(InspectionPlan plan, Instant afterExclusive) {
        InspectionScheduleConfig cfg = parseScheduleConfig(plan.getScheduleConfigJson());
        ZoneId zone = ZoneId.of(plan.getTimezone());
        Instant next = scheduleCalculator.nextRun(plan.getScheduleType(), cfg, zone, afterExclusive);
        return LocalDateTime.ofInstant(next, ZoneOffset.UTC);
    }

    private InspectionScheduleConfig parseScheduleConfig(String json) {
        if (json == null || json.isBlank()) {
            throw new InspectionValidationException("[scheduleConfig] 不能为空");
        }
        try {
            MapLike map = objectMapper.readValue(json, MapLike.class);
            LocalTime time = LocalTime.parse(map.localTime);
            DayOfWeek dow = map.dayOfWeek == null ? null : DayOfWeek.valueOf(map.dayOfWeek);
            Integer dom = map.dayOfMonth;
            return new InspectionScheduleConfig(time, dow, dom);
        } catch (Exception e) {
            throw new InspectionValidationException("[scheduleConfig] 解析失败: " + e.getMessage());
        }
    }

    private static String serializeOrEmpty(Object o) {
        if (o == null) return "{}";
        try {
            return new ObjectMapper().writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String serializeScheduleConfig(CreatePlanInput input) {
        java.util.LinkedHashMap<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("localTime", input.localTime == null ? null : input.localTime.toString());
        if (input.dayOfWeek != null) map.put("dayOfWeek", input.dayOfWeek.name());
        if (input.dayOfMonth != null) map.put("dayOfMonth", input.dayOfMonth);
        try {
            return new ObjectMapper().writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** MapLike:Jackson 反序列化辅助。 */
    public static class MapLike {
        public String localTime;
        public String dayOfWeek;
        public Integer dayOfMonth;
    }

    /** 兼容 TypeReference 的反序列化(供后续扩展使用)。 */
    @SuppressWarnings("unused")
    private static final TypeReference<java.util.Map<String, Object>> MAP_TYPE =
            new TypeReference<>() {};
}