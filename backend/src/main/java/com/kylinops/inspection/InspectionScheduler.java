package com.kylinops.inspection;

import com.kylinops.inspection.model.InspectionExecutionStatus;
import com.kylinops.inspection.model.InspectionTriggerType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 巡检调度器(P1-02 Task 6)。
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>周期性扫描到期计划 → 调用 {@link InspectionExecutionService#executeScheduled}</li>
 *   <li>手动触发 {@link #runNow(String, String)} → 调用 executeManual, 不修改 plan.nextRunAt</li>
 *   <li>启动期恢复委托给 {@link InspectionRecovery}</li>
 *   <li>触发后重算 plan.nextRunAt(从不重放过期窗口)</li>
 * </ul>
 *
 * <h3>非职责</h3>
 * <ul>
 *   <li>不在内存里维护锁 — 依赖 Service.existsByPlanIdAndStatus(RUNNING) 原子检测
 *   <li>不感知 SKIPPED — Service 内部决定,Scheduler 照常推进
 *   <li>不创建 Session/Message/PendingAction — 调 Service.executeScheduled/executeManual
 * </ul>
 */
@Slf4j
@Component
public class InspectionScheduler {

    private final InspectionExecutionService executionService;
    private final InspectionPlanRepository planRepository;
    private final InspectionPlanService planService;
    private final InspectionRecovery recovery;
    private final ThreadPoolTaskScheduler taskScheduler;
    private final InspectionProperties properties;
    private final Clock clock;

    private volatile boolean running = false;
    private volatile boolean shutdown = false;

    @Autowired
    public InspectionScheduler(InspectionExecutionService executionService,
                                InspectionPlanRepository planRepository,
                                InspectionPlanService planService,
                                InspectionRecovery recovery,
                                ThreadPoolTaskScheduler taskScheduler,
                                InspectionProperties properties,
                                Clock clock) {
        this.executionService = executionService;
        this.planRepository = planRepository;
        this.planService = planService;
        this.recovery = recovery;
        this.taskScheduler = taskScheduler;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 应用启动完成后,启动调度器(委托给 Spring 提供的 ThreadPoolTaskScheduler),
     * 跑一次启动期恢复 + 一次首次扫描,然后按 pollInterval 周期触发。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (shutdown) {
            return;
        }
        // 1) 启动期恢复(独立事务, 不阻塞 application ready)
        try {
            recovery.recoverAbandoned();
        } catch (Exception e) {
            log.warn("启动期恢复失败(继续启动): {}", e.getMessage(), e);
        }
        // 2) 周期调度(测试可关闭 auto-start 避免污染断言)
        if (properties.getScheduler().isAutoStart()) {
            start();
        } else {
            log.info("ApplicationReadyEvent 跳过 auto-start (auto-start=false)");
        }
    }

    /** 启动周期调度。已运行则幂等返回。 */
    public synchronized void start() {
        if (running || shutdown) {
            return;
        }
        running = true;
        long intervalMs = Math.max(properties.getScheduler().getPollInterval().toMillis(), 100L);
        taskScheduler.scheduleAtFixedRate(this::tickSafely, intervalMs);
        log.info("巡检调度器启动: pollInterval={}ms", intervalMs);
    }

    /**
     * 关闭调度器。停止接收新任务,等已开始的任务完成。
     */
    @PreDestroy
    public synchronized void shutdown() {
        if (shutdown) {
            return;
        }
        shutdown = true;
        running = false;
        log.info("巡检调度器关闭");
        // ThreadPoolTaskScheduler 由 Spring 容器管理, 不在此显式 shutdown,
        // 避免与 InspectionSchedulingConfig.destroyMethod="shutdown" 双重关闭。
    }

    /** 当前调度器是否在运行。 */
    public boolean isRunning() {
        return running;
    }

    /** 当前调度器是否已 shutdown(Spring 共享 context 的测试需要此标志)。 */
    public boolean isShutdown() {
        return shutdown;
    }

    /**
     * 测试辅助:重启已 shutdown 的调度器。仅供 SpringBootTest 共享 context 场景使用,
     * 让后续测试能在被前序测试 shutdown 后再次触发 runOnePoll。
     * 注意:不重新调用 start(),避免周期性 poll 与单测断言互相干扰。
     */
    public synchronized void restartForTest() {
        if (!shutdown) {
            return;
        }
        shutdown = false;
        running = false;
        log.debug("巡检调度器 restartForTest: 重置 shutdown/running 标志, 不重新启动周期 poll");
    }

    /**
     * 立刻触发一次完整扫描。可由测试或运维 API 调用(走当前 Clock 时间)。
     */
    public void runOnePoll() {
        runOnePoll(clock.instant());
    }

    /**
     * 立刻触发一次完整扫描,使用给定的时间基准。供测试注入 deterministic 时间。
     */
    public void runOnePoll(Instant now) {
        if (shutdown) {
            return;
        }
        List<InspectionPlan> duePlans = planRepository.findByEnabledTrueAndNextRunAtLessThanEqual(
                LocalDateTime.ofInstant(now, java.time.ZoneOffset.UTC));
        for (InspectionPlan plan : duePlans) {
            triggerScheduled(plan, now);
        }
    }

    /**
     * 手动触发。立即调 ExecutionService.executeManual, 不修改 plan.nextRunAt。
     *
     * @param planId   计划 ID
     * @param operator 操作主体(管理员用户名)
     * @return executionId(Service 返回 execution)
     */
    public String runNow(String planId, String operator) {
        InspectionPlan plan = planService.getPlan(planId);
        // 校验:disabled 计划不允许手动触发
        if (!plan.isEnabled()) {
            throw new InspectionValidationException(
                    "[plan] 未启用,无法手动触发: " + planId);
        }
        InspectionExecution exec = executionService.executeManual(plan, operator);
        log.info("手动触发: planId={}, operator={}, executionId={}",
                planId, operator, exec.getExecutionId());
        // 不重算 nextRunAt — 调度器下次扫描仍按原节奏
        return exec.getExecutionId();
    }

    // ==================== 内部 ====================

    /**
     * 调度器周期 tick 的包装:异常不打断调度。
     */
    private void tickSafely() {
        try {
            runOnePoll();
        } catch (Exception e) {
            log.error("巡检扫描异常(调度继续): {}", e.getMessage(), e);
        }
    }

    /**
     * 触发单个到期计划。流程:executeScheduled → 重算 nextRunAt → save。
     */
    private void triggerScheduled(InspectionPlan plan, Instant now) {
        try {
            InspectionExecution exec = executionService.executeScheduled(plan);
            log.debug("到期计划触发: planId={}, status={}, executionId={}",
                    plan.getPlanId(), exec.getStatus(), exec.getExecutionId());

            // 重算 nextRunAt(从不重放) — 即便 SKIPPED 也按节奏推进
            LocalDateTime nextRun = planService.computeNextRunAt(plan, Instant.now());
            plan.setNextRunAt(nextRun);
            plan.setLastRunAt(LocalDateTime.ofInstant(now, java.time.ZoneOffset.UTC));
            planRepository.save(plan);
        } catch (Exception e) {
            log.error("触发巡检异常: planId={}", plan.getPlanId(), e);
        }
    }
}