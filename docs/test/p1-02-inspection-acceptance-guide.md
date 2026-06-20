# P1-02 定时巡检 MVP 验收指南

> 本指南是 P1-02 Scheduled Inspection MVP 的人工验证脚本。所有命令在 worktree
> `D:\Work\code\kylin-ops\.claude\worktrees\p1-02-inspection-mvp` 下执行,基线 commit `9c252dd`。
>
> 占位符 `<...>` 需要在执行命令后填入实际输出到 `docs/test/p1-02-inspection-acceptance.md`。
>
> 设计稿: `docs/superpowers/specs/2026-06-18-p1-02-scheduled-inspection-mvp-design.md`
>
> ⚠ P1-02 巡检路径是**只读**路径 — 不创建 `PendingAction`、不绕过 RiskCheck、不调用 LLM。
> 与 ChatConsole 通过 user prompt 触发的 L2 重启**正交**,本验收不涉及 L2 演示路径。

## 0. 前置条件

- Java 17（已验证 879/879 + 2 skipped 后端基线）
- Maven 3.9+
- Node.js 20.x（推荐 20.14+）
- npm 10+
- Playwright chromium（已通过 `npx playwright install chromium`）
- Git Bash / PowerShell（Windows）/ bash（Linux）
- 操作系统：Windows 11（开发主机,OS 工具大量降级但巡检逻辑可验）；Linux/LoongArch（**待真实目标机执行**）

工作目录：

```bash
cd "D:/Work/code/kylin-ops/.claude/worktrees/p1-02-inspection-mvp"
```

确认当前分支：

```bash
git branch --show-current
# 期望: worktree-p1-02-inspection-mvp
git log -1 --oneline
# 期望: 9c252dd feat(inspection): 添加巡检管理前端页面
```

---

## 1. 后端静态校验

### 1.1 红线扫描:巡检路径不引入 raw shell / 不调用 LLM

```bash
rg -n 'ProcessBuilder|api/(exec|shell|command/run)' backend/src/main/java/com/kylinops/inspection
rg -n 'chatClient|llmClient|LlmIntent' backend/src/main/java/com/kylinops/inspection
rg -n 'PendingAction.*save|pendingActionService.create' backend/src/main/java/com/kylinops/inspection
```

**预期**：3 个命令均无输出（exit code 1 = 无匹配,符合预期）。
- 巡检路径不直接调 shell,全部通过 `ToolExecutor` 调白名单 Tool
- 不调用 LLM,纯模板驱动
- 不创建 `PendingAction`,只读路径红线条款 §1

### 1.2 模板工具风险校验（启动期 fail-fast）

```bash
rg -n 'RiskLevel.L[2-4]|PermissionType.WRITE|PermissionType.ADMIN' backend/src/main/java/com/kylinops/inspection/InspectionTemplateRegistry.java
```

**预期**：无输出。模板只引用 `RiskLevel.L0/L1` + `PermissionType.READ` 工具,违规立即 `IllegalStateException` 启动失败。

### 1.3 后端测试基线

```bash
cd backend
mvn -B test 2>&1 | tee /tmp/p1-02-backend-test.log
```

**预期**：
- `Tests run: 879` + `Failures: 0, Errors: 0` + `Skipped: 2`
- `BUILD SUCCESS`
- 填入：`Tests run: <N>, Failures: 0, Errors: 0, Skipped: 2`
- 关键 inspection 测试类:
  - `InspectionRepositoryTest`（8 用例,V7 schema 契约）
  - `InspectionTemplateRegistryTest`（5 用例,启动期校验）
  - `InspectionPlanValidatorTest`（16 用例,字段边界）
  - `InspectionRiskContextFactoryTest`（6 用例,RiskCheck 上下文）
  - `InspectionLogErrorClassifierTest`（7 用例,异常日志分类）
  - `InspectionResultEvaluatorTest`（7 用例,异常判定）
  - `InspectionExecutionServiceIntegrationTest`（9 用例,执行闭环）
  - `InspectionAuditIntegrationTest`（巡检审计写入,sessionId=null）
  - `InspectionReportIntegrationTest`（报告降级 null）
  - `InspectionNotificationFactoryTest`（事件 detail.abnormal 正交）
  - `InspectionPlanServiceTest`（9 用例,CRUD + 乐观锁）
  - `InspectionSchedulerTest`（7 用例,周期 poll + runNow）
  - `InspectionRecoveryTest`（8 用例,abandoned 启动期恢复）
  - `InspectionControllerTest`（22 用例,11 端点 REST 契约）
  - `InspectionSecurityBoundaryTest`（12 用例,operator 从 session / CSRF / 404）

### 1.4 后端打包

```bash
mvn -B clean package -DskipTests
ls -la backend/target/kylin-ops-guard.jar
```

**预期**：`kylin-ops-guard.jar` 存在（约 45-55MB,P1-02 增量约 5MB）。

---

