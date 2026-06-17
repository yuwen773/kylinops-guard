# P1-01 Plan 01：NotificationCenter 骨架

> **状态**：⏳ 待实施（从 spec 批准后开始）
> **版本**：v0.1
> **日期**：2026-06-17
> **来源 Spec**：[2026-06-17-p1-01-notification-center-design.md](../specs/2026-06-17-p1-01-notification-center-design.md)
> **前置依赖**：P0 冲刺全部合入（后端 550+1skipped · 前端 190 · E2E 19+3skipped）；Fix-03 合入（构造器兼容模式）
> **后置触发**：Plan 01 合入 + tag `p1-01-foundation-done` 后启动 Plan 02

---

## 概述

实现 P1-01 告警通道 Phase 1：全部通道层 + 调度层 + 3 个安全类事件触发点（L4_BLOCK / PROMPT_INJECTION_BLOCK / L2_CONFIRM_REQUIRED）。

**架构要点**（spec 已批准的核心设计决策）：
- NotificationChannel 是**通道类型处理器**（不是通道实例），按 ChannelType 注册
- ChannelConfig 才是具体通道实例，Dispatcher 遍历 config.channels 逐条处理
- NotificationEventFactory 统一构造事件，AgentOrchestrator 不直接拼 payload
- dryRun=true → 按配置通道写 SKIPPED record（channelId=channelConfig.id）
- 队列满 → 只 log error，不写 FAILED record
- emit() 同步部分不做 DB 写入、不发 HTTP 请求
- 唯一约束冲突 → DataIntegrityViolationException → log warn，跳过该 channel

---

## 任务拆分

### Task 0：环境与依赖检查

**目的**：确定 HTTP 客户端选型，锁定 mock 方案，确认项目现有依赖不冲突。

**检查清单**：
- □ 项目现有 HTTP 依赖：Spring RestTemplate / OkHttp / Java HttpClient？
- □ 选型标准：与本项目已有依赖一致 + 可 mock
- □ mock 方案：MockWebServer（OkHttp） / MockRestServiceServer（RestTemplate） / WireMock — 随客户端同步锁定
- □ 确认 H2 File Mode 能自动建表（`@Entity` + `@Table` + `uniqueConstraints`）
- □ 确认 `application-test.yml` 无 `notification.enabled=true` 残留
- □ 确认 `application-demo.yml` 无硬编码 URL / secret
- □ 确认 `@EnableAsync` 已配置

**产出**：
- □ 依赖检查记录写入 Task 0 commit message 或注释
- □ HTTP 客户端选型确定后修改 design doc 中相关描述（如果与 spec 假设不同）

---

### Task 1：数据模型 — 枚举 + Event DTO

**创建文件**（新包 `com.kylinops.notification`）：

| 文件 | 说明 |
|---|---|
| `NotificationEventType.java` | 枚举：L4_BLOCK / PROMPT_INJECTION_BLOCK / L2_CONFIRM_REQUIRED / SERVICE_ABNORMAL / DISK_RISK |
| `NotificationSeverity.java` | 枚举：CRITICAL / WARNING / INFO |
| `NotificationStatus.java` | 枚举：PENDING / SENT / FAILED / SKIPPED（无 RETRYING） |
| `ChannelType.java` | 枚举：WEBHOOK / FEISHU |
| `NotificationEvent.java` | 可变 DTO（@Data @Builder），强类型字段，不用 Map context |

**设计约束**：
- NotificationEvent 是可变 DTO（@Data 生成的 setter 仅供 Service 内部使用）
- 强类型字段：matchedRuleId / serviceName / diskPath / diskUsagePercent / promptInjectionPattern / rcaConfidence
- eventId 由 Service 在 emit() 入口补 UUID
- occurredAt 由 Clock 注入生成

**测试**：`NotificationEventTest` — builder / 字段校验 / 强类型 context

---

### Task 2：NotificationRecord JPA 实体 + Repository

**创建文件**：

| 文件 | 说明 |
|---|---|
| `NotificationRecord.java` | @Entity，`@Table(name="notification_records")` |
| `NotificationRecordRepository.java` | JPA Repository |
| `NotificationRecordSummary.java` | DTO（API 出口，不含 requestPayload / responseBody） |

