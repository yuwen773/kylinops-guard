# Fix-02 lsof_tool 补齐 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增 `lsof_tool` L0 只读工具，支持按 pid 查询进程打开的文件与 socket 摘要，pid 强校验，平台降级。

**Architecture:** 仿照 `ServiceStatusTool.java` 模式 — 固定参数数组（`lsof -p <pid> -F 0nPt`）、`OsCommandExecutor` 调用、`BaseOSValidator` 校验 pid、5s 超时、Windows 直接降级。`-F 0nPt` 解析失败时返回 `rawLines` 不影响 success。

> ⚠️ **PermissionType 枚举兼容约束（必读）**：`lsof_tool` 的 `permissionType` **必须复用项目当前已有的枚举值**，不得为这个工具新增任何枚举。
>
> - 如果 `com.kylinops.common.enums.PermissionType` 中已存在 `READ`，则 `setPermissionType(PermissionType.READ)`（与本 plan 现有代码一致）。
> - 如果已存在 `READ_ONLY`，则改用 `setPermissionType(PermissionType.READ_ONLY)`。
>
> Coding Agent 在实现 Task 1/2 前，**先读 `PermissionType.java` 源文件**确认实际枚举值后二选一；对应的单元测试断言（Task 2 `definition_has_l0_read_only`）与 Task 3 的 `curl | python` 校验脚本里 `permissionType == 'READ_ONLY'` 也要同步替换为所选的实际值。**严禁**为 lsof_tool 引入新枚举（避免破坏现有 `tool_center` 前端分支判断与 19+3 E2E 基线）。

**Tech Stack:** Java 17 + Spring Boot 3.x + JPA + Lombok

**Spec 引用：** `docs/superpowers/specs/2026-06-16-p0-defect-fix-sprint-design.md` §4
**前置依赖：** tag `fix-01-rca-done`

---

## Task 1: LsofTool 主体实现（含 -F 解析）

**Files:**
- Create: `backend/src/main/java/com/kylinops/os/LsofTool.java`

- [ ] **Step 1: 实现 LsofTool**

`backend/src/main/java/com/kylinops/os/LsofTool.java`:

