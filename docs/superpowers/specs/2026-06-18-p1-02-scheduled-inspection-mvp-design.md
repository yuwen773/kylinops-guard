# P1-02 定时巡检 MVP 设计

> 日期：2026-06-18
> 状态：✅ **COMPLETED** — 2026-06-20 合入 master (merge commit `2602ac9`)
> 对应缺陷：D-08 无定时巡检 / 无人值守

## 0. 完成元数据

- **完成时间**：2026-06-20
- **Merge commit**：`2602ac9`（worktree-p1-02-inspection-mvp → master, --no-ff）
- **Tag**：`v0.4-inspection-mvp`（annotated）
- **Worktree 路径**：`D:/Work/code/kylin-ops/.claude/worktrees/p1-02-inspection-mvp`（harness 管理,保留）
- **验收基线**：
  - backend `mvn test`：**879 / 879 + 2 skipped**（exit 0,3:07 min）
  - frontend `vitest --run`：**335 / 335**（exit 0,34.67s,32 files）
  - Playwright `npx playwright test`：**38 passed + 3 skipped**（exit 0,26.6s）
  - 合并后 smoke：`mvn test-compile` exit 0 + `vue-tsc --noEmit` exit 0
- **验收指南**：[docs/test/p1-02-inspection-acceptance-guide.md](../../test/p1-02-inspection-acceptance-guide.md)
- **实现范围（9 commits）**：
  1. `442d933` feat(inspection): 添加巡检周期计算（InspectionScheduleCalculator）
  2. `1fc79e9` feat(inspection): 添加巡检计划持久化（V7 schema + InspectionPlan/Execution 实体）
  3. `70942f4` feat(inspection): 添加内置巡检模板（HEALTH/DISK/SERVICE + Validator）
  4. `6b1e8d8` feat(inspection): 打通巡检审计报告通知（AuditLog + Report + Notification 闭环）
  5. `04df039` feat(inspection): 接入巡检执行闭环（InspectionExecutionService + ResultEvaluator）
  6. `aae0c52` feat(inspection): 添加巡检调度与恢复（InspectionScheduler + InspectionRecovery）
  7. `cbae292` feat(inspection): 添加巡检管理接口（InspectionController 11 REST 端点）
  8. `9c252dd` feat(inspection): 添加巡检管理前端页面（ScheduledInspection 一级页面 + 7 子组件）
  9. `de6b091` docs(test): 添加 P1-02 定时巡检 MVP 验收指南
- **核心契约**：
  - 11 个 REST 端点：`GET /templates` + 6 plans CRUD + 2 executions + `POST /run`
  - 3 个内置模板：HEALTH（7+1 工具）、DISK（2+1 条件扩展）、SERVICE（2+1 工具）
  - 5 个执行状态：RUNNING / SUCCESS / PARTIAL_SUCCESS / FAILED / SKIPPED
  - 3 个通知策略：NEVER / ON_ABNORMAL / ALWAYS
  - abandoned 启动期恢复：REQUIRES_NEW 独立事务,默认阈值 1h
- **CLAUDE.md 红线合规**：
  - 巡检路径无 raw shell（`ProcessBuilder` 零命中）
  - 巡检路径不调用 LLM
  - 巡检不创建 `PendingAction`（只读路径）
  - 模板只引用 L0/L1 + READ 工具（启动期 fail-fast）
  - 巡检审计 `sessionId=null`,`triggerType` + `operator` 识别来源
- **已知限制**：
  - Windows OS 工具大量降级（缺 `df`/`ps`/`ss`/`systemctl`/`journalctl`）,HEALTH/DISK 模板在 Windows 上大概率 FAILED,逻辑闭环仍可演示
  - Linux/LoongArch 真实目标机完整 4 场景未在本环境验证
  - 单实例 leader 调度,多实例需外部锁（quartz/shedlock）,P1-02 未做

## 1. 背景与目标

当前系统已有三类实时诊断能力：

- 健康检查：多工具 fan-out、健康评估链、审计和报告。
- 磁盘诊断：磁盘用量、大文件扫描、日志证据和 RCA。
- 服务诊断：服务状态、端口、日志证据和 RCA。

系统也已有异步 Webhook / 飞书通知与 `NotificationRecord` 持久化，但所有能力都需要用户主动发起。P1-02 的目标是新增无人值守巡检闭环：

```text
巡检计划 → 定时或手动触发 → 只读工具执行 → RCA / 异常判定
→ AuditLog → 报告 → 按策略通知
```

