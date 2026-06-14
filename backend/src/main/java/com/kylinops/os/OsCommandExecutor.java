package com.kylinops.os;

import com.kylinops.config.RuntimeProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OS 命令执行器
 * <p>
 * 封装 {@link ProcessBuilder} 的创建、执行、超时控制、输出截断和异常处理。
 * 所有 OS 感知 OpsTool 通过此执行器运行系统命令，禁止直接创建 ProcessBuilder。
 * </p>
 *
 * <h3>硬边界（P1-T4）</h3>
 * <ul>
 *   <li>并发 drain — stdout/stderr 独立线程同时读取</li>
 *   <li>硬超时 — 从 process.start() 起算，超时后 destroyForcibly + 清理子进程</li>
 *   <li>输出上限 — 默认 1 MB / 1000 行每条流</li>
 *   <li>有界线程池 — 最大 8 并发进程，等待队列 32</li>
 *   <li>拒绝策略 — AbortPolicy，返回 {@link CommandResult#overloaded(String)}</li>
 *   <li>子进程清理 — {@link ProcessHandle#descendants()} destroyForcibly</li>
 *   <li>方法最迟在 timeoutMs+1s 内返回</li>
 * </ul>
 */
@Slf4j
@Component
public class OsCommandExecutor {

    /** 最大输出行数（未配置 RuntimeProperties 时的默认值） */
    static final int DEFAULT_MAX_LINES = 1000;

    /** 最大输出字节数 (1 MB) */
    static final int DEFAULT_MAX_BYTES = 1_048_576;

    /** 默认最大并发进程数 */
    static final int DEFAULT_MAX_PROCESSES = 8;

    /** 默认等待队列容量 */
    static final int DEFAULT_QUEUE_CAPACITY = 32;

    private final RuntimeProperties props;
    private final ThreadPoolExecutor processPool;

    /**
     * 构造带运行时属性的执行器（Spring 注入用）。
     */
    public OsCommandExecutor(RuntimeProperties props) {
        this.props = props;
        this.processPool = createProcessPool();
        log.info("OsCommandExecutor 初始化: maxProcesses={}, queueCapacity={}, maxLines={}, maxBytes={}",
                props.getMaxProcesses(), props.getQueueCapacity(),
                props.getMaxLinesPerStream(), props.getMaxBytesPerStream());
    }

    /**
     * 无参构造（测试直接实例化用），使用内置默认值。
     */
    public OsCommandExecutor() {
        this.props = null; // 使用静态常量
        this.processPool = createDefaultPool();
    }

    /**
     * 创建有界线程池（生产路径）。
     */
    private ThreadPoolExecutor createProcessPool() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                props.getMaxProcesses(), props.getMaxProcesses(),
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(props.getQueueCapacity()),
                new ThreadPoolExecutor.AbortPolicy()
        );
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    /**
     * 创建默认线程池（测试路径）。
     */
    private ThreadPoolExecutor createDefaultPool() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                DEFAULT_MAX_PROCESSES, DEFAULT_MAX_PROCESSES,
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(DEFAULT_QUEUE_CAPACITY),
                new ThreadPoolExecutor.AbortPolicy()
        );
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    // ==================== 公共方法 ====================

    /**
     * 执行系统命令并返回结果。
     * <p>
     * 命令以固定参数列表执行（禁止拼接用户输入），
     * 自动处理并发 drain、超时、截断、子进程清理和异常。
     * </p>
     *
     * @param command   命令 + 参数列表（如 ["df", "-h"]）
     * @param timeoutMs 超时阈值（毫秒），从 process.start() 起算
     * @return 命令执行结果（永远不会返回 null）
     */
    public CommandResult execute(List<String> command, long timeoutMs) {
        long start = System.nanoTime();

        if (log.isDebugEnabled()) {
            log.debug("执行命令: {}", String.join(" ", command));
        }

        try {
            // 提交到有界线程池，等待执行槽
            Future<CommandResult> future = processPool.submit(() ->
                    executeInternal(command, timeoutMs));

            // 整体超时兜底：timeoutMs + cleanupBudget
            long totalTimeout = timeoutMs + (props != null
                    ? props.getCleanupBudget().toMillis()
                    : 1000);
            return future.get(totalTimeout, TimeUnit.MILLISECONDS);

        } catch (RejectedExecutionException e) {
            long elapsedNs = System.nanoTime() - start;
            log.warn("执行器队列已满，拒绝命令: {}", String.join(" ", command));
            return CommandResult.overloaded("执行器队列已满（最大 " + getMaxProcesses() + " 并发），请稍后重试", elapsedNs);

        } catch (TimeoutException e) {
            long elapsedNs = System.nanoTime() - start;
            log.warn("等待执行槽超时 ({}ms): {}", timeoutMs, String.join(" ", command));
            return new CommandResult(-1, List.of(), List.of(), false,
                    "等待执行槽超时（阈值: " + timeoutMs + "ms）", elapsedNs);

        } catch (ExecutionException e) {
            long elapsedNs = System.nanoTime() - start;
            Throwable cause = e.getCause();
            String msg = cause != null ? cause.getMessage() : "未知执行异常";
            log.debug("命令执行异常: {}", msg);
            return new CommandResult(-1, List.of(), List.of(), false,
                    "执行异常: " + msg, elapsedNs);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            long elapsedNs = System.nanoTime() - start;
            return new CommandResult(-1, List.of(), List.of(), false,
                    "执行被中断", elapsedNs);
        }
    }

    /**
     * 执行命令体（在线程池中运行）。
     */
    private CommandResult executeInternal(List<String> command, long timeoutMs) {
        long processStart = System.nanoTime();
        List<String> stdout = new ArrayList<>();
        List<String> stderr = new ArrayList<>();
        AtomicBoolean truncated = new AtomicBoolean(false);
        Process process = null;

        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            Process proc = pb.start();
            process = proc; // 赋值给外层变量用于 finally 清理

            // 并发 drain stdout 和 stderr（使用 proc 局部变量，可被 lambda 有效 final 捕获）
            CompletableFuture<List<String>> stdoutFuture = CompletableFuture.supplyAsync(
                    () -> drainStream(proc.getInputStream(), truncated));
            CompletableFuture<List<String>> stderrFuture = CompletableFuture.supplyAsync(
                    () -> drainStream(proc.getErrorStream(), truncated));

            // 硬超时：从 process.start() 起算
            boolean finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS);

            if (!finished) {
                // 超时 — 先清理子进程再 destroy
                destroyDescendants(proc);
                proc.destroyForcibly();

                // 等待 drain 完成（在清理预算内）
                long graceMs = props != null
                        ? props.getGracefulKill().toMillis()
                        : 250;
                quietSleep(graceMs);

                // 获取已 drain 的内容
                try {
                    stdout = stdoutFuture.getNow(new ArrayList<>());
                    stderr = stderrFuture.getNow(new ArrayList<>());
                } catch (Exception ignored) {
                    // 忽略 drain 异常
                }

                long elapsedNs = System.nanoTime() - processStart;
                log.warn("命令执行超时 ({}ms): {}", timeoutMs, String.join(" ", command));
                return new CommandResult(-1, stdout, stderr, true,
                        "工具执行超时（阈值: " + timeoutMs + "ms）", elapsedNs);
            }

            // 进程正常结束 — 等待 drain 完成
            try {
                stdout = stdoutFuture.get(100, TimeUnit.MILLISECONDS);
                stderr = stderrFuture.get(100, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                // drain 应已完成；用 getNow 兜底
                stdout = stdoutFuture.getNow(stdout);
                stderr = stderrFuture.getNow(stderr);
            }

            int exitCode = proc.exitValue();
            String errorMessage = null;
            if (exitCode != 0) {
                String stderrText = stderr.isEmpty() ? "" : ", stderr: " + String.join("\n", stderr);
                errorMessage = "命令退出码: " + exitCode + stderrText;
                log.debug("命令执行异常退出 (exit={}): {}", exitCode, String.join(" ", command));
            }

            long elapsedNs = System.nanoTime() - processStart;
            return new CommandResult(exitCode, stdout, stderr, truncated.get(), errorMessage, elapsedNs);

        } catch (IOException e) {
            log.debug("命令 IO 异常: {}", e.getMessage());
            long elapsedNs = System.nanoTime() - processStart;
            return new CommandResult(-1, stdout, stderr, false,
                    "IO 异常: " + e.getMessage(), elapsedNs);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            long elapsedNs = System.nanoTime() - processStart;
            return new CommandResult(-1, stdout, stderr, false,
                    "执行被中断", elapsedNs);

        } catch (Exception e) {
            log.debug("命令执行未知异常", e);
            long elapsedNs = System.nanoTime() - processStart;
            return new CommandResult(-1, stdout, stderr, false,
                    "未知异常: " + e.getMessage(), elapsedNs);

        } finally {
            if (process != null && process.isAlive()) {
                destroyDescendants(process);
                process.destroyForcibly();
            }
        }
    }

    /**
     * 并发 drain 一条流（stdout 或 stderr）。
     * <p>
     * 在 CompletableFuture 中运行，读取行并应用上限截断。
     * </p>
     */
    private List<String> drainStream(InputStream inputStream, AtomicBoolean truncated) {
        List<String> lines = new ArrayList<>();
        int maxLines = props != null ? props.getMaxLinesPerStream() : DEFAULT_MAX_LINES;
        int maxBytes = props != null ? props.getMaxBytesPerStream() : DEFAULT_MAX_BYTES;
        int totalBytes = 0;
        boolean localTruncated = false;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!localTruncated && lines.size() < maxLines && totalBytes < maxBytes) {
                    lines.add(line);
                    totalBytes += line.getBytes(StandardCharsets.UTF_8).length;
                    if (lines.size() >= maxLines || totalBytes >= maxBytes) {
                        localTruncated = true;
                    }
                }
            }
        } catch (IOException e) {
            // 进程销毁后流关闭是正常的
            if (log.isTraceEnabled()) {
                log.trace("流 drain 关闭: {}", e.getMessage());
            }
        }

        if (localTruncated) {
            truncated.set(true);
        }
        return lines;
    }

    /**
     * 清理目标进程的所有子进程。
     */
    private void destroyDescendants(Process process) {
        if (process != null) {
            try {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
            } catch (Exception e) {
                log.trace("清理子进程异常: {}", e.getMessage());
            }
        }
    }

    /**
     * 安静的线程休眠（不抛 InterruptedException）。
     */
    private void quietSleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    // ==================== 无需并发控制的公共方法 ====================

    /**
     * 读取一个文本文件的内容（用于读取 /proc 等伪文件系统中的文件）。
     * <p>
     * 不会抛出异常，错误通过 {@link FileReadResult} 返回。
     * </p>
     *
     * @param filePath 文件路径
     * @return 文件读取结果
     */
    public FileReadResult readFile(String filePath) {
        List<String> lines = new ArrayList<>();
        String errorMessage = null;

        try {
            java.nio.file.Path path = java.nio.file.Paths.get(filePath);
            if (!java.nio.file.Files.exists(path)) {
                return new FileReadResult(null, "文件不存在: " + filePath);
            }
            if (!java.nio.file.Files.isReadable(path)) {
                return new FileReadResult(null, "文件不可读: " + filePath);
            }
            lines = java.nio.file.Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            errorMessage = "读取文件失败: " + e.getMessage();
        }

        return new FileReadResult(lines, errorMessage);
    }

    /**
     * 检查命令是否可用。
     * <p>
     * 在 Linux 上使用 {@code command -v}，在 Windows 上使用 {@code where}。
     * </p>
     *
     * @param cmd 要检查的命令
     * @return true 如果命令存在且可执行
     */
    public boolean isCommandAvailable(String cmd) {
        if (cmd == null || cmd.isBlank()) {
            return false;
        }

        try {
            List<String> checkCmd;
            if (isWindows()) {
                checkCmd = List.of("where", cmd);
            } else {
                checkCmd = List.of("command", "-v", cmd);
            }

            ProcessBuilder pb = new ProcessBuilder(checkCmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(3, TimeUnit.SECONDS);
            if (finished && p.exitValue() == 0) {
                return true;
            }

            if (!isWindows()) {
                p = new ProcessBuilder("which", cmd).start();
                finished = p.waitFor(3, TimeUnit.SECONDS);
                return finished && p.exitValue() == 0;
            }

            return false;
        } catch (Exception e) {
            log.trace("命令可用性检测异常: {} - {}", cmd, e.getMessage());
            return false;
        }
    }

    /**
     * 判断当前是否为 Windows 环境。
     */
    public boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    /**
     * 判断当前是否为 Linux 环境。
     */
    public boolean isLinux() {
        return System.getProperty("os.name").toLowerCase().contains("linux");
    }

    /**
     * 获取实际的最大并发数（用于错误信息）。
     */
    private int getMaxProcesses() {
        return props != null ? props.getMaxProcesses() : DEFAULT_MAX_PROCESSES;
    }

    // ==================== 内部结果类 ====================

    /**
     * 命令执行结果（值对象）。
     */
    public static class CommandResult {
        private final int exitCode;
        private final List<String> stdout;
        private final List<String> stderr;
        private final boolean truncated;
        private final String errorMessage;
        private final long elapsedNs;

        public CommandResult(int exitCode, List<String> stdout, List<String> stderr,
                             boolean truncated, String errorMessage, long elapsedNs) {
            this.exitCode = exitCode;
            this.stdout = stdout != null ? stdout : List.of();
            this.stderr = stderr != null ? stderr : List.of();
            this.truncated = truncated;
            this.errorMessage = errorMessage;
            this.elapsedNs = elapsedNs;
        }

        /** 命令是否成功完成（exit code = 0 且无错误） */
        public boolean isSuccess() {
            return exitCode == 0 && errorMessage == null;
        }

        /** 获取执行耗时（毫秒） */
        public long getDurationMs() {
            return TimeUnit.NANOSECONDS.toMillis(elapsedNs);
        }

        /**
         * 创建执行器过载结果。
         *
         * @param message   过载描述
         * @param elapsedNs 已消耗纳秒
         * @return 过载结果
         */
        public static CommandResult overloaded(String message, long elapsedNs) {
            return new CommandResult(-1, List.of(), List.of(), false,
                    "TOOL_EXECUTOR_OVERLOADED: " + message, elapsedNs);
        }

        public int getExitCode() { return exitCode; }
        public List<String> getStdout() { return stdout; }
        public List<String> getStderr() { return stderr; }
        public boolean isTruncated() { return truncated; }
        public String getErrorMessage() { return errorMessage; }
    }

    /**
     * 文件读取结果（值对象）。
     */
    public static class FileReadResult {
        private final List<String> lines;
        private final String errorMessage;

        public FileReadResult(List<String> lines, String errorMessage) {
            this.lines = lines;
            this.errorMessage = errorMessage;
        }

        public boolean isSuccess() {
            return errorMessage == null && lines != null;
        }

        public List<String> getLines() { return lines; }
        public String getErrorMessage() { return errorMessage; }
    }
}
