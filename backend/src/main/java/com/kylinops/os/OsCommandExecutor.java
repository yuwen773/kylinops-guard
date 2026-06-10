package com.kylinops.os;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * OS 命令执行器
 * <p>
 * 封装 {@link ProcessBuilder} 的创建、执行、超时控制、输出截断和异常处理。
 * 所有 OS 感知 OpsTool 通过此执行器运行系统命令，禁止直接创建 ProcessBuilder。
 * </p>
 *
 * <h3>核心功能</h3>
 * <ul>
 *   <li>命令可用性检测 — {@link #isCommandAvailable(String)}</li>
 *   <li>命令执行 — {@link #execute(List, long)}</li>
 *   <li>输出截断 — stdout 最多 64KB 或 500 行（先到者）</li>
 *   <li>硬超时 — 超过 timeoutMs 后 {@link Process#destroyForcibly()}</li>
 *   <li>错误处理 — 异常全部封装为 {@link CommandResult}，不抛异常</li>
 * </ul>
 */
@Slf4j
@Component
public class OsCommandExecutor {

    /** 最大输出行数 */
    static final int MAX_LINES = 500;

    /** 最大输出字节数 (64 KB) */
    static final int MAX_BYTES = 65536;

    /**
     * 执行系统命令并返回结果。
     * <p>
     * 命令以固定参数列表执行（禁止拼接用户输入），
     * 自动处理超时、截断和异常。
     * </p>
     *
     * @param command  命令 + 参数列表（如 ["df", "-h"]）
     * @param timeoutMs 超时阈值（毫秒）
     * @return 命令执行结果（永远不会返回 null）
     */
    public CommandResult execute(List<String> command, long timeoutMs) {
        long start = System.nanoTime();
        List<String> stdout = new ArrayList<>();
        List<String> stderr = new ArrayList<>();
        boolean truncated = false;
        int exitCode = -1;
        String errorMessage = null;

        if (log.isDebugEnabled()) {
            log.debug("执行命令: {}", String.join(" ", command));
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            Process process = pb.start();

            // 读取 stdout（含截断）
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                int totalBytes = 0;
                while ((line = reader.readLine()) != null) {
                    if (!truncated && stdout.size() < MAX_LINES && totalBytes < MAX_BYTES) {
                        stdout.add(line);
                        totalBytes += line.getBytes(StandardCharsets.UTF_8).length;
                        if (stdout.size() >= MAX_LINES || totalBytes >= MAX_BYTES) {
                            truncated = true;
                        }
                    }
                    // 截断后继续消费流，防止进程阻塞
                }
            }

            // 读取 stderr
            try (BufferedReader errorReader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = errorReader.readLine()) != null) {
                    stderr.add(line);
                }
            }

            // 等待完成或超时
            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                long elapsedNs = System.nanoTime() - start;
                log.warn("命令执行超时 ({}ms): {}", timeoutMs, String.join(" ", command));
                return new CommandResult(-1, stdout, stderr, truncated,
                        "工具执行超时（阈值: " + timeoutMs + "ms）",
                        elapsedNs);
            }

            exitCode = process.exitValue();
            if (exitCode != 0) {
                String stderrText = stderr.isEmpty() ? "" : ", stderr: " + String.join("\n", stderr);
                errorMessage = "命令退出码: " + exitCode + stderrText;
                log.debug("命令执行异常退出 (exit={}): {}", exitCode, String.join(" ", command));
            }

        } catch (IOException e) {
            errorMessage = "IO 异常: " + e.getMessage();
            log.debug("命令 IO 异常: {}", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            errorMessage = "执行被中断";
            log.debug("命令执行被中断");
        } catch (Exception e) {
            errorMessage = "未知异常: " + e.getMessage();
            log.debug("命令执行未知异常", e);
        }

        long elapsedNs = System.nanoTime() - start;
        return new CommandResult(exitCode, stdout, stderr, truncated, errorMessage, elapsedNs);
    }

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
                // 首选 command -v（POSIX 标准），回退 which
                checkCmd = List.of("command", "-v", cmd);
            }

            ProcessBuilder pb = new ProcessBuilder(checkCmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(3, TimeUnit.SECONDS);
            if (finished && p.exitValue() == 0) {
                return true;
            }

            // 非 Windows 系统回退到 which
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