成功标准：

1. 管理员可以创建、编辑、启停、删除和立即执行巡检计划。
2. 支持 HEALTH、DISK、SERVICE 三个固定模板。
3. 每次实际调用 OS 工具的执行都有独立执行记录和 `auditId`，并必须尝试生成报告。
4. 异常或执行失败可通过现有通知通道主动推送。
5. 定时巡检不执行重启、清理或任何写操作，不创建或确认 `PendingAction`。
6. LLM 关闭时仍能完整执行。

## 2. 范围与已确认决策

| 主题 | 决策 |
|---|---|
| 安全边界 | 严格只读：巡检、RCA、审计、报告、通知 |
| 调度方式 | 每日、每周、每月；表单配置，内部计算触发时间，不开放 cron |
| 模板 | HEALTH、DISK、SERVICE 三个内置模板 |
| 服务范围 | SERVICE 每个计划只检查一个经过 allowlist 校验的服务 |
| 重入 | 同一计划已有运行实例时，新触发写 `SKIPPED` |
| 报告 | 每次拥有有效 `auditId` 的非 `SKIPPED` 执行必须尝试生成；生成失败按降级规则处理 |
| 通知 | `ON_ABNORMAL` 或 `ALWAYS`；发送到所有已启用且支持事件的全局通道 |
| 重试 | 单次失败不自动重试；下一周期正常继续 |
| 阈值 | 每计划可配置模板限定的结构化阈值 |
| 历史保留 | 删除计划后保留执行、报告和审计历史 |
| 页面 | 新增一级页面“定时巡检” |
| 身份 | 定时触发为 `SYSTEM_SCHEDULER`；立即执行记录当前管理员 |
| 认证 | 沿用现有单管理员模型，本期不引入普通用户或 RBAC |
| 错过任务 | 应用停机期间不补跑，恢复后计算下一个未来时间 |

不在本次范围：

- 自动重启、清理或其他写操作。
- Workflow DSL、任意表达式和用户自定义工具组合。
- 多主机、多服务批量巡检。
- 失败自动重试和错过任务补跑。
- 计划级通知通道或收件人选择。
- 新增钉钉、企微、邮件通道或通知配置页面。
- 普通用户、VIEWER 角色和用户管理。

## 3. 总体架构

新增独立 `com.kylinops.inspection` 领域，不模拟聊天请求，不创建虚假会话，也不依赖自然语言意图识别。

```text
InspectionController
        ↓
InspectionPlanService
        ↓
InspectionScheduler
        ↓
InspectionExecutionService
        ├─ InspectionTemplateRegistry
        ├─ ToolPlanningService / ToolExecutor
        ├─ RootCauseAnalyzer
        ├─ RiskCheckService
        ├─ AuditLogService
        ├─ ReportService
        └─ NotificationService
```

组件职责：

- `InspectionPlanService`：计划 CRUD、启停、参数校验、乐观锁和下次执行时间计算。
- `InspectionScheduler`：发现到期计划、申请执行权、触发执行和防重入。
- `InspectionExecutionService`：创建执行记录和审计，执行模板，完成 RCA、异常判定、报告和通知。
- `InspectionTemplateRegistry`：注册固定模板，定义参数、阈值、工具计划和关键工具。
- `InspectionScheduleCalculator`：按计划时区计算每日、每周和每月的下一触发时间。

复用原则：

- 复用现有工具计划与执行能力，但不复用聊天、意图识别或 LLM 入口。
- 复用现有三类 `RootCauseAnalyzer`、`AuditLogService`、`ReportService` 和通知发送基础设施。
- 如果现有服务接口只适用于 ChatConsole，应提取最小的领域接口，不复制整套编排逻辑。

## 4. 安全闭环

巡检模板只能引用已注册的只读 `OpsTool`。执行分为完整计划预检和逐工具重检：

1. 工具已注册且启用。
2. 工具风险级别为 L0 或 L1。
3. `permissionType` 为现有枚举 `PermissionType.READ`。
4. 对每个工具构造 `RiskEvaluationContext`：
   - `targetType="tool"`，与现有规则目标类型一致。
   - `toolName` 为注册名称。
   - `params` 为已校验和脱敏的结构化参数。
   - `content` 使用确定性序列化：`<toolName> <canonical-json(params)>`。JSON key 按字典序排序，不包含密钥或完整工具输出，使现有仅匹配 `content` 的 `RiskRuleEngine` 仍能检查路径和参数。