## 2. 后端启动与基础检查

打开终端 A：

```bash
cd backend
# dev profile 自带 notification master key 占位,直接可用
java -jar target/kylin-ops-guard.jar
```

**预期日志**：
- `InspectionTemplateRegistry 初始化完成:已注册 HEALTH / DISK / SERVICE 三个内置模板`
- `Started KylinOpsApplication in <N> seconds`
- `Tomcat started on port 8080`
- H2 File Mode: `jdbc:h2:file:./data/kylinops`
- **关键**: 若 `kylinops.inspection.scheduler.auto-start=true`(默认),会在 `ApplicationReadyEvent` 启动周期 poll

打开终端 B：

```bash
curl http://127.0.0.1:8080/api/health
```

**预期**：
```json
{"code":200,"message":"UP","data":{"status":"UP"},"timestamp":<ms>,"traceId":"<uuid>"}
```

---

## 3. 后端 11 个 REST 端点 curl 脚本（核心契约）

> **统一前置**: 先登录拿 session + csrf token。所有写接口(POST/PUT/DELETE)需带 X-XSRF-TOKEN。
> 注意 Spring Security 6 默认 `CookieCsrfTokenRepository.withHttpOnlyFalse()`,csrf token
> 在响应 cookie `XSRF-TOKEN`,前端 axios 自动同步;curl 需手动提取。

```bash
# ---------- 3.1 登录拿 session ----------
# 注意 dev profile 默认 admin/admin;正式部署会强制改密
COOKIES=/tmp/p1-02-cookies.txt
rm -f "$COOKIES"

# 1) GET /api/auth/csrf 拿 token(某些版本需要先 hit 一次触发 cookie 写入)
CSRF_JSON=$(curl -s -c "$COOKIES" -b "$COOKIES" http://127.0.0.1:8080/api/auth/csrf)
CSRF=$(printf '%s' "$CSRF_JSON" | sed -n 's/.*"csrfToken":"\([^"]*\)".*/\1/p')
echo "CSRF=$CSRF"

# 2) POST /api/auth/login
curl -s -c "$COOKIES" -b "$COOKIES" \
  -H "Content-Type: application/json" \
  -H "X-XSRF-TOKEN: $CSRF" \
  -d '{"username":"admin","password":"admin"}' \
  http://127.0.0.1:8080/api/auth/login

# 验证登录态
curl -s -b "$COOKIES" http://127.0.0.1:8080/api/auth/me
# 预期: {"code":200,...,"data":{"username":"admin",...}}
```

### 3.2 GET /api/inspections/templates — 列出 3 个内置模板

```bash
curl -s -b "$COOKIES" http://127.0.0.1:8080/api/inspections/templates | head -c 800
```

**预期**：`data` 数组长度=3,顺序为 `HEALTH / DISK / SERVICE`,每个含 `fields`(前端动态表单源)+ `riskLevels` + `keyToolNames`。
- `HEALTH.keyToolNames` 包含 `system_info_tool`/`cpu_status_tool`/`memory_status_tool`/`disk_usage_tool`
- `DISK.keyToolNames` 包含 `disk_usage_tool`/`large_file_scan_tool`
- `SERVICE.keyToolNames` 包含 `service_status_tool`

### 3.3 GET /api/inspections/plans — 空列表

```bash
curl -s -b "$COOKIES" http://127.0.0.1:8080/api/inspections/plans
```

**预期**：`{"code":200,...,"data":[]}`（首次启动,无 plan）。

### 3.4 POST /api/inspections/plans — 创建 HEALTH 计划

```bash
# 场景1 用:健康度巡检
PLAN_BODY='{
  "name": "nginx 健康巡检",
  "description": "P1-02 验收 — HEALTH 模板",
  "templateType": "HEALTH",
  "templateParams": {"serviceName": "nginx"},
  "thresholds": {"cpuWarningPercent": 80, "memoryWarningPercent": 80, "diskWarningPercent": 85},
  "scheduleType": "DAILY",
  "localTime": "03:00:00",
  "timezone": "Asia/Shanghai",
  "notificationPolicy": "ON_ABNORMAL"
}'
PLAN_JSON=$(curl -s -b "$COOKIES" \
  -H "Content-Type: application/json" \
  -H "X-XSRF-TOKEN: $CSRF" \
  -d "$PLAN_BODY" \
  http://127.0.0.1:8080/api/inspections/plans)
echo "$PLAN_JSON"
PLAN_ID=$(printf '%s' "$PLAN_JSON" | sed -n 's/.*"planId":"\([^"]*\)".*/\1/p')
echo "PLAN_ID=$PLAN_ID"
```

**预期**：
- HTTP 200,`data.enabled=false`,`data.nextRunAt=null`(默认禁用)
- `data.version=0`(初始乐观锁版本)
- 记录到 evidence:`PLAN_ID=<uuid36>`