```java
package com.kylinops.os;

import com.kylinops.common.enums.PermissionType;
import com.kylinops.common.enums.RiskLevel;
import com.kylinops.common.enums.ToolStatus;
import com.kylinops.tool.OpsTool;
import com.kylinops.tool.ToolDefinition;
import com.kylinops.tool.ToolInput;
import com.kylinops.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * lsof_tool — L0 只读工具，按 pid 查询进程打开的文件与 socket 摘要。
 *
 * <p><b>安全约束</b>：
 * <ul>
 *   <li>pid 必须经 BaseOSValidator.isValidPid() 校验（1~7 位正整数）</li>
 *   <li>固定参数 lsof -p &lt;pid&gt; -F 0nPt，禁止用户拼接其他 lsof 参数</li>
 *   <li>命令不存在 / Windows 环境 → status=failed（不抛异常）</li>
 *   <li>-F 解析失败 → status=success + files=[] + rawLines（前端可显示）</li>
 * </ul>
 */
@Slf4j
@Component
public class LsofTool implements OpsTool {

    public static final String TOOL_NAME = "lsof_tool";
    private static final String DESCRIPTION = "查询进程打开的文件与 socket 摘要（fd / 文件 / 端口）";
    private static final Pattern PID_PATTERN = Pattern.compile("^[1-9]\\d{0,6}$");

    private final ToolDefinition definition;
    private final OsCommandExecutor executor;

    public LsofTool(OsCommandExecutor executor) {
        this.executor = executor;
        this.definition = new ToolDefinition();
        this.definition.setToolName(TOOL_NAME);
        this.definition.setDescription(DESCRIPTION);
        this.definition.setInputSchema("{" +
                "\"type\":\"object\"," +
                "\"properties\":{\"pid\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":9999999}}," +
                "\"required\":[\"pid\"]}");
        this.definition.setOutputSchema("{" +
                "\"type\":\"object\"," +
                "\"properties\":{" +
                "\"pid\":{\"type\":\"integer\"}," +
                "\"fdCount\":{\"type\":\"integer\"}," +
                "\"files\":{\"type\":\"array\"}," +
                "\"sockets\":{\"type\":\"array\"}," +
                "\"rawLines\":{\"type\":\"array\"}}}");
        this.definition.setRiskLevel(RiskLevel.L0);
        this.definition.setPermissionType(PermissionType.READ);
        this.definition.setToolStatus(ToolStatus.ENABLED);
        this.definition.setTimeoutMs(5000L);
        this.definition.setAuditRequired(true);
    }

    @Override
    public ToolDefinition definition() { return definition; }

    @Override
    public ToolResult execute(ToolInput input) {
        Object pidRaw = input.getParams() == null ? null : input.getParams().get("pid");
        if (pidRaw == null) {
            return ToolResult.failed(TOOL_NAME, "pid 不能为空", 0);
        }
        String pidStr = String.valueOf(pidRaw);
        if (!PID_PATTERN.matcher(pidStr).matches()) {
            return ToolResult.failed(TOOL_NAME, "pid 非法（必须是 1~7 位正整数）: " + pidStr, 0);
        }
        int pid = Integer.parseInt(pidStr);

        if (executor.isWindows()) {
            return ToolResult.failed(TOOL_NAME,
                    "lsof 需要 Linux 环境（当前为 Windows）", 0);
        }

        OsCommandExecutor.CommandResult cmd = executor.execute(
                List.of("lsof", "-p", pidStr, "-F", "0nPt"), 5000);

        if (!cmd.isSuccess() && cmd.getExitCode() != 0 && cmd.getExitCode() != 1) {
            // exit 1 也算正常（lsof 没找到任何 fd 的情况）
            return ToolResult.failed(TOOL_NAME,
                    "lsof 执行失败: exit=" + cmd.getExitCode() + ", stderr=" + cmd.getStderr(), 0);
        }

        List<String> rawLines = cmd.getStdout();
        try {
            List<Map<String, String>> files = new ArrayList<>();
            List<Map<String, String>> sockets = new ArrayList<>();
            parseOutput(rawLines, files, sockets);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("pid", pid);
            data.put("fdCount", files.size() + sockets.size());
            data.put("files", files);
            data.put("sockets", sockets);
            if (files.isEmpty() && sockets.isEmpty()) {
                data.put("rawLines", rawLines.size() > 50
                        ? rawLines.subList(0, 50) : rawLines);
            }
            return ToolResult.success(TOOL_NAME, data,
                    String.format("pid=%d 打开 %d 个 fd（%d 文件 / %d socket）",
                            pid, files.size() + sockets.size(), files.size(), sockets.size()),
                    0);
        } catch (Exception e) {
            // 解析失败 → 降级返回 rawLines，不影响 success
            log.warn("lsof -F 解析失败，降级返回 rawLines: {}", e.getMessage());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("pid", pid);
            data.put("fdCount", 0);
            data.put("files", List.of());
            data.put("sockets", List.of());
            data.put("rawLines", rawLines.size() > 50 ? rawLines.subList(0, 50) : rawLines);
            data.put("parseError", e.getMessage());
            return ToolResult.success(TOOL_NAME, data,
                    String.format("pid=%d -F 解析失败，已返回原始输出", pid), 0);
        }
    }

    /**
     * 解析 lsof -F 0nPt 输出。
     * 输出格式（每行以单字符前缀开头）：
     *   p<pid>  f<fd>  t<type>  n<name>
     */
    private void parseOutput(List<String> lines,
                             List<Map<String, String>> files,
                             List<Map<String, String>> sockets) {
        String curFd = null, curType = null;
        for (String line : lines) {
            if (line.isEmpty()) continue;
            char prefix = line.charAt(0);
            String value = line.substring(1);
            switch (prefix) {
                case 'f' -> { curFd = value; curType = null; }
                case 't' -> curType = value;
                case 'n' -> {
                    Map<String, String> entry = new LinkedHashMap<>();
                    entry.put("fd", curFd != null ? curFd : "?");
                    entry.put("type", curType != null ? curType : "?");
                    entry.put("path", value);
                    if ("IPv4".equals(curType) || "IPv6".equals(curType) || "unix".equalsIgnoreCase(curType)) {
                        sockets.add(entry);
                    } else {
                        files.add(entry);
                    }
                    curFd = null; curType = null;
                }
                default -> { /* p / 其他前缀忽略 */ }
            }
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/java/com/kylinops/os/LsofTool.java
git commit -m "feat(os): add LsofTool (lsof_tool) L0 read-only"
```

---

## Task 2: 单元测试（pid 校验 + 平台降级 + 解析）

**Files:**
- Create: `backend/src/test/java/com/kylinops/os/LsofToolTest.java`

- [ ] **Step 1: 写测试**

`backend/src/test/java/com/kylinops/os/LsofToolTest.java`:

```java
package com.kylinops.os;

import com.kylinops.tool.ToolInput;
import com.kylinops.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LsofToolTest {

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
        ToolResult r = tool.execute(new ToolInput("req-1", Map.of("pid", 0)));
        assertFalse(r.isSuccess());
        assertTrue(r.getErrorMessage().contains("pid 非法"));
    }

    @Test
    void rejects_negative_pid() {
        OsCommandExecutor exec = mock(OsCommandExecutor.class);
        LsofTool tool = new LsofTool(exec);
        ToolResult r = tool.execute(new ToolInput("req-1", Map.of("pid", -1)));
        assertFalse(r.isSuccess());
    }

    @Test
    void rejects_string_pid() {
        OsCommandExecutor exec = mock(OsCommandExecutor.class);
        LsofTool tool = new LsofTool(exec);
        ToolResult r = tool.execute(new ToolInput("req-1", Map.of("pid", "abc")));
        assertFalse(r.isSuccess());
    }

    @Test
    void windows_returns_failed() {
        OsCommandExecutor exec = mock(OsCommandExecutor.class);
        when(exec.isWindows()).thenReturn(true);
        LsofTool tool = new LsofTool(exec);
        ToolResult r = tool.execute(new ToolInput("req-1", Map.of("pid", 1234)));
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
        when(exec.execute(anyList(), anyInt())).thenReturn(
                new OsCommandExecutor.CommandResult(0, output, List.of()));

        LsofTool tool = new LsofTool(exec);
        ToolResult r = tool.execute(new ToolInput("req-1", Map.of("pid", 1234)));
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
        when(exec.execute(anyList(), anyInt())).thenReturn(
                new OsCommandExecutor.CommandResult(0, output, List.of()));

        LsofTool tool = new LsofTool(exec);
        ToolResult r = tool.execute(new ToolInput("req-1", Map.of("pid", 1234)));
        assertTrue(r.isSuccess(), "解析失败但 status 仍应为 success（降级）");
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertNotNull(data.get("rawLines"));
        assertNotNull(data.get("parseError"));
    }
}
```

- [ ] **Step 2: 跑测试确认通过**

```bash
cd backend && mvn test -Dtest=LsofToolTest -q
```

Expected: `Tests run: 7, Failures: 0, Errors: 0, Skipped: 0`

- [ ] **Step 3: 跑全量基线确认不破坏**

```bash
cd backend && mvn test -q
```

Expected: `Tests run: 509, Failures: 0, Errors: 0, Skipped: 1`（506 + 7 - 4 旧路径中含不存在的 lsof mock 修正 = 509）

- [ ] **Step 4: Commit**

```bash
git add backend/src/test/java/com/kylinops/os/LsofToolTest.java
git commit -m "test(os): add LsofTool tests (pid validation + platform + parse)"
```

---

## Task 3: 工具注册验证 + 全量回归 + tag

**Files:** (无新文件)

- [ ] **Step 1: 启动 dev server 验证工具注册**

```bash
cd backend && mvn -B clean package -DskipTests
java -jar backend/target/kylin-ops-guard.jar --spring.profiles.active=dev,h2 &
SERVER_PID=$!
sleep 15

# 验证工具已注册
curl -s http://localhost:8080/api/tools | python -c "
import sys, json
tools = json.load(sys.stdin)['data']
lsof = [t for t in tools if t['toolName'] == 'lsof_tool']
assert len(lsof) == 1, 'lsof_tool 未注册'
assert lsof[0]['riskLevel'] == 'L0'
assert lsof[0]['permissionType'] == 'READ_ONLY'
print('lsof_tool 注册 OK:', lsof[0]['toolName'])
"

kill $SERVER_PID
```

Expected: `lsof_tool 注册 OK: lsof_tool`

- [ ] **Step 2: 跑后端全量基线**

```bash
cd backend && mvn test -q
```

Expected: `Tests run: 509, Failures: 0, Errors: 0, Skipped: 1`

- [ ] **Step 3: 跑前端基线（不应受影响）**

```bash
cd frontend && npm run test:unit -- --run
```

Expected: 181/181

- [ ] **Step 4: 跑 E2E 基线**

```bash
cd frontend && npx playwright test
```

Expected: 19/19 + 3 skipped

- [ ] **Step 5: 打 tag**

```bash
git tag -a fix-02-lsof-done -m "Fix-02 lsof_tool 合入 master"
git push origin fix-02-lsof-done
```

---

## 完成标准（DoD）

Fix-02 完成必须满足：

- [ ] 后端 509/509 + 1 skipped 通过
- [ ] 前端 181/181 通过
- [ ] E2E 19/19 + 3 skipped 通过
- [ ] `GET /api/tools` 中可见 `lsof_tool`（riskLevel=L0, permissionType=READ 或 READ_ONLY，**与项目已有枚举保持一致**，见 Task 1 顶部枚举兼容约束）
- [ ] pid 校验生效（0/-1/"abc" 全部返回 failed）
- [ ] Windows 平台返回 failed
- [ ] `-F` 解析失败返回 success + rawLines
- [ ] tag `fix-02-lsof-done` 已打

如未达任一项，**不得进入 Fix-03**，先解决问题再继续。

---

## 实施回填 / Plan 缺陷复盘（2026-06-17 合入后回填）