5. `RiskCheckService.check(context, auditId)` 返回 `ALLOW`。

完整计划预检对所有步骤执行上述检查，任一条件不满足时：

- 不执行任何 OS 工具。
- 不创建或确认 `PendingAction`。
- 执行记录标记为 `FAILED`。
- 审计记录保存拒绝原因。
- 按失败事件执行通知策略。

每个工具实际调用前再次读取注册元数据并执行同样检查，以防计划预检后工具状态变化。逐工具重检失败时：

- 已完成的工具调用保留审计，不回滚只读结果。
- 立即停止所有剩余工具，不调用当前工具。
- 执行标记为 `FAILED`、`abnormal=true`，记录安全拒绝原因并进入报告与通知阶段。

所有 OS 访问仍通过 `ToolExecutor` 和已注册 `OpsTool`；调度器不得直接运行命令。阈值仅影响异常判定、报告和通知，不得修改风险等级或触发自动处置。

## 5. 数据模型

### 5.1 `inspection_plans`

| 字段 | 含义 |
|---|---|
| `plan_id` | 全局唯一计划 ID |
| `name` / `description` | 计划名称与说明 |
| `template_type` | `HEALTH` / `DISK` / `SERVICE` |
| `template_params_json` | 模板限定参数 |
| `thresholds_json` | 模板限定阈值 |
| `schedule_type` | `DAILY` / `WEEKLY` / `MONTHLY` |
| `schedule_config_json` | 执行时间、星期或日期，结构见 5.2 |
| `timezone` | IANA 时区 |
| `notification_policy` | `ON_ABNORMAL` / `ALWAYS` |
| `enabled` | 是否启用 |
| `next_run_at` / `last_run_at` | UTC 时间 |
| `created_by` / `updated_by` | 管理员主体 |
| `version` | JPA 乐观锁 |
| `created_at` / `updated_at` | 审计时间 |

新建计划默认 `enabled=false`。月度计划仅允许选择 1 至 28 日。

### 5.2 调度配置结构

API 与数据库使用相同的结构化 DTO，不接受 cron 字符串：

```json
{ "scheduleType": "DAILY", "localTime": "08:00" }
{ "scheduleType": "WEEKLY", "dayOfWeek": "MONDAY", "localTime": "08:00" }
{ "scheduleType": "MONTHLY", "dayOfMonth": 1, "localTime": "08:00" }
```

约束：

- `localTime` 固定为 24 小时制 `HH:mm`。
- `dayOfWeek` 使用 Java `DayOfWeek` 英文枚举 `MONDAY` 至 `SUNDAY`。
- `dayOfMonth` 为 1 至 28。
- `timezone` 必须通过 `ZoneId.of(...)` 校验并以 IANA ID 持久化，例如 `Asia/Shanghai`。
- 夏令时缺口中的本地时间顺延到该时区的第一个有效时间。
- 夏令时重叠时间选择较早的 offset，确保每个计划周期只触发一次。

### 5.3 `inspection_executions`

| 字段 | 含义 |
|---|---|
| `execution_id` | 全局唯一执行 ID |
| `plan_id` | 原计划 ID，可在计划删除后保留 |
| 计划快照 | 名称、模板、参数、阈值、通知策略 |
| `trigger_type` | `SCHEDULED` / `MANUAL` |
| `triggered_by` | `SYSTEM_SCHEDULER` 或当前管理员 |
| `status` | `RUNNING` / `SUCCESS` / `PARTIAL_SUCCESS` / `FAILED` / `SKIPPED` |
| `abnormal` | 系统状态是否异常 |
| `scheduled_at` | 计划触发时间 |
| `started_at` / `finished_at` | 实际执行时间 |
| `summary` / `error_message` | 脱敏摘要 |
| `conflicting_execution_id` | `SKIPPED` 时关联正在运行的执行 |
| `audit_id` / `report_id` | 闭环关联 |
| `created_at` | 创建时间 |

执行记录保存计划快照。计划删除采用物理删除，但不得级联删除执行、报告和审计历史。`plan_id` 是保留原计划 ID 的普通索引字段，不建立指向 `inspection_plans` 的数据库外键；历史展示以执行快照为准。

同一计划只能存在一个 `RUNNING` 执行。必须使用数据库原子申请或等效唯一约束保证，不能只依赖 JVM 内存锁。

### 5.4 审计兼容

现有审计模型面向请求和聊天入口，实施时应做最小扩展以表达巡检来源：