### 3.5 POST /api/inspections/plans/{planId}/enable — 启用计划

```bash
curl -s -b "$COOKIES" \
  -H "X-XSRF-TOKEN: $CSRF" \
  -X POST "http://127.0.0.1:8080/api/inspections/plans/$PLAN_ID/enable"
```

**预期**：
- `data.enabled=true`
- `data.nextRunAt` 非 null（计算的下一次触发时刻,Asia/Shanghai 时区次日 03:00:00 → UTC 减 8h）
- `data.version=1`(乐观锁 +1)

### 3.6 POST /api/inspections/plans/{planId}/disable — 停用计划

```bash
curl -s -b "$COOKIES" \
  -H "X-XSRF-TOKEN: $CSRF" \
  -X POST "http://127.0.0.1:8080/api/inspections/plans/$PLAN_ID/disable"
```

**预期**：`data.enabled=false`,`data.nextRunAt=null`(停用清空避免调度器误拉取)。

### 3.7 POST /api/inspections/plans/{planId}/run — 立即触发

```bash
RUN_JSON=$(curl -s -b "$COOKIES" \
  -H "X-XSRF-TOKEN: $CSRF" \
  -X POST "http://127.0.0.1:8080/api/inspections/plans/$PLAN_ID/run")
echo "$RUN_JSON"
EXEC_ID=$(printf '%s' "$RUN_JSON" | sed -n 's/.*"executionId":"\([^"]*\)".*/\1/p')
echo "EXEC_ID=$EXEC_ID"
```

**预期**：
- `data.executionId` 非空（UUID,36 字符）
- `data.status` 可能为 `RUNNING`（即时返回）或已是终态（快速完成）

### 3.8 GET /api/inspections/executions/{executionId} — 轮询直到终态

```bash
for i in 1 2 3 4 5 6 7 8; do
  RESP=$(curl -s -b "$COOKIES" "http://127.0.0.1:8080/api/inspections/executions/$EXEC_ID")
  STATUS=$(printf '%s' "$RESP" | sed -n 's/.*"status":"\([^"]*\)".*/\1/p')
  echo "[$i] status=$STATUS"
  case "$STATUS" in
    SUCCESS|PARTIAL_SUCCESS|FAILED|SKIPPED) echo "终态: $RESP"; break ;;
  esac
  sleep 2
done
```

**预期**：
- 60s 内收敛到 4 终态之一
- Windows 上 `disk_usage_tool` 必然 fail → HEALTH/DISK 模板大概率 `FAILED`(key tool 失败)
- `data.abnormal=true`(FAILED 时 abnormal 反映 key tool 失败 OR threshold 突破)
- `data.auditId` 非空（`auditId` 关联到审计中心,审计 `triggerType=SCHEDULED/MANUAL` + `operator=admin`）
- `data.reportId` 可能非空（报告生成可能降级 null,正常）

### 3.9 GET /api/inspections/executions — 列表 + 过滤

```bash
# 全部
curl -s -b "$COOKIES" "http://127.0.0.1:8080/api/inspections/executions?size=10"

# 按 planId 过滤
curl -s -b "$COOKIES" "http://127.0.0.1:8080/api/inspections/executions?planId=$PLAN_ID&size=10"

# 按 status 过滤
curl -s -b "$COOKIES" "http://127.0.0.1:8080/api/inspections/executions?status=FAILED&size=10"
```

**预期**：每种过滤返回包含 `EXEC_ID` 至少 1 条；size clamp 在 [1..100]。

### 3.10 PUT /api/inspections/plans/{planId} — 部分更新(乐观锁)

```bash
# 当前 version=1,改 description 必须带 version
curl -s -b "$COOKIES" \
  -H "Content-Type: application/json" \
  -H "X-XSRF-TOKEN: $CSRF" \
  -X PUT "http://127.0.0.1:8080/api/inspections/plans/$PLAN_ID" \
  -d '{"description":"P1-02 验收 — 已更新","version":1}'
```

**预期**：`data.version=2`,`data.description` 更新成功。

### 3.10.1 乐观锁冲突验证

```bash
# 再 PUT 用 version=1(已过期)
curl -s -b "$COOKIES" \
  -H "Content-Type: application/json" \
  -H "X-XSRF-TOKEN: $CSRF" \
  -X PUT "http://127.0.0.1:8080/api/inspections/plans/$PLAN_ID" \
  -d '{"description":"过期更新","version":1}'
```

**预期**：HTTP 409,`message` 含 "version" 字样。

### 3.11 DELETE /api/inspections/plans/{planId} — 删除

```bash
curl -s -b "$COOKIES" \
  -H "X-XSRF-TOKEN: $CSRF" \
  -X DELETE "http://127.0.0.1:8080/api/inspections/plans/$PLAN_ID"
```

**预期**：`{"code":200,...}`。**红线**:execution/audit/report **不级联删除**（前端删除确认文案"历史执行、报告和审计记录不会被删除"必须与此一致）。

