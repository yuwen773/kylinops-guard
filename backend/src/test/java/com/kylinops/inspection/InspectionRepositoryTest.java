package com.kylinops.inspection;

import com.kylinops.inspection.model.InspectionExecutionStatus;
import com.kylinops.inspection.model.InspectionNotificationPolicy;
import com.kylinops.inspection.model.InspectionScheduleType;
import com.kylinops.inspection.model.InspectionTemplateType;
import com.kylinops.inspection.model.InspectionTriggerType;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1-02 Plan 02 — Task 2 (Repository-level tests).
 *
 * <p>覆盖 7 个 repository 行为契约(Plan §Step 4):</p>
 * <ol>
 *   <li>save + reload plan,JSON snapshot 字符串原样回填</li>
 *   <li>@Version 乐观锁:两处读出后并发写,后者抛 {@link ObjectOptimisticLockingFailureException}</li>
 *   <li>删除 plan 仍保留 execution(findByExecutionId 仍返回)</li>
 *   <li>{@code findEnabledDueAt(now)} 仅返回 enabled=true 且 next_run_at <= now 的计划</li>
 *   <li>{@code findByPlanIdForUpdate} 行锁串行化:事务 A 持锁,事务 B 在不同 connection 上阻塞等待</li>
 *   <li>同计划重入:已有 RUNNING 时,新事务感知并写 SKIPPED</li>
 *   <li>启动期恢复查询 {@code findAllByStatus(RUNNING)} 列出 abandoned 行</li>
 * </ol>
 *
 * <p><b>测试设置：</b></p>
 * <ul>
 *   <li>{@code @DataJpaTest} 复用既有 test profile 的 H2 datasource + Flyway V1-V7 全套迁移</li>
 *   <li>{@code @Rollback(false)} + {@link BeforeEach} 手工清表 — 事务模板(测试 5/6)需要显式提交才能跨事务看见锁/重入效果</li>
 *   <li>{@link Import} {@code InspectionBeansConfig} 仅为了注入 Spring 配置类,实际业务 Bean 不在本测试使用范围</li>
 * </ul>
 */
