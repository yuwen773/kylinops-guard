# KylinOps Guard 生产化与 LLM 增强设计

## 1. 文档信息

- 日期：2026-06-13
- 状态：已批准
- 目标周期：2-3 周
- 目标：在不削弱现有安全闭环的前提下，完成生产安全加固、真实 LLM 接入、H2/PostgreSQL 双数据库支持和 LoongArch 真机验收。

## 2. 背景与现状

项目已经完成比赛 P0 演示闭环：

```text
自然语言输入
-> Prompt 注入检测
-> 意图识别
-> Tool 规划
-> RiskCheck
-> ToolExecutor / SafeExecutor
-> 审计
-> 报告
```

当前主要缺口：

1. LLM 只有配置占位，意图识别、参数提取和回复生成仍为规则或模板。
2. API 没有认证，审计、报告和 L2 确认接口对匿名访问开放。
3. `OsCommandExecutor` 顺序读取 stdout/stderr，超时可能失效，并存在管道阻塞风险。
4. H2 Console、固定数据库口令、宽松 CORS 和 Mock Tool 不适合生产。
5. 生产数据库迁移和 Schema 版本管理尚未建立。
6. Kylin V11 / LoongArch64 仍需要持续真机验证和验收证据。

## 3. 已确认范围

### 3.1 本期包含

- 单管理员账号认证。
- DeepSeek 和阿里云百炼 Qwen 的 OpenAI-Compatible 配置切换。
- LLM 用于歧义意图/参数增强和真实 ToolResult 的中文总结。
- H2 和 PostgreSQL 双数据库支持。
- Flyway Schema 迁移。
- 命令执行超时和输出读取可靠性修复。
- 生产 profile、CORS、异常输出、健康检查和 Mock Tool 隔离。
- Kylin V11 / LoongArch64 持续部署及端到端验收。

### 3.2 本期不包含

- 多用户和完整 RBAC。
- LLM 自主选择并执行工具。
- LLM 安全决策或自动确认 L2。
- 自动跨供应商重试。
- 真实文件删除、日志截断等高风险写操作。
- 分布式任务队列或大型监控平台。

真实写操作继续仅允许白名单服务重启；其他写操作保持 preview。

## 4. 设计原则

1. 现有九条安全红线保持不变。
2. LLM 是可选增强，不是安全闭环依赖。
3. 工具和执行计划必须由服务端可信代码约束。
4. 失败默认降级或阻断，不能绕过 RiskCheck。
5. 认证、LLM、编排、执行和持久化使用清晰接口隔离。
6. 生产配置默认收紧，开发便利只存在于 dev/test profile。
7. 所有关键路径必须可自动化测试，并在 LoongArch 真机复验。

## 5. 目标架构

```text
Frontend
  |
  +-- POST /api/auth/login
  +-- authenticated /api/**
          |
     AgentOrchestrator
          |
  +-------+--------------------+
  |                            |
HybridIntentService      ToolPlanningService
  |                            |
规则识别 + LLM 增强         固定可信工具映射
                               |
                        RiskCheckService
                               |
                    ToolExecutor / SafeExecutor
                               |
                      ToolResult + AuditLog
                               |
                    HybridResponseService
                    模板兜底 + LLM 总结
```

### 5.1 新增模块

#### `auth`

- 管理员凭据校验。
- 服务端登录会话。
- Cookie、CSRF、登录限流和接口保护。
- PendingAction 创建者与确认者归属校验。

#### `llm`

- `LlmClient` 供应商无关接口。
- OpenAI-Compatible HTTP 实现。
- 结构化请求/响应 DTO。
- 超时、错误分类、脱敏日志和降级状态。

#### `agent/intelligence`

- `HybridIntentService`：规则结果和 LLM 增强结果合并。
- `HybridResponseService`：模板兜底和 LLM 总结。
- 严格的模型输入裁剪、输出 Schema 校验和事实边界。

#### `migration`

- Flyway 迁移脚本。
- H2 PostgreSQL Mode 与 PostgreSQL 公共 SQL。
- Schema 版本升级和启动校验。

#### `runtime`