### 3.12 GET /api/inspections/plans/{planId} — 删除后 404

```bash
curl -s -b "$COOKIES" "http://127.0.0.1:8080/api/inspections/plans/$PLAN_ID"
```

**预期**：HTTP 404,`code=404`,`message="[plan] 不存在: <id>"`。

### 3.13 校验矩阵

| 场景 | 端点 | 预期 HTTP | 备注 |
|---|---|---|---|
| 未登录 GET | /api/inspections/templates | 401/403 | filter chain 在 SecurityConfig |
| 无 CSRF POST | /api/inspections/plans | 403 | CsrfFilter 在 AuthorizationFilter 之前 |
| 无 CSRF POST 有 session | /api/inspections/plans | 401 | 触发 CsrfFilter 后 |
| body operator=forged-admin | /api/inspections/plans/{id}/run | 200 但 operator 取自 session | SecurityBoundaryTest.bodyOperatorIsIgnored 覆盖 |
| 不存在的 planId | /api/inspections/plans/{nonexistent} | 404 | controller @ExceptionHandler |
| 重复 schedule 计划 | POST /plans (重复 name) | 409 | uk_inspection_plan_name 唯一约束 |

---

## 4. 4 个核心验收场景（端到端 Live Demo）

> **前提**：前端 `cd frontend && npm run dev`(127.0.0.1:5173,proxy `/api` → 8080),后端 8080 跑着。
>
> **浏览器访问**：`http://127.0.0.1:5173/inspections`
>
> **Windows 注意事项**：OS 工具(`df`/`ps`/`ss`/`systemctl`/`journalctl`)在 Windows 上大部分降级为 `failed`,
> 但巡检**逻辑闭环**仍可演示(status 收敛、审计写入、报告生成、通知策略)。

### 4.1 场景一:健康度巡检(HEALTH 模板,多工具 fan-out)

**操作**:
1. 浏览器访问 `/inspections`
2. 点击「**新建计划**」按钮
3. templateType 选「**系统健康检查**」(HEALTH)
4. 填 name=`e2e-健康巡检`,serviceName=`nginx`(必须 ∈ `kylinops.inspection.allowed-services`)
5. scheduleType 默认 `DAILY`,localTime 默认 `03:00:00`,timezone 默认 `Asia/Shanghai`
6. notificationPolicy 选 `ON_ABNORMAL`(异常时通知)
7. 提交 → 列表新增一行(初始 `enabled=false`,`nextRunAt=null`)
8. 行内点「**启用**」→ 按钮变「**停用**」,`nextRunAt` 出现(次日 03:00 Asia/Shanghai)
9. 行内点「**立即执行**」→ 弹出进度对话框
10. 等待(Windows 上一般 5-30s)→ 进度条关闭,执行记录新增一行

**预期**:
- 模板字段由后端 `/templates` 动态驱动:4 个字段(serviceName/cpuWarningPercent/memoryWarningPercent/diskWarningPercent)
- HEALTH 模板 stage1 = `system_info_tool`/`cpu_status_tool`/`memory_status_tool`/`disk_usage_tool`/`process_list_tool`/`network_port_tool`/`service_status_tool`(7 工具并发);stage2 = `journal_log_tool`
- Windows 上 status 大概率 `FAILED`(key tool `disk_usage_tool` 失败,因缺 `df -h`)
- execution 行 `summary` 含 "X/Y 工具成功" 计数
- 异常判定:`abnormal=true`(key tool 失败)
- 通知:policy=ON_ABNORMAL + event=INSPECTION_ABNORMAL → 走通知通道
- 审计详情:点「**查看审计**」→ `/audit?auditId=<uuid>`,triggerType=`MANUAL`,operator=`admin`,sessionId=null(巡检红线)

**手动断言**:
- [ ] 模板字段由 `/templates` 动态生成(前端无 HEALTH 硬编码分支)
- [ ] 创建 → 启用 → 立即执行 三步走通
- [ ] 终态收敛(60s 内)到 SUCCESS/PARTIAL_SUCCESS/FAILED/SKIPPED 之一
- [ ] auditId 可在 `/audit?auditId=<id>` 看到,triggerType=MANUAL,无 sessionId
- [ ] ON_ABNORMAL 通知:飞书/邮件通道已配 → 收到一条 INSPECTION_ABNORMAL 通知

### 4.2 场景二:磁盘巡检(DISK 模板,带 journal_log 扩展)

**操作**:
1. 新建计划,templateType 选「**磁盘诊断**」(DISK)
2. scanDir 填 `/tmp/cache-demo`(必须在 `BaseOSValidator.ALLOWED_SCAN_ROOTS` 白名单)
3. logServiceName 填 `nginx`(非空 → stage2 追加 `journal_log_tool`)
4. thresholds 默认 `diskWarningPercent=85`,`largeFileMinMb=1024`
5. 提交 → 启用 → 立即执行