**关键约束**：
- `UNIQUE(event_id, channel_id)` — 联合唯一约束
- 索引：`audit_id`、`created_at`
- `channel_id` 来自 `channelConfig.id`（不是 channel handler 的 channelId()）
- `channel_type` = `channelConfig.type`（nullable=false）
- `requestPayload` @Lob TEXT — 已脱敏的 payload JSON
- `responseBody` @Lob TEXT — 截断到 1KB
- `retryCount` 默认 0（P1-02 启用）
- `createdAt` 通过 Clock 注入生成

**NotificationRecordSummary**（API 出口）：
- 不包含 requestPayload / responseBody（安全约束）
- errorMessage 已脱敏后才返回

**测试**：`NotificationRecordRepositoryTest` — CRUD / 唯一约束 / 索引

---

### Task 3：配置模型 + 多 profile yml

**创建 / 修改文件**：

| 文件 | 操作 |
|---|---|
| `NotificationConfig.java` | 新建 — @ConfigurationProperties("kylinops.notification") |
| `application.yml` | 追加 — 默认 enabled=false, dry-run=false, channels=[] |
| `application-demo.yml` | 追加 — enabled=true, channels 含 webhook-demo + feishu-demo, URL/secret 走 env |
| `application-test.yml` | 追加 — enabled=false, channels=[]（强制关闭） |

**NotificationConfig 结构**：
```java
@Data
@ConfigurationProperties(prefix = "kylinops.notification")
public class NotificationConfig {
    private boolean enabled = false;
    private boolean dryRun = false;
    private List<ChannelConfig> channels = List.of();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ChannelConfig {
        private String id;          // 通道实例 ID（全局唯一）
        private ChannelType type;   // WEBHOOK / FEISHU
        private boolean enabled = true;
        private String url;         // 空字符串视为未配置
        private String secret;      // WEBHOOK 可选，FEISHU 必填
        private Integer timeoutMs;  // 默认 3000
    }
}
```

**关键不变量**：
- `application.yml` 默认 enabled=false
- `application-demo.yml` 显式 enabled=true（演示场景）
- `application-test.yml` 显式 enabled=false（测试环境禁外网）
- 通道 URL / secret 全部走 env 变量，**禁止**硬编码到 yml
- 需注册 `@EnableConfigurationProperties(NotificationConfig.class)`

---

### Task 4：NotificationChannel 接口 + NotificationChannelRegistry

**创建文件**：

| 文件 | 说明 |
|---|---|
| `NotificationChannel.java` | 接口（通道类型处理器） |
| `NotificationChannelRegistry.java` | 注册中心（按 ChannelType 索引，fail-open） |

**NotificationChannel 接口**：
```java
public interface NotificationChannel {
    ChannelType type();
    default boolean supports(NotificationEvent event, NotificationConfig.ChannelConfig channelConfig) { return true; }
    NotificationSendResult send(NotificationEvent event, String maskedPayload, NotificationConfig.ChannelConfig channelConfig);
}
```

**关键设计**：
- 实现类必须是 `@Component`，自动被 Spring 收集到 `List<NotificationChannel>`
- WebhookChannel bean 处理所有 type=WEBHOOK 的配置实例
- FeishuChannel bean 处理所有 type=FEISHU 的配置实例
- **没有 `channelId()` 方法** — 通道实例由 ChannelConfig 标识

**NotificationChannelRegistry** 设计（伪代码）：
```java
@Component
public class NotificationChannelRegistry {
    private final Map<ChannelType, NotificationChannel> handlerMap = new LinkedHashMap<>();

    @PostConstruct
    public void init() {
        // 遍历 channels，按 type 注册；重复 type → log warn 保留第一个
    }

    public Optional<NotificationChannel> resolveHandler(ChannelType type) {
        return Optional.ofNullable(handlerMap.get(type));
    }

    public boolean hasAnyHandler() { ... }
}
```

**启动策略：fail-open**
- 有 handler → log info
- 无 handler（notification.enabled=false 或未配置任何 handler bean）→ log warn，**不抛异常**
- 重复 type → log warn 保留第一个

**测试**：`NotificationChannelRegistryTest` — fail-open / 无 handler 不抛 / 重复 type warn / resolveHandler 按 type 匹配

---

### Task 5：NotificationEventFactory

**创建文件**：

| 文件 | 说明 |
|---|---|
| `NotificationEventFactory.java` | @Component，统一构造事件 |

