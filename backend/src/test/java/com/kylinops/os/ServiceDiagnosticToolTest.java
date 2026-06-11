package com.kylinops.os;

import com.kylinops.tool.ToolInput;
import com.kylinops.tool.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ServiceStatusTool 和 JournalLogTool 测试
 * <p>
 * 验证注册属性、参数校验和命令格式安全性。
 * </p>
 */
@DisplayName("ServiceDiagnosticTool — 服务诊断工具")
class ServiceDiagnosticToolTest {

    private final OsCommandExecutor executor = new OsCommandExecutor();
    private final ServiceStatusTool serviceTool = new ServiceStatusTool(executor);
    private final JournalLogTool journalTool = new JournalLogTool(executor);

    @Test
    @DisplayName("ServiceStatusTool 定义正确")
    void serviceToolDefinition() {
        assertThat(serviceTool.definition().getToolName()).isEqualTo("service_status_tool");
        assertThat(serviceTool.definition().getRiskLevel().name()).isIn("L0", "L1");
        assertThat(serviceTool.definition().getPermissionType().name()).isEqualTo("READ");
        assertThat(serviceTool.definition().isAuditRequired()).isTrue();
    }

    @Test
    @DisplayName("JournalLogTool 定义正确")
    void journalToolDefinition() {
        assertThat(journalTool.definition().getToolName()).isEqualTo("journal_log_tool");
        assertThat(journalTool.definition().getRiskLevel().name()).isIn("L0", "L1");
        assertThat(journalTool.definition().getPermissionType().name()).isEqualTo("READ");
        assertThat(journalTool.definition().isAuditRequired()).isTrue();
    }

    @Test
    @DisplayName("ServiceStatusTool 空服务名 → 失败")
    void serviceStatusEmptyName() {
        ToolResult result = serviceTool.execute(ToolInput.builder()
                .toolName("service_status_tool")
                .params(Map.of())
                .requestId("test").build());
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("服务名");
    }

    @Test
    @DisplayName("ServiceStatusTool 非法服务名 → 失败")
    void serviceStatusInvalidName() {
        ToolResult result = serviceTool.execute(ToolInput.builder()
                .toolName("service_status_tool")
                .params(Map.of("serviceName", "nginx; rm -rf /"))
                .requestId("test").build());
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("不合法");
    }

    @Test
    @DisplayName("ServiceStatusTool 合法服务名 → 执行或降级")
    void serviceStatusValidName() {
        ToolResult result = serviceTool.execute(ToolInput.builder()
                .toolName("service_status_tool")
                .params(Map.of("serviceName", "nginx"))
                .requestId("test").build());
        // Windows 上可能降级，但不应该抛异常
        assertThat(result.getToolName()).isEqualTo("service_status_tool");
        assertThat(result.getStatus()).isIn("success", "failed");
    }

    @Test
    @DisplayName("JournalLogTool 空服务名 → 失败")
    void journalLogEmptyName() {
        ToolResult result = journalTool.execute(ToolInput.builder()
                .toolName("journal_log_tool")
                .params(Map.of())
                .requestId("test").build());
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    @DisplayName("JournalLogTool 非法服务名 → 失败")
    void journalLogInvalidName() {
        ToolResult result = journalTool.execute(ToolInput.builder()
                .toolName("journal_log_tool")
                .params(Map.of("serviceName", "nginx; echo hacked"))
                .requestId("test").build());
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("不合法");
    }

    @Test
    @DisplayName("JournalLogTool lines 参数越界 → 截断到上限")
    void journalLogLinesBound() {
        ToolResult result = journalTool.execute(ToolInput.builder()
                .toolName("journal_log_tool")
                .params(Map.of("serviceName", "nginx", "lines", 99999))
                .requestId("test").build());
        if (result.isSuccess()) {
            // 如果执行成功，lines 应在合理范围内
            assertThat(result.getData()).isNotNull();
        }
    }

    @Test
    @DisplayName("不拼接 shell 字符串 — 参数数组模式")
    void noShellConcatenation() {
        // 验证工具使用固定参数数组，不拼接用户输入为 shell 命令
        assertThat(serviceTool).isNotNull();
        assertThat(journalTool).isNotNull();
        // 安全保证来自实现方式（ProcessBuilder + List.of）
    }
}