**预期**:
- DISK 模板 stage1 = `disk_usage_tool`;stage2 = `large_file_scan_tool`;stage3(条件) = `journal_log_tool`
- execution plan_snapshot 固化(后续 plan 修改不影响该 execution)
- Windows 上 `disk_usage_tool` 必然 fail → status=FAILED(key tool 失败)
- Linux 上若磁盘 ≥85% → threshold breach → `abnormal=true`,event=INSPECTION_ABNORMAL
- Linux 上若磁盘 < 85% → status=SUCCESS 或 PARTIAL_SUCCESS(取决于 large_file_scan_tool)

**手动断言**:
- [ ] scanDir 校验:`/etc`(不在白名单)→ 创建返回 400,`message` 含 `[scanDir]`
- [ ] 启用后 `nextRunAt` 计算正确
- [ ] 执行完成后 plan_snapshot_json 在 execution detail 中可查(GET /executions/{id})
- [ ] abnormal 反映 key tool 失败 OR 阈值突破

### 4.3 场景三:服务巡检(SERVICE 模板,L2 重启正交)

**操作**:
1. 新建计划,templateType 选「**服务诊断**」(SERVICE)
2. serviceName 填 `nginx`,expectedPort 填 `80`(可选)
3. 提交 → 启用 → 立即执行

**预期**:
- SERVICE 模板 stage1 = `service_status_tool` + `network_port_tool`;stage2 = `journal_log_tool`
- key tool = `service_status_tool`
- Windows 上 service_status_tool 必然 fail → status=FAILED
- Linux 上若 nginx active + 端口 80 监听 → status=SUCCESS
- **正交**:SERVICE 巡检**不创建** PendingAction;L2 重启走 ChatConsole user prompt 触发(`/api/chat/send` → RiskCheck → CONFIRM → `/api/actions/confirm`)

**手动断言**:
- [ ] 不出现 ExecutionConfirmCard 渲染(巡检是只读路径)
- [ ] execution summary 含 service_status_tool / network_port_tool / journal_log_tool 计数
- [ ] 异常时发 INSPECTION_FAILED 通知(FAILED 优先于 ABNORMAL 事件类型)

### 4.4 场景四:立即执行触发 + 进度对话框 + 轮询

**操作**:
1. 任一已启用 plan 行内点「**立即执行**」
2. RunNowProgressDialog 弹出,显示「执行中」
3. 后端每 1.5s 轮询 `GET /executions/{id}`
4. 终态后显示「**关闭**」按钮 → 点击关闭

**预期**:
- 进度对话框 `data-testid="run-progress-dialog"`,状态显示中文(RUNNING=执行中/SUCCESS=成功/PARTIAL_SUCCESS=部分成功/FAILED=失败/SKIPPED=已跳过)
- 轮询间隔:前端 `setInterval(1500)`,超时 60s(若一直 RUNNING → 显示「巡检未在预期时间内完成」+ emit('done', status='TIMEOUT'))
- 终态条件:`status ∈ {SUCCESS, PARTIAL_SUCCESS, FAILED, SKIPPED}` → clearTimers + emit('done')
- operator 永远从 session 拿:body 不带 operator 字段,curl 已验证(§3.7)

**手动断言**:
- [ ] 进度条 1.5s 间隔刷新 status(浏览器 devtools 看 network)
- [ ] 60s 内必收敛(若超时 → 显示 TIMEOUT 文案,不假死)
- [ ] 关闭后执行记录区新增一行,「查看报告」/「查看审计」按钮在 auditId/reportId 非空时显示
- [ ] 行内跳转 `/audit?auditId=...` 与 `/reports?reportId=...` 通畅

### 4.5 跨场景联动(可选)

**操作**:
1. 在执行记录点「**查看报告**」→ 跳到 `/reports?reportId=<id>`
2. 报告详情中「**源审计**」链接回指 `/audit?auditId=<id>`
3. 审计详情含 `triggerType=MANUAL` + `operator=admin`,无 `sessionId`
4. 删除该 plan → 执行记录/审计/报告**仍然存在**(不级联)

**手动断言**:
- [ ] 5 步跳转通畅,跨页面 auditId 一致
- [ ] 删除 plan 不影响历史 execution/audit/report(设计 §11.1 红线)
- [ ] 报告 Markdown 渲染安全(`html:false` 锁定)

---

## 5. 前端验证

### 5.1 安装依赖

```bash
cd frontend
npm install
```

**预期**：约 1-3 分钟,无错误退出。

### 5.2 单元测试

```bash
npm run test:unit -- --run 2>&1 | tee /tmp/p1-02-frontend-unit.log
```