**接口**：
```java
@Component
public class NotificationEventFactory {
    public NotificationEvent l4Block(String auditId, String sessionId, RiskLevel riskLevel, RiskDecision riskDecision, String matchedRuleId, String riskReason) { ... }
    public NotificationEvent promptInjectionBlock(String auditId, String sessionId, String promptInjectionPattern, String reason) { ... }
    public NotificationEvent l2ConfirmRequired(String auditId, String sessionId, IntentType intentType, RiskLevel riskLevel, String actionId, String summary) { ... }
    public NotificationEvent serviceAbnormal(String auditId, String sessionId, String serviceName, double rcaConfidence, String conclusion) { ... }
    public NotificationEvent diskRisk(String auditId, String sessionId, String diskPath, Double diskUsagePercent, double rcaConfidence, String conclusion) { ... }
}
```

**设计约束**：
- 每个工厂方法负责填充 title / summary / detail / severity / 强类型字段
- AgentOrchestrator **只调工厂方法**，不直接 `new NotificationEvent.builder()`
- 后续调整模板、标题、摘要时，只需要改 Factory，不需要改 AgentOrchestrator

---

### Task 6：WebhookChannel + FeishuChannel

**创建文件**：

| 文件 | 说明 |
|---|---|
| `channel/WebhookChannel.java` | @Component，type()=WEBHOOK |
| `channel/FeishuChannel.java` | @Component，type()=FEISHU |
| `NotificationSendResult.java` | DTO：success / responseCode / responseBody / errorMessage |
| `exception/NotificationSendException.java` | 内部异常（不外抛） |

**WebhookChannel**：
- POST JSON 到 channelConfig.url
- 3s 超时（channelConfig.timeoutMs 可覆盖）
- 若 channelConfig.secret 非空 → 添加 `X-KylinOps-Signature: HMAC-SHA256` 请求头
- 幂等设计：不依赖 channelId，所有实例信息来自 channelConfig 参数

**FeishuChannel**：
- 飞书机器人 webhook 格式
- timestamp + sign 签名（飞书官方算法）
- payload 使用 markdown 类型
- channelConfig.secret 必填（用于签名计算）

**所有异常抛回 dispatcher 统一处理**，不在实现类内 catch。

**测试**：
- `WebhookChannelTest` — HMAC 签名 / 超时 / 4xx/5xx / 异常
- `FeishuChannelTest` — timestamp+sign 算法 / markdown payload
- 使用 Task 0 锁定的 HTTP mock 方案

---

### Task 7：NotificationPayloadSanitizer

**创建文件**：

| 文件 | 说明 |
|---|---|
| `NotificationPayloadSanitizer.java` | @Component，payload 脱敏 |

**脱敏规则**：
- key 含 secret/token/password/api_key/access_key/private_key → 替换为 "***"
- value 含 /etc/passwd / /etc/shadow / ~/.ssh/ / /var/lib/mysql/ → 替换为 "/etc/[REDACTED]" 等
- userInput 原文（风险命令） → 仅保留前 50 字符 + "..."

**测试**：`NotificationPayloadSanitizerTest` — 纯单元测试，无需启动 Spring Context

---

### Task 8：NotificationAsyncConfig

**创建文件**：

| 文件 | 说明 |
|---|---|
| `NotificationAsyncConfig.java` | @Bean notificationExecutor（ThreadPoolTaskExecutor） |

**配置**：
```java
@Bean(name = "notificationExecutor")
public Executor notificationExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(2);
    executor.setMaxPoolSize(5);
    executor.setQueueCapacity(100);
    executor.setThreadNamePrefix("notification-");
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
    // AbortPolicy：队列满时抛 RejectedExecutionException
    // → NotificationService 捕获后只 log error，不写 record
    executor.initialize();
    return executor;
}
```

**关键**：
- AbortPolicy（禁止 CallerRunsPolicy — 会让主流程被通知拖慢）
- 队列满不写 FAILED record（只 log error）
- 不在主线程执行任何通知相关操作

---

### Task 9：NotificationService + NotificationDispatcher

**创建文件**：

| 文件 | 说明 |
|---|---|
| `NotificationService.java` | 入口 API，AgentOrchestrator 注入点 |
| `NotificationDispatcher.java` | 调度器，异步执行 |

**NotificationService.emit(event) 同步部分职责**（不做 DB 写入、不发 HTTP 请求）：
1. enabled=false → 直接 return（不写 record）
2. 补 eventId / occurredAt
3. 调 `dispatcher.dispatchAsync(event)`

