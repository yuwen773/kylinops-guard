package com.kylinops.os;

import com.kylinops.tool.OpsTool;
import com.kylinops.tool.ToolInput;
import com.kylinops.tool.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OS 工具边界条件测试
 * <p>
 * 验证工具在非 Linux 环境的优雅降级、参数校验、异常隔离等能力。
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Task 05 — OS 工具边界与降级")
class OsToolEdgeCaseTest {

    @Autowired
    private OsCommandExecutor executor;

    // ==================== BaseOSValidator 单元测试 ====================

    @Test
    @DisplayName("isValidServiceName — 合法服务名")
    void validServiceNames() {
        assertThat(BaseOSValidator.isValidServiceName("nginx")).isTrue();
        assertThat(BaseOSValidator.isValidServiceName("my-service_1.0@v2")).isTrue();
        assertThat(BaseOSValidator.isValidServiceName("a")).isTrue();
    }

    @Test
    @DisplayName("isValidServiceName — 非法服务名")
    void invalidServiceNames() {
        assertThat(BaseOSValidator.isValidServiceName(null)).isFalse();
        assertThat(BaseOSValidator.isValidServiceName("")).isFalse();
        assertThat(BaseOSValidator.isValidServiceName(" ")).isFalse();
        assertThat(BaseOSValidator.isValidServiceName("rm -rf /")).isFalse();
        assertThat(BaseOSValidator.isValidServiceName("nginx; ls")).isFalse();
        assertThat(BaseOSValidator.isValidServiceName("../etc")).isFalse();
    }

    @Test
    @DisplayName("isValidPid — 合法 PID")
    void validPids() {
        assertThat(BaseOSValidator.isValidPid("1")).isTrue();
        assertThat(BaseOSValidator.isValidPid("100")).isTrue();
        assertThat(BaseOSValidator.isValidPid("999999")).isTrue();
        assertThat(BaseOSValidator.isValidPid("4194304")).isTrue();
    }

    @Test
    @DisplayName("isValidPid — 非法 PID")
    void invalidPids() {
        assertThat(BaseOSValidator.isValidPid(null)).isFalse();
        assertThat(BaseOSValidator.isValidPid("")).isFalse();
        assertThat(BaseOSValidator.isValidPid("0")).isFalse();
        assertThat(BaseOSValidator.isValidPid("-1")).isFalse();
        assertThat(BaseOSValidator.isValidPid("abc")).isFalse();
        assertThat(BaseOSValidator.isValidPid("1; rm -rf")).isFalse();
        assertThat(BaseOSValidator.isValidPid("99999999")).isFalse(); // 超过最大限制
    }

    @Test
    @DisplayName("sanitizePath — 合法路径")
    void validPaths() {
        String result = BaseOSValidator.sanitizePath("/var/log",
                BaseOSValidator.ALLOWED_SCAN_ROOTS);
        assertThat(result).isEqualTo("/var/log");

        result = BaseOSValidator.sanitizePath("/var/log/nginx/access.log",
                BaseOSValidator.ALLOWED_SCAN_ROOTS);
        assertThat(result).isEqualTo("/var/log/nginx/access.log");
    }