- `triggerType = SCHEDULED | MANUAL`。
- `operator = SYSTEM_SCHEDULER | <admin username>`。
- 不创建聊天 Session 或 Message。
- `userInput` 不伪造自然语言，可为空；计划名、模板、参数和执行来源写入结构化审计摘要。
- 每次执行使用独立 `auditId`，所有 ToolCall、RiskCheck、报告和通知共享该 ID。

## 6. 模板与阈值

涉及服务名的模板必须使用巡检专用 allowlist，不接受任意合法字符串：

```yaml
inspection:
  allowed-services:
    - nginx
```

- 默认值为 `nginx`，可通过部署配置增加其他允许服务。
- `GET /api/inspections/templates` 返回可选服务列表，前端使用下拉框而非自由输入。
- 保存计划和执行预检都必须同时通过 `BaseOSValidator.isValidServiceName` 与 allowlist 成员校验。
- allowlist 在计划创建后移除某服务时，该计划下次执行预检失败，不调用任何工具，并提示管理员修改计划。

### 6.1 HEALTH

参数：

- `serviceName`：必填，必须来自巡检服务 allowlist；用于服务状态和日志检查。

工具计划：

- 并行执行现有 `system_info_tool`、`cpu_status_tool`、`memory_status_tool`、`disk_usage_tool`、`process_list_tool`、`network_port_tool`。
- 同一并行阶段执行 `service_status_tool(serviceName)`。
- 随后执行 `journal_log_tool(serviceName, lines=50)`。

该计划是在现有 `SYSTEM_CHECK` 六工具计划上增加有明确目标的服务与日志检查，复用 `HealthCheckAnalyzer`。

阈值：

- `cpuWarningPercent`：50–100，默认 80。
- `memoryWarningPercent`：50–100，默认 80。
- `diskWarningPercent`：50–100，默认 85。

关键工具：system、CPU、memory、disk。

异常条件：

- 任一指标达到对应阈值。
- 任一关键工具失败。
- 日志经 6.3 定义的 `InspectionLogErrorClassifier` 判定存在错误。
- RCA 健康评分低于 80。

### 6.2 DISK

工具核心顺序为磁盘用量和大文件扫描；配置了日志目标时再执行相关日志检查。复用 `DiskDiagnosisAnalyzer`。

参数：

- `scanDir`：必填且只能选择 `BaseOSValidator.ALLOWED_SCAN_ROOTS` 下的路径；执行时转换为现有工具参数 `scanDirs=[scanDir]`。
- `logServiceName`：可选；配置后必须来自巡检服务 allowlist，并调用 `journal_log_tool(logServiceName, lines=50)`。

阈值：

- `diskWarningPercent`：50–100，默认 85。
- `largeFileMinMb`：100–1048576，默认 1024。

`largeFileMinMb` 不传入现有 `large_file_scan_tool`，而是在工具返回 `files[].sizeMB` 后做确定性的异常过滤。MVP 不提供 `topN` 参数，沿用工具内置的固定输出上限。RCA 适配层负责把当前 `files` 输出规范化为分析器可消费的证据结构。

关键工具：disk usage、large file scan。

异常条件：

- 磁盘达到阈值。
- 发现达到最小尺寸的大文件。
- 任一关键工具失败。

敏感目录可在报告中标记，但不得生成删除动作。

### 6.3 SERVICE

参数：

- `serviceName`：必填，必须来自巡检服务 allowlist。
- `expectedPort`：可选整数，范围 1–65535。

工具计划沿用现有服务诊断：`service_status_tool(serviceName)` 与全局 `network_port_tool` 并行，随后执行 `journal_log_tool(serviceName, lines=50)`。复用 `ServiceDiagnosisAnalyzer`。

新增轻量 `InspectionLogErrorClassifier` 适配现有日志输出：

- 只检查 `journal_log_tool.data.entries` 中本次返回的最多 50 行。
- 使用大小写不敏感的固定词边界模式：`error`、`failed`、`failure`、`fatal`、`panic`、`exception`、`segfault`、`oom`、`out of memory`、`permission denied`。
- 命中行经现有审计脱敏和截断后写入适配结果的 `errors` 列表，供 `ServiceDiagnosisAnalyzer` 使用。
- 不调用 LLM，不允许计划自定义模式；后续如需调整，必须修改代码并增加测试语料。

关键工具：service status。

异常条件：

- 服务不是正常运行状态。
- 配置了 `expectedPort` 且全局监听结果中不存在该端口。
- `InspectionLogErrorClassifier` 产生非空 `errors`。
- 关键工具失败。