**预期**：
- `inspection.ts` API 层 14 用例(端点契约、404/400 错误透传、updatePlan version 强制)
- `PlanListTable` 8 用例(列表渲染、4 行内按钮、删除确认文案)
- `PlanFormDialog` 7 用例(create/update 二合一、defineExpose 暴露 form setTemplateType)
- `RunNowProgressDialog` 6 用例(setInterval + vi.useFakeTimers + advanceTimersByTimeAsync)
- `AppLayout` 1 用例(NavItems 第 8 项定时巡检)
- 填入：`Test Files: <N> passed, Tests: <N> passed`
- 总数:`Tests 335 passed`(基线)

### 5.3 前端构建

```bash
npm run build 2>&1 | tee /tmp/p1-02-frontend-build.log
```

**预期**：
- `vue-tsc --noEmit` 通过
- `vite build` 成功
- 产物 `frontend/dist/index.html` 存在
- 填入产物大小：`dist/index.html size: <KB>`

### 5.4 Playwright E2E(mock 模式)

```bash
cd frontend
npm run test:e2e 2>&1 | tee /tmp/p1-02-e2e-mock.log
```

**预期**：
- `inspection.spec.ts` 5 用例全过:① 空列表加载 ② 创建 HEALTH 计划 ③ 启用 disabled 计划 ④ 立即执行进度对话框 ⑤ 删除按钮可见
- 填入：`Tests: 38 passed, Failed: 0`(基线)+ `Skipped: 3`

### 5.5 Playwright Live 烟雾测试(可选)

需要后端在 :8080 跑着。**先启动后端**(见 §2)。

```bash
# PowerShell
$env:E2E_LIVE = 'true'
npx playwright test tests/e2e/inspection.spec.ts --reporter=list
Remove-Item Env:E2E_LIVE

# 或 Git Bash
E2E_LIVE=true npx playwright test tests/e2e/inspection.spec.ts --reporter=list
```

**预期**：mock 被禁用,真实命中后端。**注意**:live 模式依赖 OS 工具,Windows 上 HEALTH/DISK 模板大概率 FAILED,但 e2e 应仍能渲染终态(填入 `Live tests: <N> passed`)。

---

## 6. Forbidden / 红线最终扫描

```bash
cd "D:/Work/code/kylin-ops/.claude/worktrees/p1-02-inspection-mvp"

# 6.1 巡检路径无 raw shell
rg -n 'ProcessBuilder|api/(exec|shell|command/run)' backend/src/main/java/com/kylinops/inspection && echo "FAIL" || echo "PASS: inspection 无 raw shell"

# 6.2 巡检路径无 LLM 调用
rg -n 'chatClient|llmClient|LlmIntent' backend/src/main/java/com/kylinops/inspection && echo "FAIL" || echo "PASS: inspection 不调用 LLM"

# 6.3 巡检路径无 PendingAction 创建
rg -n 'PendingAction.*save|pendingActionService.create' backend/src/main/java/com/kylinops/inspection && echo "FAIL" || echo "PASS: inspection 不创建 PendingAction"

# 6.4 模板只引用 L0/L1 + READ 工具(代码层校验)
rg -n 'RiskLevel.L[2-4]|PermissionType.WRITE|PermissionType.ADMIN' backend/src/main/java/com/kylinops/inspection/InspectionTemplateRegistry.java && echo "FAIL" || echo "PASS: 模板工具风险/权限合规"

# 6.5 巡检审计 sessionId=null(CLAUDE.md 红线 5)
rg -n 'createInspectionAudit' backend/src/main/java/com/kylinops/audit -A 5 | rg -i 'sessionId' && echo "FAIL: sessionId 不应为 null 的传递" || echo "PASS: 巡检审计 sessionId=null"
```

**预期**：5 个 PASS。

---

## 7. abandoned 执行恢复验证（启动期）

> **目的**: 验证进程崩溃 → 重启后,abandoned RUNNING execution 被自动收尾(不卡死)。

### 7.1 制造 RUNNING 执行

```bash
# 1) 创建一个已启用 plan
PLAN_JSON=$(curl -s -b "$COOKIES" \
  -H "Content-Type: application/json" \
  -H "X-XSRF-TOKEN: $CSRF" \
  -d '{
    "name":"recovery-test",
    "templateType":"SERVICE",
    "templateParams":{"serviceName":"nginx"},
    "scheduleType":"DAILY",
    "localTime":"03:00:00",
    "timezone":"Asia/Shanghai",
    "notificationPolicy":"ALWAYS"
  }' http://127.0.0.1:8080/api/inspections/plans)
PLAN_ID=$(printf '%s' "$PLAN_JSON" | sed -n 's/.*"planId":"\([^"]*\)".*/\1/p')
curl -s -b "$COOKIES" -H "X-XSRF-TOKEN: $CSRF" -X POST "http://127.0.0.1:8080/api/inspections/plans/$PLAN_ID/enable" > /dev/null

# 2) 立即触发拿 executionId
RUN_JSON=$(curl -s -b "$COOKIES" -H "X-XSRF-TOKEN: $CSRF" -X POST "http://127.0.0.1:8080/api/inspections/plans/$PLAN_ID/run")
EXEC_ID=$(printf '%s' "$RUN_JSON" | sed -n 's/.*"executionId":"\([^"]*\)".*/\1/p')
echo "EXEC_ID=$EXEC_ID"

# 3) 验证 execution 处于 RUNNING 状态
curl -s -b "$COOKIES" "http://127.0.0.1:8080/api/inspections/executions/$EXEC_ID" | grep -oE '"status":"[^"]*"'
# 预期: "status":"RUNNING"
```