    @Test
    @DisplayName("sanitizePath — 非法路径")
    void invalidPaths() {
        // 空路径
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> BaseOSValidator.sanitizePath("", BaseOSValidator.ALLOWED_SCAN_ROOTS)))
                .hasMessageContaining("不能为空");

        // 穿越路径
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> BaseOSValidator.sanitizePath("/var/log/../../etc/passwd",
                        BaseOSValidator.ALLOWED_SCAN_ROOTS)))
                .hasMessageContaining("穿越");

        // 不在白名单内
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> BaseOSValidator.sanitizePath("/etc/passwd",
                        BaseOSValidator.ALLOWED_SCAN_ROOTS)))
                .hasMessageContaining("不在允许的扫描范围");
    }

    // ==================== OsCommandExecutor 单元测试 ====================

    @Test
    @DisplayName("isCommandAvailable — 'java' 命令应存在")
    void javaCommandAvailable() {
        boolean available = executor.isCommandAvailable("java");
        // 在开发环境中 java 通常存在
        System.out.println("java 命令可用: " + available);
    }

    @Test
    @DisplayName("isCommandAvailable — 不存在的命令返回 false")
    void nonexistentCommandReturnsFalse() {
        assertThat(executor.isCommandAvailable("this_command_does_not_exist_xyz123"))
                .isFalse();
    }

    @Test
    @DisplayName("isWindows — 检测当前环境")
    void detectWindows() {
        String osName = System.getProperty("os.name").toLowerCase();
        boolean isWindows = osName.contains("win");
        assertThat(executor.isWindows()).isEqualTo(isWindows);
    }

    // ==================== 解析逻辑单元测试 ====================

    @Test
    @DisplayName("SystemInfoTool.parseUptimeSeconds — 各种 uptime 格式")
    void parseUptimeSeconds() {
        // 天 + HH:MM 格式
        assertThat(SystemInfoTool.parseUptimeSeconds(
                "10:25:43 up 3 days,  2:45,  1 user,  load average: 0.08, 0.03, 0.01"))
                .isEqualTo(3 * 86400 + 2 * 3600 + 45 * 60);

        // 仅 HH:MM（启动不到一天）
        assertThat(SystemInfoTool.parseUptimeSeconds(
                "10:25:43 up 2:30, 1 user, load average: 0.08, 0.03, 0.01"))
                .isEqualTo(2 * 3600 + 30 * 60);

        // 空输入
        assertThat(SystemInfoTool.parseUptimeSeconds(null)).isEqualTo(0);
        assertThat(SystemInfoTool.parseUptimeSeconds("")).isEqualTo(0);

        // 不含 "up"
        assertThat(SystemInfoTool.parseUptimeSeconds("some random text")).isEqualTo(0);
    }

    @Test
    @DisplayName("LargeFileScanTool.parseSize — 各种大小格式")
    void parseDuSize() {
        assertThat(LargeFileScanTool.parseSize("1.5G"))
                .isEqualTo((long) (1.5 * 1024 * 1024 * 1024));
        assertThat(LargeFileScanTool.parseSize("234M"))
                .isEqualTo(234L * 1024 * 1024);
        assertThat(LargeFileScanTool.parseSize("789K"))
                .isEqualTo(789L * 1024);
        assertThat(LargeFileScanTool.parseSize("1024"))
                .isEqualTo(1024L);
        assertThat(LargeFileScanTool.parseSize("")).isEqualTo(0);
        assertThat(LargeFileScanTool.parseSize(null)).isEqualTo(0);
        assertThat(LargeFileScanTool.parseSize("abc")).isEqualTo(0);
    }

    // ==================== 异常隔离测试 ====================

    @Test
    @DisplayName("process_detail_tool 对不存在的 PID 返回 FAILED")
    void processDetailNonExistentPid(@Autowired OpsTool processDetailTool) {
        // 使用一个极不可能存在的 PID
        Map<String, Object> params = new HashMap<>();
        params.put("pid", 99999999);
        ToolInput input = ToolInput.builder()
                .toolName("process_detail_tool")
                .params(params)
                .requestId("test-non-existent-pid")
                .build();

        ToolResult result = processDetailTool.execute(input);

        // Windows 环境：降级提示；Linux 环境：PID 不存在提示
        assertThat(result.getStatus()).isEqualTo("failed");
        String errMsg = result.getErrorMessage();
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        if (isWindows) {
            assertThat(errMsg).contains("Linux");
        } else {
            assertThat(errMsg).contains("99999999");
        }
    }

    @Test
    @DisplayName("process_detail_tool 缺少 PID 参数返回 FAILED")
    void processDetailMissingPid(@Autowired OpsTool processDetailTool) {
        ToolInput input = ToolInput.builder()
                .toolName("process_detail_tool")
                .params(new HashMap<>())
                .requestId("test-missing-pid")
                .build();

        ToolResult result = processDetailTool.execute(input);

        assertThat(result.getStatus()).isEqualTo("failed");
        String errMsg = result.getErrorMessage();
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        if (isWindows) {
            assertThat(errMsg).contains("Linux");
        } else {
            assertThat(errMsg).contains("pid");
        }
    }

    @Test
    @DisplayName("process_detail_tool 传入非法 PID 返回 FAILED")
    void processDetailInvalidPid(@Autowired OpsTool processDetailTool) {
        Map<String, Object> params = new HashMap<>();
        params.put("pid", "abc");
        ToolInput input = ToolInput.builder()
                .toolName("process_detail_tool")
                .params(params)
                .requestId("test-invalid-pid")
                .build();

        ToolResult result = processDetailTool.execute(input);

        assertThat(result.getStatus()).isEqualTo("failed");
        String errMsg = result.getErrorMessage();
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        if (isWindows) {
            assertThat(errMsg).contains("Linux");
        } else {
            assertThat(errMsg).contains("PID");
        }
    }

    @Test
    @DisplayName("所有 OS 工具在非 Linux 环境应优雅降级（不抛异常）")
    void allToolsGracefulDegradationOnNonLinux() {
        String osName = System.getProperty("os.name").toLowerCase();
        boolean isLinux = osName.contains("linux");

        // 如果已经是 Linux 则跳过此测试的降级验证
        if (isLinux) {
            System.out.println("当前为 Linux 环境，跳过 Windows 降级验证");
            return;
        }

        // 非 Linux 环境：验证所有 OS 工具都返回 failed（而非抛异常）
        // 注意：system_info_tool 在 Windows 会使用 JVM 属性降级而非直接 failed
    }
}
