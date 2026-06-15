package com.kylinops.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * 验证 {@link AgentOrchestrator#parallelExecutor} 在高并发突发场景下的行为：
 * <ul>
 *   <li>不再因 {@link java.util.concurrent.SynchronousQueue} 满而抛 {@link RejectedExecutionException}</li>
 *   <li>队列满时由调用线程（CallerRunsPolicy）兜底执行</li>
 *   <li>队列容量足以缓冲 8 工具 × 多请求突发</li>
 * </ul>
 *
 * <p>Spring Boot 启动时 AgentOrchestrator 即创建 parallelExecutor 字段，
 * 因此直接通过反射读字段即可拿到单例 executor。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("AgentOrchestrator.parallelExecutor — 高并发降级")
class AgentOrchestratorExecutorTest {

    @Autowired
    private AgentOrchestrator orchestrator;

    @Test
    @DisplayName("parallelExecutor 是 bounded + CallerRunsPolicy + 足够 maxPool")
    void parallelExecutorHasBoundedQueueAndCallerRunsPolicy() throws Exception {
        ThreadPoolExecutor pool = extractParallelExecutor();

        // corePool=4
        assertThat(pool.getCorePoolSize())
                .as("corePool should be 4")
                .isEqualTo(4);
        // maxPool >= 8（任务规范要求覆盖未来 8-12 工具并行）
        assertThat(pool.getMaximumPoolSize())
                .as("maxPool should cover 8+ tools (>= 8)")
                .isGreaterThanOrEqualTo(8);
        // keepAlive 60s
        assertThat(pool.getKeepAliveTime(TimeUnit.SECONDS))
                .as("keepAlive should be 60s")
                .isEqualTo(60L);
        // 队列有界且容量 >= 64
        assertThat(pool.getQueue())
                .as("queue should be bounded to prevent OOM")
                .isNotInstanceOf(java.util.concurrent.SynchronousQueue.class);
        int queueCapacity = pool.getQueue().size() + pool.getQueue().remainingCapacity();
        assertThat(queueCapacity)
                .as("queue capacity should be >= 64")
                .isGreaterThanOrEqualTo(64);
        // 显式 CallerRunsPolicy
        assertThat(pool.getRejectedExecutionHandler())
                .as("rejected handler should be CallerRunsPolicy")
                .isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
        // daemon thread factory
        Thread t = pool.getThreadFactory().newThread(() -> { });
        assertThat(t.isDaemon())
                .as("pool threads should be daemon")
                .isTrue();
        assertThat(t.getName())
                .as("pool threads should be named 'agent-parallel-*'")
                .startsWith("agent-parallel-");
    }

    @Test
    @DisplayName("提交 20 个 sleep 任务不应抛 RejectedExecutionException")
    void parallelExecutorHandlesMoreThanEightConcurrentTasks() throws Exception {
        ThreadPoolExecutor pool = extractParallelExecutor();

        int taskCount = 20;
        long sleepMs = 200L;
        List<CompletableFuture<String>> futures = new ArrayList<>();
        Set<String> executingThreadNames = ConcurrentHashMap.newKeySet();

        for (int i = 0; i < taskCount; i++) {
            int idx = i;
            CompletableFuture<String> future;
            try {
                future = CompletableFuture.supplyAsync(() -> {
                    String name = Thread.currentThread().getName();
                    executingThreadNames.add(name);
                    try {
                        Thread.sleep(sleepMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return "task-" + idx + ":" + name;
                }, pool);
            } catch (RejectedExecutionException e) {
                fail("task %d should not be rejected: %s", e, idx, e.getMessage());
                return;
            }
            futures.add(future);
        }

        // 等待全部完成（带 30s 上限）
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(30, TimeUnit.SECONDS);

        // 全部完成且无 rejected
        for (int i = 0; i < taskCount; i++) {
            String result = futures.get(i).get();
            assertThat(result)
                    .as("task %d should complete", i)
                    .startsWith("task-" + i + ":");
        }

        // 至少观察到 pool 线程在跑（说明池被实际使用）
        boolean hasPoolThread = executingThreadNames.stream()
                .anyMatch(name -> name.startsWith("agent-parallel-"));
        assertThat(hasPoolThread)
                .as("at least some tasks should run on agent-parallel-* pool threads")
                .isTrue();
    }

    @Test
    @DisplayName("线程池+队列全满时，超额任务走 CallerRunsPolicy 不抛异常")
    void rejectedTaskFallsBackToCallerRunsPolicy() throws Exception {
        ThreadPoolExecutor pool = extractParallelExecutor();
        int maxPool = pool.getMaximumPoolSize();
        int queueCap = pool.getQueue().size() + pool.getQueue().remainingCapacity();

        // 阻塞 maxPool 个工作线程（每个等同一个 latch）
        CountDownLatch holdLatch = new CountDownLatch(1);
        List<CompletableFuture<String>> blockers = new ArrayList<>();
        for (int i = 0; i < maxPool; i++) {
            int idx = i;
            blockers.add(CompletableFuture.supplyAsync(() -> {
                try {
                    holdLatch.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "blocker-" + idx;
            }, pool));
        }
        // 等待所有 maxPool 个工作线程都进入 active 状态
        awaitPoolBusy(pool, maxPool, 5_000L);

        // 再把队列填满（queueCap 个任务排队）
        for (int i = 0; i < queueCap; i++) {
            try {
                pool.execute(() -> {
                    try {
                        holdLatch.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            } catch (RejectedExecutionException e) {
                // 释放避免悬挂
                holdLatch.countDown();
                CompletableFuture.allOf(blockers.toArray(new CompletableFuture[0]))
                        .get(30, TimeUnit.SECONDS);
                fail("queue fill task %d should not be rejected (queue capacity is %d): %s",
                        e, i, queueCap, e.getMessage());
            }
        }
        // 等待队列里也确认填满
        long deadline = System.currentTimeMillis() + 2_000L;
        while (pool.getQueue().size() < queueCap && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
        assertThat(pool.getQueue().size())
                .as("queue should be full (size=%d)", queueCap)
                .isEqualTo(queueCap);

        // 此时再提交一个任务，pool+queue 全部满
        // → CallerRunsPolicy 会在调用线程（测试主线程）同步执行
        // → 调用线程会进入 holdLatch.await → 不会立即返回
        // → 这正是 CallerRunsPolicy 的语义：不让任务被丢弃
        CountDownLatch overflowStarted = new CountDownLatch(1);
        CountDownLatch overflowFinished = new CountDownLatch(1);
        try {
            pool.execute(() -> {
                overflowStarted.countDown();
                overflowFinished.countDown();
            });
        } catch (RejectedExecutionException e) {
            holdLatch.countDown();
            CompletableFuture.allOf(blockers.toArray(new CompletableFuture[0]))
                    .get(30, TimeUnit.SECONDS);
            fail("over-capacity task should fall back to CallerRunsPolicy, not throw: %s",
                    e, e.getMessage());
        }

        // CallerRunsPolicy 已经在调用线程（测试主线程）上同步执行了 execute 块
        // 当线程池满+队列满，pool.execute 的 CallerRunsPolicy 路径会同步执行任务
        // 因为我们 release 了 holdLatch（没有 release），任务其实在等 holdLatch
        // 释放所有阻塞，验证整个流程不抛异常
        holdLatch.countDown();
        CompletableFuture.allOf(blockers.toArray(new CompletableFuture[0]))
                .get(30, TimeUnit.SECONDS);

        // overflowFinished 是否被 countDown？只有当 overflow 任务真正执行过才会
        // CallerRunsPolicy 会同步执行任务（即使有 holdLatch.await），任务内的
        // overflowStarted.countDown() 和 overflowFinished.countDown() 会在 await 之前执行
        assertThat(overflowStarted.getCount())
                .as("CallerRunsPolicy task should have started (synchronously in caller thread)")
                .isZero();
    }

    /**
     * 等待线程池进入「所有工作线程都 active」状态。
     * 由于 ThreadPoolExecutor 默认懒创建线程，需要等到 getPoolSize() == maxPool
     * 且 getActiveCount() == maxPool 才算真正忙。
     */
    private void awaitPoolBusy(ThreadPoolExecutor pool, int maxPool, long maxWaitMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + maxWaitMs;
        while (System.currentTimeMillis() < deadline) {
            int poolSize = pool.getPoolSize();
            int active = pool.getActiveCount();
            if (poolSize >= maxPool && active >= maxPool) {
                return;
            }
            Thread.sleep(20L);
        }
    }

    private ThreadPoolExecutor extractParallelExecutor() throws Exception {
        Field field = AgentOrchestrator.class.getDeclaredField("parallelExecutor");
        field.setAccessible(true);
        Object value = field.get(orchestrator);
        assertThat(value)
                .as("parallelExecutor field must exist")
                .isNotNull()
                .isInstanceOf(ThreadPoolExecutor.class);
        return (ThreadPoolExecutor) value;
    }
}