- 可靠的命令执行和有界并发。
- liveness/readiness。
- dev/test/prod 配置和结构化运行日志。

### 5.2 保持安全权威的现有模块

- `ToolPlanningService`：最终工具计划。
- `ToolRegistry`：可调用工具集合。
- `RiskCheckService`：ALLOW、CONFIRM、BLOCK。
- `SafeExecutor`：受控动作白名单。
- `AuditLogService`：请求全链路记录。

`AgentOrchestrator` 只负责编排接口，不承载模型供应商、认证或数据库细节。

## 6. LLM 设计

### 6.1 职责边界

LLM 可以：

- 增强 UNKNOWN 或歧义输入的意图判断。
- 提取受限参数，如服务名、操作类型和 PID。
- 基于本次真实 ToolResult 生成中文总结。

LLM 不可以：

- 构造或执行系统命令。
- 增加未注册工具。
- 修改工具风险等级。
- 降低 RiskCheck 决策。
- 自动确认 L2。
- 补充工具未观察到的系统事实。

### 6.2 意图处理流程

1. Prompt 注入检测最先执行；命中时直接进入现有阻断流程。
2. 现有规则分类器识别明确意图。
3. UNKNOWN、歧义输入或需要参数增强的输入调用 LLM。
4. LLM 仅输出结构化 JSON：

```json
{
  "intent": "SERVICE_DIAGNOSIS",
  "confidence": 0.91,
  "parameters": {
    "serviceName": "nginx",
    "operation": "status"
  }
}
```

5. 服务端验证意图枚举、置信度、参数 Schema 和字段白名单。
6. 无效 JSON、低置信度或非法参数回退规则结果。
7. `ToolPlanningService` 根据受信结果生成固定计划。

首期建议最低置信度为可配置值，默认 `0.75`。明确的危险命令和 Prompt 注入结果不能被 LLM 覆盖。

### 6.3 回复生成流程

1. 工具执行和风险决策完成。
2. 将经过裁剪和脱敏的 ToolResult、风险结论及失败状态提交给 LLM。
3. 提示词明确要求仅总结所给事实。
4. LLM 失败时使用现有 `AgentResponseBuilder` 模板。
5. API 仍返回结构化 toolCalls、riskDecision 和 auditId，不能仅依赖自然语言答复。

### 6.4 供应商与配置

DeepSeek 和 Qwen 共用 OpenAI-Compatible 协议，通过配置切换：

```yaml
kylinops:
  llm:
    enabled: ${LLM_ENABLED:false}
    base-url: ${LLM_BASE_URL:}
    api-key: ${LLM_API_KEY:}
    model: ${LLM_MODEL:}
    intent-timeout-ms: ${LLM_INTENT_TIMEOUT_MS:3000}
    response-timeout-ms: ${LLM_RESPONSE_TIMEOUT_MS:5000}
    confidence-threshold: ${LLM_CONFIDENCE_THRESHOLD:0.75}
```

首期不自动跨供应商重试，避免延迟、重复计费和不确定行为。供应商切换由部署配置完成。

### 6.5 降级和审计

- `enabled=false`：全程使用规则和模板。
- 超时、429、5xx、网络异常或无效 JSON：本次调用立即降级。
- LLM 故障不得改变 RiskCheck 和工具执行结果。
- 审计记录调用阶段、模型、耗时、结果状态和降级原因。
- 不记录 API Key、隐藏推理过程或未脱敏的完整工具输出。

## 7. 认证与接口安全

### 7.1 单管理员认证

- 用户名和密码哈希通过环境变量或受保护配置提供。
- `POST /api/auth/login` 创建服务端会话。
- `POST /api/auth/logout` 销毁会话。
- 浏览器使用 `HttpOnly`、`Secure`（生产）、`SameSite=Strict` Cookie。
- `/api/health/live` 和登录接口允许匿名访问。
- 其余 `/api/**` 默认要求认证。

不引入用户注册、密码找回和角色管理。

### 7.2 PendingAction 归属

- PendingAction 保存创建管理员标识和创建会话标识。
- 确认时同时校验当前管理员、会话和动作状态。
- 仅凭 `actionId` 不能确认其他会话创建的动作。
- 确认后继续执行现有二次 RiskCheck。

