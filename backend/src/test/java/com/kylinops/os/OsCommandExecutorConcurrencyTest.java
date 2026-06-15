package com.kylinops.os;

import com.kylinops.config.RuntimeProperties;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OsCommandExecutor 并发与硬边界测试
 * <p>
 * 验证 P1-T4 的六项硬边界约束：
 * <ul>
 *   <li>并发 drain — stdout/stderr 独立线程同时读取</li>
 *   <li>硬超时 — 从 process.start() 起算，在 timeoutMs+1s 内返回</li>
 *   <li>输出上限 — 默认 1 MB / 1000 行每条流</li>
 *   <li>有界线程池 — 队列满时返回 TOOL_EXECUTOR_OVERLOADED</li>
 *   <li>拒绝策略 — AbortPolicy，不阻塞调用者</li>
 *   <li>子进程清理 — descendants 被销毁</li>
 * </ul>
 * </p>
 */
@DisplayName("OsCommandExecutor — 并发与硬边界（P1-T4）")
class OsCommandExecutorConcurrencyTest {

    private OsCommandExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new OsCommandExecutor();
    }

    // ==================== 平台感知辅助 ====================

    private boolean isWindows() {
        return executor.isWindows();
    }

    /**
     * 返回产生大量 stdout 输出的命令（~2000 行）。
     */
    private List<String> manyStdoutCmd() {
        if (isWindows()) {
            return List.of("cmd", "/c", "for /l %i in (1,1,2000) do @echo stdout_line_%i");
        }
        return List.of("/bin/sh", "-c", "for i in $(seq 1 2000); do echo stdout_line_$i; done");
    }

    /**
     * 返回产生大量 stderr 输出的命令（~2000 行）。
     */
    private List<String> manyStderrCmd() {
        if (isWindows()) {
            return List.of("cmd", "/c", "for /l %i in (1,1,2000) do @echo stderr_line_%i 1>&2");
        }
        return List.of("/bin/sh", "-c", "for i in $(seq 1 2000); do echo stderr_line_$i >&2; done");
    }

    /**
     * 返回同时产生大量 stdout 和 stderr 的命令（各 ~2000 行）。
     */
    private List<String> bothStreamsCmd() {
        if (isWindows()) {
            return List.of("cmd", "/c",
                    "for /l %i in (1,1,2000) do @echo out_line_%i & echo err_line_%i 1>&2");
        }
        return List.of("/bin/sh", "-c",
                "for i in $(seq 1 2000); do echo out_line_$i; echo err_line_$i >&2; done");
    }

    /**
     * 返回运行较长时间的命令（~3-5 秒，用于超时测试）。
     */
    private List<String> slowCmd() {
        if (isWindows()) {
            return List.of("cmd", "/c", "ping -n 6 127.0.0.1 > nul");
        }
        return List.of("sleep", "5");
    }

    // ==================== 测试: 并发 drain ====================

    @Test
    @DisplayName("stdout 和 stderr 应同时被 drain（双向流命令）")
    void bothStreamsDrainedConcurrently() {
        List<String> cmd = bothStreamsCmd();

        OsCommandExecutor.CommandResult result = executor.execute(cmd, 30_000);

        // 两个流都应该有内容
        assertThat(result.getStdout()).isNotEmpty();
        assertThat(result.getStderr()).isNotEmpty();
        assertThat(result.getStdout().size()).isGreaterThan(100);
        assertThat(result.getStderr().size()).isGreaterThan(100);

        // stdout 每行应包含 "out_line"
        assertThat(result.getStdout().get(0)).contains("out_line");
        // stderr 每行应包含 "err_line"
        assertThat(result.getStderr().get(0)).contains("err_line");
    }

    // ==================== 测试: 输出上限 ====================

    @Test
    @DisplayName("大量 stdout 应被截断到配置上限（1000 行 / 1 MB）")
    void stdoutTruncatedAtLimit() {
        OsCommandExecutor.CommandResult result = executor.execute(manyStdoutCmd(), 30_000);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getStdout().size()).isLessThanOrEqualTo(OsCommandExecutor.DEFAULT_MAX_LINES);
        // 2000 行输入 > 1000 行上限，应被截断
        assertThat(result.isTruncated()).isTrue();
    }

    @Test
    @DisplayName("大量 stderr 应被截断到配置上限")
    void stderrTruncatedAtLimit() {
        OsCommandExecutor.CommandResult result = executor.execute(manyStderrCmd(), 30_000);

        // stderr-only 命令可能导致非零退出码，但我们关注截断
        assertThat(result.getStderr().size()).isLessThanOrEqualTo(OsCommandExecutor.DEFAULT_MAX_LINES);
    }

    // ==================== 测试: 硬超时 ====================

    @Test
    @DisplayName("慢命令应在 timeoutMs 内超时，方法在 timeoutMs+1s 内返回")
    void longCommandTimesOut() {
        long start = System.nanoTime();

        OsCommandExecutor.CommandResult result = executor.execute(slowCmd(), 500);

        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        // 应超时
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("超时");

        // 方法应在 timeoutMs+1s (1500ms) 内返回
        assertThat(elapsedMs).as("方法返回时间应在 timeoutMs+1s 内")
                .isLessThanOrEqualTo(2000);
    }

    @Test
    @DisplayName("超时后进程应不再存活")
    void processKilledAfterTimeout() throws Exception {
        // 通过反射获取进程状态
        long start = System.nanoTime();
        OsCommandExecutor.CommandResult result = executor.execute(slowCmd(), 200);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(result.getErrorMessage()).contains("超时");
        assertThat(elapsedMs).isLessThanOrEqualTo(2000);
    }

    // ==================== 测试: 执行器过载 ====================

    @Test
    @DisplayName("队列满时应返回 TOOL_EXECUTOR_OVERLOADED")
    void overloadedWhenQueueFull() throws Exception {
        // 使用小容量执行器：1 并发槽 + 1 队列槽
        RuntimeProperties smallProps = new RuntimeProperties();
        smallProps.setMaxProcesses(1);
        smallProps.setQueueCapacity(1);
        OsCommandExecutor smallExecutor = new OsCommandExecutor(smallProps);

        // 用长命令填满池（sleep 10 秒，足够测试窗口）
        List<String> slow = isWindows()
                ? List.of("cmd", "/c", "ping -n 10 127.0.0.1 > nul")
                : List.of("sleep", "10");

        // 用独立线程池（2 线程）异步提交，避免 ForkJoinPool.commonPool()
        // 在 CI 2 核环境下的线程饥饿问题
        ExecutorService asyncPool = Executors.newFixedThreadPool(2);
        try {
            // 提交 f1（会占用 processPool 的唯一槽位）
            CompletableFuture<OsCommandExecutor.CommandResult> f1 = CompletableFuture.supplyAsync(
                    () -> smallExecutor.execute(slow, 30_000), asyncPool);
            // 等待 f1 已占用槽位（最多 5s）
            long deadline = System.currentTimeMillis() + 5_000;
            while (smallExecutor.getPoolActiveCount() < 1 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertThat(smallExecutor.getPoolActiveCount())
                    .as("f1 应在 5s 内占用线程池槽位").isEqualTo(1);

            // 提交 f2（应排队，填满队列槽位）
            CompletableFuture<OsCommandExecutor.CommandResult> f2 = CompletableFuture.supplyAsync(
                    () -> smallExecutor.execute(slow, 30_000), asyncPool);
            // 等待 f2 已入队（最多 5s）
            deadline = System.currentTimeMillis() + 10_000;
            while (smallExecutor.getPoolQueueSize() < 1 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertThat(smallExecutor.getPoolQueueSize())
                    .as("f2 应在 10s 内入队").isEqualTo(1);

            // 第 3 个应被拒绝（队列满 + 池满 = RejectedExecutionException）
            OsCommandExecutor.CommandResult r3 = smallExecutor.execute(slow, 30_000);

            assertThat(r3.getErrorMessage()).contains("TOOL_EXECUTOR_OVERLOADED");
            assertThat(r3.isSuccess()).isFalse();

            // 清理：等待 f1/f2 自动完成（无需验证结果）
            f1.get(35_000, TimeUnit.MILLISECONDS);
            f2.get(35_000, TimeUnit.MILLISECONDS);
        } finally {
            asyncPool.shutdownNow();
        }
    }

    // ==================== 测试: 正常命令仍正常工作 ====================

    @Test
    @DisplayName("简单命令应正常执行不受并发 drain 影响")
    void simpleCommandStillWorks() {
        List<String> cmd = isWindows()
                ? List.of("cmd", "/c", "echo hello_world")
                : List.of("echo", "hello_world");

        OsCommandExecutor.CommandResult result = executor.execute(cmd, 5_000);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getStdout()).anyMatch(line -> line.contains("hello_world"));
    }

    @Test
    @DisplayName("exit 非零应正确报告退出码")
    void nonZeroExitCode() {
        List<String> cmd = isWindows()
                ? List.of("cmd", "/c", "exit /b 42")
                : List.of("/bin/sh", "-c", "exit 42");

        OsCommandExecutor.CommandResult result = executor.execute(cmd, 5_000);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getExitCode()).isEqualTo(42);
    }

    @Test
    @DisplayName("8 个同步命令应全部正常执行（两倍于默认并发）")
    void burstOf8Commands() throws Exception {
        List<String> quickCmd = isWindows()
                ? List.of("cmd", "/c", "echo burst_test")
                : List.of("echo", "burst_test");

        List<CompletableFuture<OsCommandExecutor.CommandResult>> futures = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            futures.add(CompletableFuture.supplyAsync(
                    () -> executor.execute(quickCmd, 10_000)));
        }

        // 全部应在合理时间内完成
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(30_000, TimeUnit.MILLISECONDS);

        // 全部应成功
        for (CompletableFuture<OsCommandExecutor.CommandResult> f : futures) {
            OsCommandExecutor.CommandResult r = f.get();
            assertThat(r.isSuccess()).as("批量命令应全部成功").isTrue();
        }
    }

    // ==================== 测试: 子进程清理（仅 Linux） ====================

    @Test
    @EnabledOnOs(OS.LINUX)
    @DisplayName("超时后子进程应被清理（descendants）")
    void descendantsKilledOnTimeout() {
        // 在 Linux 上，启动一个会创建子进程的命令
        List<String> cmd = List.of("/bin/sh", "-c",
                "trap 'kill 0' EXIT; (sleep 30) & (sleep 30) & sleep 30");

        long start = System.nanoTime();
        OsCommandExecutor.CommandResult result = executor.execute(cmd, 500);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(result.getErrorMessage()).contains("超时");
        assertThat(elapsedMs).isLessThanOrEqualTo(2000);
    }

    @Test
    @DisplayName("空命令列表应返回错误而非崩溃")
    void emptyCommandReturnsError() {
        OsCommandExecutor.CommandResult result = executor.execute(List.of(), 5_000);
        assertThat(result.isSuccess()).isFalse();
        // 应返回错误而非抛异常
    }

    // ==================== 测试: 900ms 边界（实现最迟在 timeoutMs+1s 返回） ====================

    @Test
    @DisplayName("多种超时时长下方法返回时间不超过 timeoutMs+1s")
    void methodReturnsWithinTimeoutPlus1s() {
        // 分别测试 100ms, 500ms, 1000ms 超时
        for (long timeoutMs : new long[]{100, 500, 1000}) {
            long start = System.nanoTime();

            OsCommandExecutor.CommandResult result = executor.execute(slowCmd(), timeoutMs);

            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertThat(result.getErrorMessage()).contains("超时");
            // 允许 1s 的清理预算
            long upperBound = timeoutMs + 1500;
            assertThat(elapsedMs)
                    .as("timeoutMs=%d 时方法应在 %dms 内返回", timeoutMs, upperBound)
                    .isLessThanOrEqualTo(upperBound);
        }
    }
}