@DataJpaTest
@Import(InspectionBeansConfig.class)
@ActiveProfiles("test")
@DisplayName("P1-02 T2 — InspectionRepository")
class InspectionRepositoryTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private InspectionPlanRepository planRepo;

    @Autowired
    private InspectionExecutionRepository execRepo;

    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    private JdbcTemplate jdbc;

    private TransactionTemplate tx;

    @BeforeEach
    void cleanDb() {
        // 每个测试方法前清表(默认 @DataJpaTest 在方法结束时回滚,清表保证幂等)
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            em.getEntityManager().createQuery("DELETE FROM InspectionExecution").executeUpdate();
            em.getEntityManager().createQuery("DELETE FROM InspectionPlan").executeUpdate();
        });
        tx = new TransactionTemplate(txManager);
    }

    /** 构造最小可持久化的 plan,字段覆盖 JSON 快照列。 */
    private InspectionPlan samplePlan(String planId, boolean enabled, LocalDateTime nextRunAt) {
        InspectionPlan p = new InspectionPlan();
        p.setPlanId(planId);
        p.setName("plan-" + planId);
        p.setDescription("desc-" + planId);
        p.setTemplateType(InspectionTemplateType.HEALTH);
        p.setTemplateParamsJson("{\"serviceName\":\"nginx\"}");
        p.setThresholdsJson("{\"cpuWarningPercent\":80}");
        p.setScheduleType(InspectionScheduleType.DAILY);
        p.setScheduleConfigJson("{\"localTime\":\"08:00\"}");
        p.setTimezone("Asia/Shanghai");
        p.setNotificationPolicy(InspectionNotificationPolicy.ON_ABNORMAL);
        p.setEnabled(enabled);
        p.setNextRunAt(nextRunAt);
        p.setLastRunAt(null);
        LocalDateTime now = LocalDateTime.now();
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        return p;
    }

    /** 构造最小可持久化的 execution,plan_id 是普通 String。 */
    private InspectionExecution sampleExecution(String planId, InspectionExecutionStatus status,
                                                 InspectionTriggerType trigger, String operator) {
        InspectionExecution e = new InspectionExecution();
        e.setExecutionId(UUID.randomUUID().toString());
        e.setPlanId(planId);
        e.setPlanSnapshotJson("{\"name\":\"snapshot\"}");
        e.setStatus(status);
        e.setTriggerType(trigger);
        e.setOperator(operator);
        e.setStartedAt(LocalDateTime.now());
        e.setFinishedAt(null);
        e.setSummary("sample");
        e.setAuditId(null);
        e.setReportId(null);
        e.setAbnormal(false);
        LocalDateTime now = LocalDateTime.now();
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        return e;
    }

    // ----- 1. save + reload + JSON snapshot -----

    @Test
    @DisplayName("save + findByPlanId 后 JSON snapshot 字段原样回填")
    void saveAndReloadPlan_roundTripsJsonSnapshot() {
        // REQUIRES_NEW:save 提交后,后续 read 才能读到一致 JSON 快照
        newRequiresNew().executeWithoutResult(s ->
                planRepo.saveAndFlush(samplePlan("plan-1", true,
                        LocalDateTime.now().plusDays(1))));
        em.clear();

        Optional<InspectionPlan> reloaded = planRepo.findByPlanId("plan-1");
        assertThat(reloaded)
                .as("findByPlanId 应能定位刚保存的计划")
                .isPresent();
        InspectionPlan p = reloaded.get();
        assertThat(p.getPlanId()).isEqualTo("plan-1");
        assertThat(p.getName()).isEqualTo("plan-plan-1");
        assertThat(p.getTemplateParamsJson()).isEqualTo("{\"serviceName\":\"nginx\"}");
        assertThat(p.getThresholdsJson()).isEqualTo("{\"cpuWarningPercent\":80}");
        assertThat(p.getScheduleConfigJson()).isEqualTo("{\"localTime\":\"08:00\"}");
        assertThat(p.getTemplateType()).isEqualTo(InspectionTemplateType.HEALTH);
        assertThat(p.getScheduleType()).isEqualTo(InspectionScheduleType.DAILY);
        assertThat(p.getNotificationPolicy()).isEqualTo(InspectionNotificationPolicy.ON_ABNORMAL);
        assertThat(p.isEnabled()).isTrue();
        // @Version 字段持久化后从 0 起始,saveAndFlush 不会变;后续更新会增 1
        assertThat(p.getVersion()).isEqualTo(0L);
    }

    // ----- 2. @Version 乐观锁冲突 -----

    @Test
    @DisplayName("@Version 乐观锁:基于旧 version 的并发更新抛 ObjectOptimisticLockingFailureException")
    void optimisticLockConflict_onConcurrentUpdate() {
        // 1) 初始 INSERT 在 REQUIRES_NEW 事务中提交(version=0)
        newRequiresNew().executeWithoutResult(s ->
                planRepo.saveAndFlush(samplePlan("plan-2", true,
                        LocalDateTime.now().plusDays(1))));

        // 2) 测试事务内读出 entity(managed,version=0)
        InspectionPlan reader = planRepo.findByPlanId("plan-2").orElseThrow();
        assertThat(reader.getVersion())
                .as("初始 INSERT 后 version 应为 0")
                .isEqualTo(0L);

        // 3) 模拟"另一并发事务已经更新并提交":直接 SQL 递增 version + 改名,
        //    DB.version 变为 1,而 reader 仍持有 in-memory version=0(stale snapshot)。
        newRequiresNew().executeWithoutResult(s -> jdbc.update(
                "UPDATE inspection_plans SET version = version + 1, name = ? WHERE plan_id = ?",
                "by-other-tx", "plan-2"));

        // 4) reader 提交自己的修改,UPDATE WHERE version=0 在 DB 中匹配 0 行 → 冲突
        reader.setName("by-this-tx");
        // 注:em.flush() 直接抛 JPA 标准 OptimisticLockException;Spring 的
        // ObjectOptimisticLockingFailureException 包装在 @Transactional 提交边界
        // 才发生(由 JpaTransactionManager.convertJpaAccessException 翻译),
        // 所以这里接受任一类型 —— 关键是 @Version 真的生效。
        assertThatThrownBy(() -> em.flush())
                .as("基于旧 version 的并发更新必须抛乐观锁异常")
                .isInstanceOfAny(ObjectOptimisticLockingFailureException.class,
                        OptimisticLockException.class);
    }

    // ----- 3. 删除 plan 保留 execution -----

    @Test
    @DisplayName("删除 plan 后 execution 仍可按 executionId 查出(无 FK 级联)")
    void deletePlan_preservesExecution() {
        // 准备数据:plan + execution 在 REQUIRES_NEW 事务中提交
        newRequiresNew().executeWithoutResult(s -> {
            InspectionPlan plan = planRepo.saveAndFlush(samplePlan("plan-3", true,
                    LocalDateTime.now().plusDays(1)));
            execRepo.saveAndFlush(sampleExecution(plan.getPlanId(),
                    InspectionExecutionStatus.SUCCESS,
                    InspectionTriggerType.SCHEDULED, "SYSTEM_SCHEDULER"));
        });
        em.clear();

        // 物理删除 plan(无 FK 级联)
        newRequiresNew().executeWithoutResult(s -> {
            InspectionPlan plan = planRepo.findByPlanId("plan-3").orElseThrow();
            planRepo.deleteById(plan.getId());
            em.flush();
        });

        // execution 仍可通过 executionId 查出
        List<InspectionExecution> all = execRepo.findAll();
        assertThat(all)
                .as("删除 plan 后 execution 仍存在")
                .hasSize(1);
        assertThat(all.get(0).getPlanId())
                .as("execution.plan_id 应保留原 planId(普通 String 字段)")
                .isEqualTo("plan-3");

        // plan 确实被删了
        assertThat(planRepo.findByPlanId("plan-3"))
                .as("plan 物理删除后 findByPlanId 应为空")
                .isEmpty();
    }

    @Test
    @DisplayName("uk_inspection_plan_name 唯一约束:同名 plan 重复插入抛 DataIntegrityViolationException")
    void uniqueConstraint_nameEnforced() {
        // 第一行 plan 在 REQUIRES_NEW 事务中提交,后续唯一约束冲突在独立事务里抛,
        // 不会污染 @DataJpaTest 外层事务的 rollback-only 标记
        newRequiresNew().executeWithoutResult(s ->
                planRepo.saveAndFlush(samplePlan("plan-4a", true, LocalDateTime.now().plusDays(1))));

        InspectionPlan duplicate = samplePlan("plan-4b", false, null);
        duplicate.setName("plan-plan-4a"); // 复用一个已存在的 name

        assertThatThrownBy(() -> newRequiresNew().executeWithoutResult(s ->
                planRepo.saveAndFlush(duplicate)))
                .as("uk_inspection_plan_name 必须拒绝同名 plan")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ----- 4. findEnabledDueAt(now) -----

    @Test
    @DisplayName("findEnabledDueAt 仅返回 enabled=true 且 next_run_at <= now 的计划")
    void findEnabledDueAt_returnsOnlyDueAndEnabled() {
        LocalDateTime now = LocalDateTime.now();

        // 4 条组合在 REQUIRES_NEW 事务中批量提交,使查询能从已提交状态读到一致视图
        newRequiresNew().executeWithoutResult(s -> {
            planRepo.saveAndFlush(samplePlan("plan-due", true, now.minusMinutes(5)));
            planRepo.saveAndFlush(samplePlan("plan-future", true, now.plusHours(1)));
            planRepo.saveAndFlush(samplePlan("plan-disabled", false, now.minusMinutes(5)));
            planRepo.saveAndFlush(samplePlan("plan-both-off", false, now.plusHours(1)));
        });
        em.clear();

        List<InspectionPlan> due = planRepo.findByEnabledTrueAndNextRunAtLessThanEqual(now);
        assertThat(due)
                .as("应仅返回 enabled=true 且 next_run_at <= now 的 1 条")
                .hasSize(1);
        assertThat(due.get(0).getPlanId()).isEqualTo("plan-due");
    }

    // ----- 5. PESSIMISTIC_WRITE 行锁串行化 -----

    @Test
    @DisplayName("findByPlanIdForUpdate 持锁期间,另一事务必须阻塞等待直到持锁事务提交")
    void pessimisticWriteLock_serializesClaims() throws Exception {
        // REQUIRES_NEW:确保 plan 在异步线程启动前已提交,跨连接可见
        newRequiresNew().executeWithoutResult(s ->
                planRepo.saveAndFlush(samplePlan("plan-lock", true,
                        LocalDateTime.now().plusDays(1))));
        final String planId = "plan-lock";

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch firstLockHeld = new CountDownLatch(1);
        CountDownLatch releaseFirstLock = new CountDownLatch(1);
        AtomicReference<Throwable> secondTxError = new AtomicReference<>();
        AtomicReference<String> secondTxObservedName = new AtomicReference<>();

        try {
            // 事务 A:持锁,睡眠 600ms 后改名为 "updated-by-first" 提交
            CompletableFuture<Void> firstTx = CompletableFuture.runAsync(() ->
                    tx.executeWithoutResult(status -> {
                        try {
                            InspectionPlan locked = planRepo.findByPlanIdForUpdate(planId)
                                    .orElseThrow(() -> new AssertionError("plan must exist"));
                            firstLockHeld.countDown();
                            releaseFirstLock.await(5, TimeUnit.SECONDS);
                            locked.setName("updated-by-first");
                            planRepo.saveAndFlush(locked);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } catch (Throwable t) {
                            secondTxError.compareAndSet(null, t);
                        }
                    }), executor);

            // 等 A 持锁
            assertThat(firstLockHeld.await(5, TimeUnit.SECONDS))
                    .as("事务 A 必须在 5s 内获取行锁")
                    .isTrue();

            // 事务 B:从独立线程/连接尝试锁,必须阻塞,直到 A 释放后才能看到更新
            long bStart = System.currentTimeMillis();
            CompletableFuture<Void> secondTx = CompletableFuture.runAsync(() -> {
                try {
                    tx.executeWithoutResult(status -> {
                        InspectionPlan lockedB = planRepo.findByPlanIdForUpdate(planId)
                                .orElseThrow(() -> new AssertionError("plan must exist"));
                        secondTxObservedName.set(lockedB.getName());
                    });
                } catch (Throwable t) {
                    secondTxError.compareAndSet(null, t);
                }
            }, executor);

            // 让 B 有机会排队等待
            Thread.sleep(150);

            // 释放 A,B 应能完成
            releaseFirstLock.countDown();
            firstTx.get(5, TimeUnit.SECONDS);
            secondTx.get(5, TimeUnit.SECONDS);

            long bElapsed = System.currentTimeMillis() - bStart;
            assertThat(secondTxError.get())
                    .as("事务 B 不应出错,行锁串行化后必须能拿到锁")
                    .isNull();
            assertThat(secondTxObservedName.get())
                    .as("事务 B 必须看到事务 A 提交后的更新(updated-by-first),而不是旧 name")
                    .isEqualTo("updated-by-first");
            assertThat(bElapsed)
                    .as("事务 B 必须等事务 A 释放后才能继续(等待 > 100ms)")
                    .isGreaterThan(100L);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    // ----- 6. 同计划重入 SKIPPED -----

    @Test
    @DisplayName("同计划 RUNNING 执行存在时,新事务感知并写入 SKIPPED")
    void skippedExecution_whenRunningExists() {
        // 准备 plan
        newRequiresNew().executeWithoutResult(s ->
                planRepo.saveAndFlush(samplePlan("plan-reentry", true,
                        LocalDateTime.now().plusDays(1))));

        // 第一次触发:RUNNING — 在独立事务中持锁 + 写入
        newRequiresNew().executeWithoutResult(s -> {
            InspectionPlan locked = planRepo.findByPlanIdForUpdate("plan-reentry")
                    .orElseThrow();
            boolean hasRunning = execRepo.existsByPlanIdAndStatus(
                    "plan-reentry", InspectionExecutionStatus.RUNNING);
            assertThat(hasRunning).as("首次触发时不应存在 RUNNING").isFalse();
            execRepo.save(sampleExecution("plan-reentry", InspectionExecutionStatus.RUNNING,
                    InspectionTriggerType.SCHEDULED, "SYSTEM_SCHEDULER"));
        });

        // 第二次触发:应识别 RUNNING,写 SKIPPED
        newRequiresNew().executeWithoutResult(s -> {
            InspectionPlan locked = planRepo.findByPlanIdForUpdate("plan-reentry")
                    .orElseThrow();
            boolean hasRunning = execRepo.existsByPlanIdAndStatus(
                    "plan-reentry", InspectionExecutionStatus.RUNNING);
            assertThat(hasRunning).as("第二次触发时 RUNNING 必须存在").isTrue();
            InspectionExecution skipped = sampleExecution("plan-reentry",
                    InspectionExecutionStatus.SKIPPED,
                    InspectionTriggerType.SCHEDULED, "SYSTEM_SCHEDULER");
            execRepo.save(skipped);
        });

        List<InspectionExecution> all = execRepo.findByPlanIdOrderByStartedAtDesc(
                "plan-reentry", org.springframework.data.domain.PageRequest.of(0, 10))
                .getContent();
        assertThat(all)
                .as("应有 2 条执行记录(1 RUNNING + 1 SKIPPED)")
                .hasSize(2);
        long skippedCount = all.stream()
                .filter(e -> e.getStatus() == InspectionExecutionStatus.SKIPPED).count();
        assertThat(skippedCount).isEqualTo(1L);
    }

    // ----- 7. 启动期恢复 abandoned RUNNING -----

    @Test
    @DisplayName("findAllByStatus(RUNNING) 返回所有遗留的 RUNNING 执行(abandoned 恢复用)")
    void startupRecovery_findsAllRunningExecutions() {
        // 在 REQUIRES_NEW 事务中准备数据
        String[] abandonedIds = new String[2];
        newRequiresNew().executeWithoutResult(s -> {
            InspectionPlan plan = planRepo.saveAndFlush(samplePlan("plan-recover", true,
                    LocalDateTime.now().plusDays(1)));
            InspectionExecution abandoned1 = sampleExecution(plan.getPlanId(),
                    InspectionExecutionStatus.RUNNING, InspectionTriggerType.SCHEDULED,
                    "SYSTEM_SCHEDULER");
            abandoned1.setStartedAt(LocalDateTime.now().minusHours(2));
            execRepo.save(abandoned1);

            InspectionExecution abandoned2 = sampleExecution(plan.getPlanId(),
                    InspectionExecutionStatus.RUNNING, InspectionTriggerType.SCHEDULED,
                    "SYSTEM_SCHEDULER");
            abandoned2.setStartedAt(LocalDateTime.now().minusMinutes(30));
            execRepo.save(abandoned2);
            abandonedIds[0] = abandoned1.getExecutionId();
            abandonedIds[1] = abandoned2.getExecutionId();

            InspectionExecution finished = sampleExecution(plan.getPlanId(),
                    InspectionExecutionStatus.SUCCESS, InspectionTriggerType.MANUAL,
                    "admin");
            finished.setFinishedAt(LocalDateTime.now().minusMinutes(1));
            execRepo.save(finished);
        });

        List<InspectionExecution> running = execRepo.findAllByStatus(
                InspectionExecutionStatus.RUNNING);
        assertThat(running)
                .as("启动期查询必须列出全部 RUNNING 执行,包括 abandoned")
                .hasSize(2);
        assertThat(running).extracting(InspectionExecution::getExecutionId)
                .containsExactlyInAnyOrder(abandonedIds[0], abandonedIds[1]);
    }

    /**
     * 构造一个 REQUIRES_NEW 传播的 TransactionTemplate — 挂起 @DataJpaTest 外层
     * 事务,在独立事务中提交/回滚,避免异步线程看不到未提交数据,以及唯一约束
     * 异常污染外层事务的 rollback-only 标记。
     */
    private TransactionTemplate newRequiresNew() {
        TransactionTemplate t = new TransactionTemplate(txManager);
        t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return t;
    }
}