### 7.3 Web 防护

- 状态变更接口启用 CSRF 防护。
- 开发 profile 的 CORS 仅允许 `localhost:5173` 和 `127.0.0.1:5173`。
- 生产通过 Nginx 同源访问，默认不开放跨域。
- 登录和 Chat API 设置基础速率限制。
- 连续登录失败触发短时锁定。
- 错误响应使用稳定错误码和 traceId，不返回内部异常消息。

## 8. 命令执行可靠性

`OsCommandExecutor` 需要满足：

1. stdout 和 stderr 由独立任务并发消费。
2. 超时从进程启动时开始计算，不能在流读取后才开始等待。
3. 超时后终止进程及可识别的子进程。
4. stdout 和 stderr 分别限制最大行数和最大字节数。
5. 截断后继续消费底层流，避免子进程阻塞。
6. 返回退出码、超时、截断、耗时和脱敏错误信息。
7. 使用有界线程池，拒绝或降级过载请求。
8. 应用关闭时正确停止执行线程和残留进程。

必须增加以下自动化测试：

- 持续输出 stdout 的超时进程。
- 持续输出 stderr 的进程。
- stdout/stderr 同时大量输出。
- 输出截断后进程仍可退出。
- 超时后进程不残留。
- 并发达到上限时行为可预测。

## 9. 数据库与迁移

### 9.1 Profile

- `dev`：H2 File Mode，H2 Console 可选开启。
- `test`：H2 Memory，每次测试运行 Flyway。
- `prod`：PostgreSQL，连接信息来自环境变量。

所有环境使用 `ddl-auto: validate`；禁止生产使用 `update`。

### 9.2 Flyway

- 初始迁移覆盖当前 JPA 实体表和索引。
- 后续 Schema 变更只通过版本化迁移。
- SQL 使用 H2 PostgreSQL Mode 和 PostgreSQL 都支持的公共语法。
- CI 分别验证 H2 迁移和 PostgreSQL 迁移。
- 现有 H2 文件数据提供一次性导出/导入说明，应用启动时不自动搬迁。

## 10. 健康检查与可观测性

### 10.1 健康端点

- `/api/health/live`：进程存活，不检查外部依赖。
- `/api/health/ready`：数据库、风险规则和核心 Bean 已就绪。
- 详细诊断可显示关键 Linux 命令可用性，但命令缺失不直接判定应用死亡。

### 10.2 日志和指标

日志上下文包含：

- `traceId`
- `auditId`
- `sessionId`
- `llmProvider`

记录以下轻量指标：

- LLM 调用耗时和降级次数。
- 工具调用耗时和超时数。
- 登录失败和锁定次数。
- 数据库迁移版本。

不在本期引入大型监控平台。

## 11. 部署设计

- Nginx 托管前端并反向代理 `/api`。
- 后端默认仅监听 `127.0.0.1:8080`。
- Nginx 提供 TLS。
- systemd 管理后端进程、重启策略和环境变量文件。
- API Key、管理员密码哈希和数据库凭据不进入 Git。
- MockTool 和 FailingMockTool 仅在 dev/test profile 注册。
- H2 Console 仅在 dev profile 开启。

LoongArch 目标机持续验证：

1. JDK、PostgreSQL、Nginx 和 systemd 环境。
2. Flyway 首次迁移和重复启动。
3. 登录、退出和匿名访问阻断。
4. 四个核心演示场景。
5. DeepSeek、Qwen 和 LLM 故障降级。
6. 白名单服务重启及 L2 二次校验。
7. 审计、报告和重启恢复。
8. 数据库备份恢复和有限并发测试。

## 12. 前端变化

- 增加管理员登录页。
- 路由进入业务页面前检查登录状态。
- API Client 统一携带 Cookie 和 CSRF Token。
- 收到 401 时清理本地状态并跳转登录页。
- AppLayout 增加退出入口和当前登录状态。
- 保留现有六个业务页面及四个演示流程。
- 不新增用户管理和角色管理页面。

## 13. 测试策略

### 13.1 单元与契约测试