```java
public void emit(NotificationEvent event) {
    try {
        if (!config.isEnabled()) { log.debug(...); return; }
        if (event.getEventId() == null) event.setEventId(UUID.randomUUID().toString());
        if (event.getOccurredAt() == null) event.setOccurredAt(LocalDateTime.now(clock));
        dispatcher.dispatchAsync(event);
    } catch (Exception e) {
        log.error(...); // 永不冒泡
    }
}
```

**NotificationDispatcher.dispatchAsync**（在 notificationExecutor 线程池执行）：

```java
// @Async("notificationExecutor")
public void dispatchAsync(NotificationEvent event) {
    try { doDispatch(event); }
    catch (Exception e) { log.error(...); }
}

private void doDispatch(NotificationEvent event) {
    // 1. 遍历 config.channels，过滤 enabled + url 非空
    // 2. 无有效通道 → log debug，return
    // 3. sanitizer.mask(event) → maskedPayload
    // 4. for each channelConfig:
    //    a. registry.resolveHandler(type) — 找不到 → log warn，跳过
    //    b. handler.supports(event, channelConfig) — false → 跳过
    //    c. config.isDryRun() → writeSkippedRecord()，跳过（不发送）
    //    d. createPendingRecord() — DataIntegrityViolationException → log warn，跳过
    //    e. handler.send(event, maskedPayload, channelConfig)
    //    f. 更新 record status=SENT / FAILED
}
```

**测试**：
- `NotificationServiceTest` — enabled=false 不调 dispatcher / enabled=true 补 eventId+occurredAt / 异常不抛
- `NotificationDispatcherTest` — 通道选择 / dryRun 按通道写 SKIPPED / 发送失败写 FAILED / 唯一约束冲突 / 无 handler 跳过

---

### Task 10：AgentOrchestrator 集成（3 个 emit 点）

**修改文件**：

| 文件 | 操作 |
|---|---|
| `AgentOrchestrator.java` | 新增构造器委托（Fix-03 模式）+ 3 个 emit 调用 |

**注入方式**（Fix-03 教训 — 不得用 @RequiredArgsConstructor 替换现有显式构造器）：
```java
// 新增字段
private final NotificationService notificationService;

// 新增构造器 + 老构造器委托
public AgentOrchestrator(
        ...原有参数...,
        NotificationService notificationService) {
    this(...原有参数...);
    this.notificationService = notificationService;
}
```

**3 个 emit 触发点**：

| 触发点 | 位置 | 条件 |
|---|---|---|
| PROMPT_INJECTION_BLOCK | `handleInjectionBlock()` 出口 | injection.isInjectionDetected() == true |
| L4_BLOCK | `process()` Step 7 `case BLOCK` | decision == BLOCK && riskLevel == L4 |
| L2_CONFIRM_REQUIRED | `process()` Step 7 `case CONFIRM` | decision == CONFIRM |

**关键不变量**：
- 通过 `notificationEventFactory.l4Block(...)` 创建事件，不直接拼 NotificationEvent
- emit() 调用在 process() 的 try 块内，异常被外层 catch
- handleInjectionBlock() 是独立 try/finally，emit() 在 finally 块之前
- **不修改** RiskRuleEngine / PromptInjectionDetector（P0 安全基线）

**测试**：
- `L4BlockNotificationIntegrationTest` — L4 触发 / payload 内容
- `PromptInjectionNotificationTest` — handleInjectionBlock 路径
- `L2ConfirmNotificationTest` — L2 CONFIRM 触发 / 不 auto-confirm
- `NotificationEnabledDisabledTest` — enabled=false 不发 / dryRun 写 SKIPPED

---

### Task 11：AuditLog 联查 + Detail 关联

**修改文件**：

| 文件 | 操作 |
|---|---|
| `AuditLogService.java` | `toDetail()` 联查 notificationRecords |
| `AuditLogDetail.java` | 新增 `List<NotificationRecordSummary> notificationRecords` 字段 |

**关键约束**：
- 不修改 AuditLog entity（仅在 DTO 加字段）
- `NotificationRecordSummary` 不包含 requestPayload（安全约束）
- `AuditLogDetail.notificationRecords` 始终返回（即使为空列表）

**测试**：`AuditLogNotificationDetailTest` — toDetail 联查 / 不含 requestPayload

---

### Task 12：Phase 1 测试完善

**测试类汇总**（10–11 个测试类）：