未配置 `expectedPort` 时，网络端口结果只作为证据展示，不据此判定服务异常。

报告只能给出人工处置建议；重启建议必须明确标记为需用户另行发起并完成 L2 确认。

### 6.4 状态语义

- 工具均成功且编排完整：`SUCCESS`，无论指标是否异常。
- 部分非关键工具失败但仍得到可信结论：`PARTIAL_SUCCESS`，同时 `abnormal=true`。
- 关键工具失败、无法形成可信结论、审计创建失败或编排失败：`FAILED`。
- 同计划重入：`SKIPPED`。

执行是否成功与被巡检系统是否异常是两个独立维度。

## 7. 调度与执行流程

调度使用 Spring `TaskScheduler` 与数据库计划，不为每条计划创建固定 `@Scheduled` 方法。

```text
应用启动或计划变更
→ 加载 enabled 计划
→ 按计划时区计算 nextRunAt
→ 到期触发
→ 数据库原子申请执行权
→ 创建 execution 和 auditId
→ 校验模板、工具和 RiskCheck
→ 执行 OpsTool
→ RCA 与异常判定
→ 完成审计
→ 生成报告
→ 按策略通知
→ 更新 execution、lastRunAt 和 nextRunAt
```

规则：

- 应用停机期间错过的任务不补跑。
- “立即执行”不改变 `nextRunAt`。
- 停用计划取消未来触发，正在运行的执行允许完成。
- 正在运行的计划不得删除。
- 重入创建 `SKIPPED` 记录，不生成报告、不发送通知。
- 单次失败不自动重试，下一周期继续。
- 调度正常运行时触发偏差目标不超过 5 秒。
- 启动恢复遗留 `RUNNING` 时：
  - 将执行标记为 `FAILED`，原因是“执行进程异常终止”。
  - 若原 `auditId` 存在，则将该审计终结为失败，尝试生成失败报告，并按原通知策略发送失败通知。
  - 若原 `auditId` 缺失，则不得继续调用工具或伪造审计；执行记录保留高优先级错误，等待管理员排查。
  - 更新计划 `lastRunAt`，并从当前时间计算下一个未来 `nextRunAt`，不补跑中断周期。

## 8. 报告与通知

每次拥有有效 `auditId` 的非 `SKIPPED` 执行都必须尝试调用现有报告能力，报告类型按模板映射：

- HEALTH → 健康检查报告。
- DISK → 磁盘诊断报告。
- SERVICE → 服务诊断报告。

报告必须基于同一 `auditId` 中的真实工具结果和 RCA，不得生成无证据的系统状态。报告生成成功时写入 `reportId`；生成失败时允许 `reportId=null` 并记录明确错误。若工具编排原本成功则降级为 `PARTIAL_SUCCESS`；若原本已失败则保持 `FAILED`。这里的强制要求是“必须尝试且可追踪失败”，不是在持久化故障下伪造报告。

通知策略：

- `ON_ABNORMAL`：`abnormal=true`、`PARTIAL_SUCCESS` 或 `FAILED` 时通知。
- `ALWAYS`：每次非 `SKIPPED` 执行都通知。

通知发送给所有已启用且支持巡检事件的全局 Webhook / 飞书通道。通知包含计划名、模板、执行状态、结论、健康评分或异常摘要、`reportId` 和 `auditId`。

通知基础设施需增加能表达“巡检完成”和“巡检异常/失败”的事件类型，具体枚举数量在实施计划中以最小改动确定。通知异步失败不反向修改巡检状态，由 `NotificationRecord` 独立记录。

## 9. 一致性与失败处理

- 保存计划时完整校验模板参数、阈值、周期和时区；触发时再次校验快照。
- 先原子申请执行权，再创建或转换为 `RUNNING`。
- 审计创建失败时不调用 OS 工具，执行直接失败且 `auditId=null`。这是一条触发失败记录，不是已开始的巡检；必须记录高优先级应用日志，不能绕过审计继续执行。
- 工具执行后审计更新失败时执行标记为 `FAILED`，不得伪装成功。
- 报告生成失败时 `reportId=null`；原状态为 `SUCCESS` 时改为 `PARTIAL_SUCCESS`，原状态为 `FAILED` 时保持不变；保留工具与审计结果并发送失败通知。
- 通知失败不改变执行状态。
- 计划编辑使用乐观锁，版本冲突返回 HTTP 409。
- API 只返回摘要以及报告、审计引用，不复制完整敏感工具输出。