### 7.2 强制 kill 进程(模拟进程崩溃)

```bash
# 找到 java 进程 PID
JAVA_PID=$(powershell -Command "(Get-Process java -ErrorAction SilentlyContinue).Id" | tr -d '\r\n' | head -c 10)
echo "JAVA_PID=$JAVA_PID"

# 强制 kill(等价于 kill -9,跳过正常 shutdown 钩子)
powershell -Command "Stop-Process -Id $JAVA_PID -Force"
sleep 3

# 确认进程已退出
powershell -Command "Get-Process java -ErrorAction SilentlyContinue"
# 预期: 无输出
```

### 7.3 临时降低 abandoned 阈值 + 重启

```bash
# 在 backend 终端通过环境变量覆盖默认 1h 阈值(临时改 5s 便于快速验证)
export KYLINOPS_INSPECTION_RECOVERY_ABANDONED_THRESHOLD=PT5S

# 重新启动后端(注意:同一 H2 文件 → 数据不丢)
cd backend
java -jar target/kylin-ops-guard.jar > /tmp/p1-02-restart.log 2>&1 &
sleep 15

# 查日志: 应看到 "启动期恢复: 共扫描 N 条 RUNNING, 恢复 M 条"
grep "启动期恢复" /tmp/p1-02-restart.log
```

**预期**:
- 日志 `启动期恢复: 共扫描 1 条 RUNNING, 恢复 1 条`(因为 EXEC_ID 处于 RUNNING 且 startedAt 距 now > 5s)
- execution 被改为 FAILED,summary="执行进程异常终止(abandoned)"
- 若 plan.notificationPolicy=ALWAYS → emit INSPECTION_FAILED 通知

### 7.4 验证收尾结果

```bash
# 重新登录(cookies 在重启后失效)
COOKIES=/tmp/p1-02-cookies.txt
rm -f "$COOKIES"
CSRF_JSON=$(curl -s -c "$COOKIES" -b "$COOKIES" http://127.0.0.1:8080/api/auth/csrf)
CSRF=$(printf '%s' "$CSRF_JSON" | sed -n 's/.*"csrfToken":"\([^"]*\)".*/\1/p')
curl -s -c "$COOKIES" -b "$COOKIES" \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $CSRF" \
  -d '{"username":"admin","password":"admin"}' \
  http://127.0.0.1:8080/api/auth/login > /dev/null

# 查询 execution
curl -s -b "$COOKIES" "http://127.0.0.1:8080/api/inspections/executions/$EXEC_ID" | python -m json.tool
```

**预期**:
- `data.status` = `"FAILED"`
- `data.summary` = `"执行进程异常终止(abandoned)"`
- `data.finishedAt` 非 null
- `data.auditId` 非空(原执行已挂的审计被 markCompleted)
- `data.reportId` 可能非空(reportService.generateFromInspectionAudit 降级 null OK)

### 7.5 清理

```bash
# 还原默认阈值
unset KYLINOPS_INSPECTION_RECOVERY_ABANDONED_THRESHOLD

# 删测试 plan
curl -s -b "$COOKIES" -H "X-XSRF-TOKEN: $CSRF" -X DELETE "http://127.0.0.1:8080/api/inspections/plans/$PLAN_ID"
```

**手动断言**:
- [ ] 进程崩溃 → 重启 → abandoned RUNNING 被自动收尾
- [ ] 审计 + 报告 + 通知(若 policy 允许)都按降级顺序处理
- [ ] execution 不会无限停留在 RUNNING 状态

---

## 8. Git 工作树干净检查

```bash
cd "D:/Work/code/kylin-ops/.claude/worktrees/p1-02-inspection-mvp"
git status --short
```

**预期**：无输出(工作树干净,所有改动已 commit)。

```bash
# 不应误提交 H2 / target / node_modules
find . -path './frontend/node_modules' -prune -o -path './backend/target' -prune -o -path './backend/data' -prune -o -type f -name '*.mv.db' -print 2>/dev/null
```

**预期**：无 H2 数据库文件(若启动过 backend,会有 `data/kylinops.mv.db`,但应在 .gitignore)。

---

## 9. 验收清单(合入 master 前必查)

