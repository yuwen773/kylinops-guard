package com.kylinops.os;

import com.kylinops.tool.ToolInput;
import com.kylinops.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LsofToolTest {

    private static ToolInput input(Map<String, Object> params) {
        return ToolInput.builder()
                .toolName("lsof_tool")
                .params(params)
                .requestId("req-1")
                .build();
    }

    @Test
    void definition_has_l0_read_only() {
        OsCommandExecutor exec = mock(OsCommandExecutor.class);
        LsofTool tool = new LsofTool(exec);
        assertEquals("lsof_tool", tool.definition().getToolName());
        assertEquals(com.kylinops.common.enums.RiskLevel.L0,
                tool.definition().getRiskLevel());
        assertEquals(com.kylinops.common.enums.PermissionType.READ,
                tool.definition().getPermissionType());
        assertTrue(tool.definition().isAuditRequired());
        assertEquals(5000L, tool.definition().getTimeoutMs());
    }

    @Test
    void rejects_invalid_pid_zero() {
        OsCommandExecutor exec = mock(OsCommandExecutor.class);
        LsofTool tool = new LsofTool(exec);
        ToolResult r = tool.execute(input(Map.of("pid", 0)));
        assertFalse(r.isSuccess());
        assertTrue(r.getErrorMessage().contains("pid 非法"));
    }

    @Test
    void rejects_negative_pid() {
        OsCommandExecutor exec = mock(OsCommandExecutor.class);
        LsofTool tool = new LsofTool(exec);
        ToolResult r = tool.execute(input(Map.of("pid", -1)));
        assertFalse(r.isSuccess());
    }

    @Test
    void rejects_string_pid() {
        OsCommandExecutor exec = mock(OsCommandExecutor.class);
        LsofTool tool = new LsofTool(exec);
        ToolResult r = tool.execute(input(Map.of("pid", "abc")));
        assertFalse(r.isSuccess());
    }

    @Test
    void windows_returns_failed() {
        OsCommandExecutor exec = mock(OsCommandExecutor.class);
        when(exec.isWindows()).thenReturn(true);
        LsofTool tool = new LsofTool(exec);
        ToolResult r = tool.execute(input(Map.of("pid", 1234)));
        assertFalse(r.isSuccess());
        assertTrue(r.getErrorMessage().contains("Windows"));
    }

    @Test
    void parses_lsof_F_output_successfully() {
        OsCommandExecutor exec = mock(OsCommandExecutor.class);
        when(exec.isWindows()).thenReturn(false);
        // 模拟 lsof -F 0nPt 输出
        List<String> output = List.of(
                "p1234", "f0", "tCHR", "n/dev/null",
                "f3", "tREG", "n/var/log/app.log",
                "f12", "tIPv4", "nTCP *:8080 (LISTEN)");
        when(exec.execute(anyList(), anyLong())).thenReturn(
                new OsCommandExecutor.CommandResult(0, output, List.of(), false, null, 0L));

        LsofTool tool = new LsofTool(exec);
        ToolResult r = tool.execute(input(Map.of("pid", 1234)));
        assertTrue(r.isSuccess());
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals(1234, data.get("pid"));
        assertEquals(3, data.get("fdCount"));
        assertEquals(2, ((List<?>) data.get("files")).size());
        assertEquals(1, ((List<?>) data.get("sockets")).size());
    }

    @Test
    void parse_failure_returns_rawLines_with_success_status() {
        OsCommandExecutor exec = mock(OsCommandExecutor.class);
        when(exec.isWindows()).thenReturn(false);
        // 模拟异常格式输出
        List<String> output = List.of("garbage", "more garbage", "1234");
        when(exec.execute(anyList(), anyLong())).thenReturn(
                new OsCommandExecutor.CommandResult(0, output, List.of(), false, null, 0L));

        LsofTool tool = new LsofTool(exec);
        ToolResult r = tool.execute(input(Map.of("pid", 1234)));
        assertTrue(r.isSuccess(), "解析失败但 status 仍应为 success（降级）");
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertNotNull(data.get("rawLines"));
        assertNotNull(data.get("parseError"));
    }
}