## 10. API

所有接口要求现有管理员会话认证，写接口沿用 CSRF 防护和统一 `ApiResponse`。

```text
GET    /api/inspections/templates
GET    /api/inspections/plans
POST   /api/inspections/plans
GET    /api/inspections/plans/{planId}
PUT    /api/inspections/plans/{planId}
POST   /api/inspections/plans/{planId}/enable
POST   /api/inspections/plans/{planId}/disable
DELETE /api/inspections/plans/{planId}
POST   /api/inspections/plans/{planId}/run
GET    /api/inspections/executions
GET    /api/inspections/executions/{executionId}
```

`POST .../{planId}/run` 异步触发并立即返回 `executionId`。执行列表支持按计划、状态、模板、触发类型和时间范围筛选。

本期只有单管理员，不新增普通用户接口或角色模型。服务层仍须集中保护写操作边界，便于后续引入 VIEWER 角色。

## 11. 前端

新增一级导航页面“定时巡检”，包含两个标签：

### 11.1 巡检计划

展示名称、模板、周期、通知策略、启停状态、上次执行、下次执行和最近结果。支持新建、编辑、启停、删除和立即执行。

表单字段：

- 公共：名称、说明、周期、时间、时区、通知策略。
- HEALTH：服务名、CPU、内存、磁盘阈值。
- DISK：扫描路径、可选日志服务名、大文件阈值、磁盘阈值。
- SERVICE：服务名、可选预期端口。

新建计划默认停用。删除前明确提示历史执行、报告和审计不会删除。

### 11.2 执行记录

展示计划快照、模板、触发方式、状态、异常标记、开始时间、耗时和摘要。支持筛选，并跳转到现有 ReportCenter 和 AuditLog 详情。

立即执行后页面按 `executionId` 轮询状态，不阻塞 HTTP 请求。MVP 不新增独立巡检报告页，也不增加通知配置页。

## 12. 测试与验收

### 12.1 后端

- 计划 CRUD、认证、乐观锁、周期和时区计算。
- 三类模板参数与阈值边界。
- 服务 allowlist、RiskCheck canonical JSON 序列化和固定日志错误模式。
- 到期触发、停用不触发、立即执行不改变下次时间。
- 重入产生 `SKIPPED`，且无报告和通知。
- 定时与手动执行主体正确写入审计。
- 未注册、`PermissionType` 非 `READ`、L2+ 或 RiskCheck 非 ALLOW 的工具在执行前被拒绝。
- 三模板工具规划、RCA、关键工具和异常判定。
- `SUCCESS + abnormal=true` 与 `PARTIAL_SUCCESS` 语义正确。
- 报告失败降级、通知失败隔离、遗留 `RUNNING` 恢复。
- LLM 禁用时完整执行。

### 12.2 前端

- 计划列表和模板动态表单。
- 阈值、时区和周期校验。
- 启停、删除确认、立即执行和状态轮询。
- 执行记录筛选及报告、审计跳转。
- 中文错误提示、空状态和窄屏主要操作。

### 12.3 端到端验收

1. 创建每日 HEALTH 计划并立即执行，产生执行记录、审计和报告。
2. 将磁盘阈值设为低于当前用量，执行状态为 `SUCCESS`、`abnormal=true` 并触发通知。
3. SERVICE 发现 nginx 异常时只给建议，不创建 `PendingAction`。
4. 上次执行未结束时再次触发，产生 `SKIPPED`，无报告和通知。
5. LLM 关闭时三类模板仍能执行。
6. 任意巡检均不存在原始 shell、自动确认或未审计工具调用。

性能目标：

- HEALTH ≤30 秒。
- DISK、SERVICE ≤10 秒，且受现有单工具超时约束。
- 计划查询接口 P95 ≤500ms。
- 正常运行时调度触发偏差 ≤5 秒。

## 13. 规格与路线图更新

该能力超出根目录 v0.1 竞赛 MVP 的原始 P2 范围，但符合当前 `functional-defect-and-roadmap.md` 中 D-08 的 P1 商业迭代方向。实现时应同步更新：

- `docs/product/functional-defect-and-roadmap.md` 的 D-08 状态与交付说明。
- 产品需求、设计、说明书、功能测试和部署文档中的页面、API、数据表与运行参数。

不得修改九条安全硬规则，也不得以“定时执行”为由绕过 RiskCheck、审计或 L2 确认机制。