- [ ] 后端测试全绿:`Tests run: 879, Failures: 0, Errors: 0, Skipped: 2`
- [ ] 前端 vitest 全绿:`Tests: 335 passed`
- [ ] 前端 build 成功:`dist/index.html` 存在
- [ ] Playwright e2e 全绿:`38 passed + 3 skipped`
- [ ] 11 个 REST 端点 curl 全跑通(§3.1-3.13)
- [ ] 4 个核心场景手动验证通过(§4.1-4.4)
- [ ] 5 个红线扫描全 PASS(§6)
- [ ] abandoned 恢复验证通过(§7)

**打 tag**:
```bash
cd "D:/Work/code/kylin-ops/.claude/worktrees/p1-02-inspection-mvp"
git tag v0.4-inspection-mvp
```

---

## 10. 决策点(合入 master 前)

evidence 全部填入 `docs/test/p1-02-inspection-acceptance.md` 后,可以:

1. 合并到 master(本地)
2. 推送并创建 PR
3. 保留分支
4. 丢弃

**Linux CI 必查**(防 Windows localhost RTT 掩盖 race):
```bash
git push origin worktree-p1-02-inspection-mvp
gh pr create --base master --title "feat(inspection): P1-02 定时巡检 MVP" --body "..."
gh run watch
# 等待 CI 全绿;任一红 → revert 或 hotfix
```

合并/打 tag 前**必须**保证 §9 验收清单 8 项已确认。

---

## 11. 故障排查

| 现象 | 排查 |
|---|---|
| mvn test 启动期 `IllegalStateException: 巡检模板 X 工具 Y 风险等级违规` | ToolRegistry 注册的工具被改为 L2+,需回退或重写模板(不允许绕过) |
| 进度对话框 TIMEOUT(60s 内未收敛) | 看 backend log 是否卡在 tool executor,或 Windows OS 工具全失败导致 stage 跑满 timeoutMs |
| e2e `inspection.spec.ts` navigate 失败 | 旧 vite 进程未重启 → `Stop-Process java` 后 `npm run dev` 重启(踩坑 #49) |
| 审计详情无 triggerType | 旧执行走 auditLogService.create 而非 createInspectionAudit(检查 migration V7 已加 trigger_type/operator 列) |
| 调度器自动触发不工作 | test profile 关闭 `kylinops.inspection.scheduler.auto-start`(默认 prod 开启);检查 backend log "InspectionScheduler 已启动" |
| abandoned 恢复不触发 | 默认 1h 阈值过长;`export KYLINOPS_INSPECTION_RECOVERY_ABANDONED_THRESHOLD=PT5S` 临时降级 |
| DELETE plan 后 GET plan 返回 404 但 execution 仍可查 | 符合设计(物理删除 plan 不级联);execution/audit/report 独立保留 |
| ON_ABNORMAL 策略但 FAILED 没发通知 | 检查 `INSPECTION_FAILED` vs `INSPECTION_ABNORMAL` 正交:FAILED 事件 detail.abnormal=false;ON_ABNORMAL 仅对 ABNORMAL 触发;**改用 ALWAYS** 验证全通知路径 |
| Windows 上 status=FAILED(abnormal=true) | 正常 — key tool `disk_usage_tool` 因缺 `df -h` 必然 fail;Linux/LoongArch 上若磁盘 < 阈值则正常 SUCCESS |

---

## 12. 已知限制

- **OS 工具在 Windows 上大部分降级**: `df`/`ps`/`ss`/`systemctl`/`journalctl` 全部不可用,导致 HEALTH/DISK 模板大概率 `FAILED`。**这是预期**:巡检逻辑闭环(status 收敛/审计/报告/通知)仍可演示,真实 OS 数据需在 Linux/LoongArch 验证。
- **LoongArch 真实部署未验证**: 本指南未在目标机执行过完整 4 场景 + abandoned recovery。
- **Spring Boot Scheduler 多实例**: 当前设计为单实例 leader,多实例部署需要外部锁(quartz/shedlock),P1-02 未做。
- **计划数量上限**: 当前无显式上限,生产环境建议加 `@kylinops.inspection.max-plans` 限流(避免 H2/PG 计划表膨胀)。
- **审计/报告/通知在 FAILED 时的状态**: abnormal/FAILED 正交(踩坑 #15),前端需清晰展示 5 种 status 而非二元的 success/fail。

---

## 13. 完成后回报

验收完成后,请把以下信息回报给主控代理:

- `mvn test` 测试数(期望 `Tests run: 879, Skipped: 2`)
- `npm run test:unit` 测试数(期望 `Tests: 335 passed`)
- `npm run build` 状态(期望 `dist/index.html` 存在)
- `npm run test:e2e` 测试数(期望 `38 passed + 3 skipped`)
- 11 个 REST 端点 curl 通过状态
- 4 场景手动验证结果(§4.1-4.4)
- 5 个红线扫描结果(§6,全 PASS)
- abandoned 恢复验证结果(§7)
- 是否需要打 `v0.4-inspection-mvp` tag
- 是否合并到 master / 创建 PR / 保留分支