| # | 测试类 | 层 |
|---|---|---|
| 1 | `NotificationEventTest` | 单元 |
| 2 | `NotificationPayloadSanitizerTest` | 单元 |
| 3 | `WebhookChannelTest` | 单元（HTTP mock） |
| 4 | `FeishuChannelTest` | 单元（HTTP mock） |
| 5 | `NotificationChannelRegistryTest` | 集成（@SpringBootTest） |
| 6 | `NotificationDispatcherTest` | 集成（mock channel + fake executor） |
| 7 | `NotificationServiceTest` | 集成（mock dispatcher，**不 mock JPA**） |
| 8 | `NotificationRecordRepositoryTest` | 数据（@DataJpaTest + H2） |
| 9 | `L4BlockNotificationIntegrationTest` | 集成（@SpringBootTest + mock channel） |
| 10 | `PromptInjectionNotificationTest` | 集成（同上） |
| 11 | `L2ConfirmNotificationTest` | 集成（同上） |
| 12 | `AuditLogNotificationDetailTest` | 集成（@SpringBootTest） |
| 13 | `NotificationEnabledDisabledTest` | 集成（mock channel） |

**测试原则**（spec §8.2）：
1. 不依赖真实飞书 / 外网 — 所有 HTTP mock
2. 通知失败不影响 AgentResult — webhook 500 时 chat/send 仍 200
3. 失败必记 FAILED / 成功必记 SENT
4. 重复 eventId+channelId 不重发
5. 敏感字段脱敏
6. 测试数量动态基线：`mvn test -q` → Tests run ≥ N, Failures=0, Errors=0, Skipped ≤ 1

---

## PR 检查清单

提交 PR 前逐项确认：

```text
□ 后端 mvn test -q → Tests run ≥ 550 + N1, Failures=0, Errors=0, Skipped ≤ 1
□ 前端 npm run test:unit -- --run → 190/190 passed, failed=0（不影响前端）
□ E2E npx playwright test → 19/19 + 3 skipped, failed=0（不影响 E2E）
□ L4 BLOCK 触发通知（rm -rf / → notification_records 表新增记录）
□ PROMPT_INJECTION_BLOCK 触发通知（"忽略规则" → notification_records 新增记录）
□ L2_CONFIRM_REQUIRED 触发通知（重启 nginx → notification_records 新增记录）
□ AuditLogDetail 包含 notificationRecords 列表（不含 requestPayload）
□ 测试环境零外网请求（application-test.yml 强制 enabled=false）
□ notification.enabled=false 时 emit() 跳过（不写 record）
□ webhook 5xx 时 chat/send 仍 200，NotificationRecord.status=FAILED
□ secret 字段在 requestPayload 中被 *** 替换
□ (event_id, channel_id) 唯一约束生效
□ NotificationChannelRegistry fail-open 启动（无 handler 不抛异常）
□ dryRun=true → 按配置通道写 SKIPPED（channelId=channelConfig.id，不真实发送）
□ 线程池满 → 只 log error，不写 FAILED record
□ emit() 同步部分不做 DB 写入、不发 HTTP 请求
□ AgentOrchestrator 使用 NotificationEventFactory 创建事件，不直接 builder()
□ 新增构造器 + 老构造器委托（Fix-03 模式）
```

---

## 提交切分建议

```bash
# Task 1-2: 数据模型
git commit -m "feat(notification): add data models (Event/Record/Enum/Repo)"

# Task 3: 配置
git commit -m "feat(notification): add NotificationConfig + multi-profile yml"

# Task 4-5: 接口 + 注册中心 + 工厂
git commit -m "feat(notification): add Channel interface, Registry, EventFactory"

# Task 6-7: 通道实现 + 脱敏
git commit -m "feat(notification): implement WebhookChannel, FeishuChannel, Sanitizer"

# Task 8-9: 调度层
git commit -m "feat(notification): add Service, Dispatcher, AsyncConfig"

# Task 10: Orchestrator 集成
git commit -m "feat(notification): integrate 3 safety emit points in AgentOrchestrator"

# Task 11: AuditLog 联查
git commit -m "feat(notification): join notification records in AuditLogDetail"

# Task 12: 测试
git commit -m "test(notification): add Phase 1 test suite"
```

---

## 后续

合入后打 tag：

```bash
git tag p1-01-foundation-done
```

然后启动 **Plan 02：SERVICE_ABNORMAL / DISK_RISK 触发**。