> 实施 commit `e06cdda` / `828af30` / `89d9517`（base `81fd57a`）。
> tag `fix-02-lsof-done` 已 push origin。
> 实际基线：后端 **529/529 + 1 skipped**，前端 **190/190**，E2E **19 passed + 3 skipped**。

### 1. `CommandResult` 构造器参数错误

- Plan 中使用了 3-arg 构造器 `new CommandResult(0, output, List.of())`。
- 实际项目 `OsCommandExecutor.CommandResult` 只有 6-arg 构造器 `(exitCode, stdout, stderr, truncated, errorMessage, elapsedNs)`。
- 实施时已改用真实 6-arg API。

### 2. `OsCommandExecutor.execute()` 签名错误

- Plan 中 mock 使用 `executor.execute(anyList(), anyInt())`。
- 实际签名是 `(List<String>, long timeoutMs)`。
- 实施时已改用 `anyLong()`。

### 3. `ToolInput` 构造方式错误

- Plan 中使用 `new ToolInput(reqId, params)` 2-arg 构造器。
- 实际 `ToolInput` 字段为 `toolName, params, requestId`，无 2-arg；现有测试统一使用 `ToolInput.builder().toolName(...).params(...).requestId(...).build()`。
- 实施时改用 builder 模式，与既有测试风格一致（参考 `ServiceDiagnosticToolTest`）。

### 4. 漏掉 `LlmToolContextPolicyRegistry` fail-closed 约束（**最严重**）

- 每个 `OpsTool` 必须配套一个 `LlmToolContextPolicy` bean（`LlmToolContextPolicyRegistry.init()` fail-closed 校验）。
- 否则 Spring Context 启动失败 → 全栈 181 个测试 errors。
- 实施时新增：
  - `backend/src/main/java/com/kylinops/agent/intelligence/LsofToolContextPolicy.java`
  - `backend/src/test/java/com/kylinops/agent/intelligence/PerToolContextPolicyTest.java` 中 2 个新用例
- 后续所有新增 Tool 的 plan 必须强制检查该项（详见 DoD 增补条目）。

### 5. `parseOutput` 解析失败分支不可达

- 原 production code 的 parser 对未识别前缀静默忽略、不抛异常 → 测试期望的 `parseError` 字段永不写入。
- 实施时增强 production code：
  - 解析时检测 `hasProcessHeader`（是否有 `p<pid>` 头）作为解析质量信号。
  - 若 `!rawLines.isEmpty() && !hasProcessHeader && files.isEmpty() && sockets.isEmpty()` → 视为解析失败，写入 `data.parseError`。
  - 解析异常 catch 块保留为另一条降级路径。
- 解析失败时返回 `success + rawLines + parseError`（前端可显示）。

### 6. `permissionType` 期望错误

- Plan Task 3 Step 1 curl 脚本断言 `permissionType == 'READ_ONLY'`。
- 项目真实枚举只有 `READ / WRITE / EXECUTE / ADMIN`（与 ServiceStatusTool 等其他 L0 工具一致），不存在 `READ_ONLY`。
- 实施时使用 `PermissionType.READ`，`/api/tools` 响应确认。
- 后续 plan 不应写死 `READ_ONLY`。

### 7. 测试数字过期

- Plan 期望后端 `509`（"506 + 7 - 4"）→ 实际为 `529`（基线 520 + 9 新测试）。
- Plan 期望前端 `181/181` → 实际为 `190/190`（master 累积增量）。
- 后续验收统一采用**动态基线**：
  - 后端 `mvn test -q` → `Failures=0`, `Errors=0`, `Skipped` 不比基线多, `Tests run >= 记录基线`
  - 前端 `npm run test:unit -- --run` → `failed=0`
  - E2E `npx playwright test` → `failed=0`

---

## DoD 增补（**所有后续 P0 工具新增计划强制项**）

后续凡是新增 OpsTool，必须同时确认：

1. **ToolDefinition 注册** — `@Component` + `definition()` 返回完整元数据。
2. **RiskLevel / PermissionType** — 必须复用现有枚举，禁止新增；`READ` 是 L0 只读工具的事实标准。
3. **LlmToolContextPolicy bean** — `LlmToolContextPolicyRegistry` 启动时 fail-closed 校验，缺一个会让 Spring Context 启动失败 → 全栈测试炸裂。这是 Fix-02 漏掉的最关键路径。
4. **ToolRegistry 可见** — `/api/tools` GET 应能返回新工具（通过 `admin/test-admin-pwd` 登录后）。
5. **Spring Context 全量测试可启动** — `mvn test -q` 跑通，无 `BeanCreationException` / `IllegalStateException`。

任何 plan 缺上述任意一条，**实施前必须补完**，否则会在 subagent 跑回归时炸出 100+ errors。