- LLM Client 请求格式、错误映射和超时。
- DeepSeek/Qwen 共用 OpenAI-Compatible 契约测试。
- LLM 结构化输出校验和非法参数拒绝。
- HybridIntentService 的规则优先、增强和降级。
- HybridResponseService 的事实约束和模板回退。
- 密码校验、锁定、Cookie 和 CSRF。
- PendingAction 跨会话确认阻断。
- 命令并发流读取和超时。

### 13.2 集成测试

- 匿名业务 API 返回 401。
- 登录后原有 API 可用。
- Prompt 注入在 LLM 前阻断。
- LLM 不可用时四个核心场景仍可运行。
- H2 与 PostgreSQL 均可完成迁移和 Repository 测试。
- 应用异常不向客户端泄露内部信息。

### 13.3 E2E 与真机验收

- 登录、六页面导航和退出。
- 健康巡检、磁盘诊断、L2 服务重启和危险命令阻断。
- 两个模型供应商分别至少完成一次真实请求。
- 模型断网或错误 Key 时完成降级场景。
- LoongArch 上执行部署、重启和数据库恢复。

## 14. 实施分期

### 阶段 1：可靠性与生产基线，3-4 天

- 修复命令执行器。
- 拆分 dev/test/prod 配置。
- 收紧 CORS 和异常输出。
- 隔离 Mock Tool。
- 引入 Flyway 和 H2/PostgreSQL 迁移验证。

### 阶段 2：单管理员认证，3-4 天

- 登录、退出、服务端会话、Cookie、CSRF 和限流。
- 全业务 API 保护。
- PendingAction 归属校验。
- 前端登录流程和 401 处理。

### 阶段 3：LLM 双供应商接入，4-5 天

- OpenAI-Compatible LlmClient。
- DeepSeek/Qwen 配置切换。
- 混合意图和参数增强。
- 基于 ToolResult 的总结。
- 超时、限流、无效输出和服务故障降级。

### 阶段 4：LoongArch 验收与收尾，3-4 天

- systemd、Nginx、PostgreSQL 和密钥配置。
- 真机核心场景、恢复和有限并发测试。
- 回填部署、功能、性能和安全验收证据。

## 15. 完成标准

1. 九条安全红线全部保持。
2. 匿名用户无法读取审计、报告或执行运维功能。
3. 跨会话不能确认 PendingAction。
4. DeepSeek/Qwen 均可通过配置接入。
5. LLM 关闭或故障时四个核心场景仍可运行。
6. 模型不能改变工具计划、安全等级或确认状态。
7. 命令超时和 stdout/stderr 大输出测试通过，无残留进程。
8. H2/PostgreSQL 均通过迁移和核心集成测试。
9. 生产关闭 H2 Console、Mock Tool 和宽松 CORS。
10. LoongArch 真机完成可复现部署与端到端验收。
11. 原有后端、前端和 Playwright 基线无回归。

## 16. 风险与缓解

| 风险 | 缓解措施 |
| --- | --- |
| 模型输出不稳定 | 严格 JSON Schema、置信度阈值、参数白名单、规则回退 |
| LLM 增加延迟 | 3s/5s 分阶段超时，无无限重试 |
| 接入认证破坏现有 E2E | 提供测试登录 fixture，逐步迁移 API 测试 |
| H2/PostgreSQL SQL 差异 | 公共 SQL、双数据库 CI |
| 命令子进程难以清理 | 使用 ProcessHandle 后代进程清理并在 Linux 真机测试 |
| LoongArch 环境差异 | 持续目标机部署，不在开发机结果上推断通过 |
| 周期超出 3 周 | 不扩展 RBAC、真实删除、自动供应商重试和大型监控 |

## 17. 决策记录

- 采用模块化分层增强，不在 `AgentOrchestrator` 内直接堆叠实现。
- 不采用 Spring AI，首期使用轻量 OpenAI-Compatible Client。
- 认证采用单管理员服务端会话，不做完整 RBAC。
- LLM 只承担意图/参数增强和结果总结。
- H2 用于开发和测试，PostgreSQL 用于生产。
- 高风险写操作保持 preview，仅白名单服务重启可真实执行。
