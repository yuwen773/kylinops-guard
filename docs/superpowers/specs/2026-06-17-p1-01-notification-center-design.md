# P1-01 告警通道 / Notification Center 设计文档

> **状态**：✅ 已批准（2026-06-17）— 进入 writing-plans 阶段
> **版本**：v0.1
> **日期**：2026-06-17
> **作者**：P1-01 设计（基于 `docs/product/functional-defect-and-roadmap.md` §二 P1 项 D-06 + brainstorming 确认 B-lite 方案）
> **配套文档**：
> - 缺陷报告：[`docs/product/functional-defect-and-roadmap.md`](../../product/functional-defect-and-roadmap.md)
> - P0 冲刺设计：[`docs/superpowers/specs/2026-06-16-p0-defect-fix-sprint-design.md`](2026-06-16-p0-defect-fix-sprint-design.md)
> - P0 RCA 实施计划：[`docs/superpowers/plans/2026-06-16-fix-01-rca-reasoning-chain-plan.md`](../plans/2026-06-16-fix-01-rca-reasoning-chain-plan.md)
> - P0 lsof 实施计划（含 fail-closed 教训）：[`docs/superpowers/plans/2026-06-16-fix-02-lsof-tool-plan.md`](../plans/2026-06-16-fix-02-lsof-tool-plan.md)
> - 需求文档：[`requirement.md`](../../../requirement.md)

---

## 0. 目标与原则

**目标**：在 P0 冲刺（`p0-sprint-released`，后端 550 + 1 skipped · 前端 190 · E2E 19 + 3 skipped）已合入的基础上，新增告警通道（Notification Center）能力，让 L4 阻断、Prompt 注入拦截、L2 待确认、运维异常等事件可被主动推送到外部 Webhook / 飞书机器人，弥补 D-06「告警推送通道缺位」缺陷。**安全事件 MTTD 从"早会才发现"降到"秒级"**。

**原则**：
1. **不破坏基线**：现有 550/550 + 1 skipped backend、190/190 frontend、19/19 + 3 skipped E2E 全部必须继续通过；后端基线数字统一用"动态基线"（Tests run ≥ N, Failures=0, Errors=0, Skipped ≤ 1）。
2. **不扩展产品边界**：本期仅做 D-06 告警通道，不引入 D-05 多主机 / D-07 回滚 / D-08 巡检 / D-09 RAG / D-12 Workflow。
3. **B-lite 方案**：通道只做 Webhook + 飞书 2 个；MVP 不做重试 / 静默 / 聚合 / DSL / 模板 / 多租户 / UI；只记录 SENT / FAILED。
4. **安全护栏不退让**：L0-L4 决策树、Prompt 注入检测、AuditLog 闭环、L2 二次确认一字不动。
5. **通知失败不影响主流程**：所有通知发送走异步线程池 + 失败隔离；`/api/chat/send` 永远 200（除非主流程本身失败）。
6. **默认关闭，演示开启**：`notification.enabled=false` 是默认；`demo` profile 开启；测试环境强制关闭（不允许真实外网请求）。
7. **不写死测试数字**：动态基线（Fix-02/03 教训）。
8. **Fail-open 启动校验**：与 `LlmToolContextPolicyRegistry` 的 fail-closed **相反**——通道未配置时 log warn，**不抛异常**（生产环境可能完全禁用通知）。

---

## 1. 关键决策记录（来自 brainstorming 阶段）

| 决策 | 选择 | 理由 |
|---|---|---|
| **方案选择** | B-lite（标准版精简） | 一次性把骨架做到位；A 方案三个月后会被推翻，C 方案范围爆炸 |
| **通道范围** | Webhook + 飞书 2 个；企业微信放到 P1-02 | 2 通道覆盖国内 SOC 主流；MVP 不堆量 |
| **发送方式** | 异步线程池（`@Async` + 专用 `notificationExecutor`） | 同步发送 = 主流程被外部 webhook 拖慢；与 `AgentOrchestrator.parallelExecutor` 模式一致 |
| **重试策略** | MVP 不做重试，只记 SENT / FAILED | 重试放到 P1-02；MVP 优先把"必发通道 + 必记状态"做对 |
| **存储模型** | `NotificationRecord` 独立 JPA 表，与 `AuditLog` 一对多 | 通知量级远大于审计；避免审计回放慢；唯一约束 `(event_id, channel_id)` 防重复 |
| **配置方式** | `application.yml` + env，**不做 UI** | UI 工作量 = 后端 1.5 倍；MVP 用 yml 即可 |
| **默认状态** | `notification.enabled=false`；`demo` profile 开启；`test` profile 强制关闭 | 测试环境禁止外网；演示场景才需要真发 |
| **触发阶段** | 5 类事件分两阶段交付：① 安全类（必做）② 运维类（必做） | 安全类是 D-06 核心价值；运维类是锦上添花但避免 P1-02 拆包 |
| **Event 字段** | 强类型字段，**不用 `Map<String, Object>` context** | 匹配 P0 RCA 的"业务字段稳定"原则；避免运行时类型错误 |
| **数据脱敏** | `NotificationPayloadSanitizer` 统一处理 | secret / token / password / 敏感路径必须脱敏后入库 |
| **API 出口** | `AuditLogDetail` 返回 `NotificationRecordSummary`（不含 `requestPayload`） | 防止 secret 通过 API 泄露到前端 |
| **依赖选择** | HTTP 客户端由 Plan 01 选型（候选：OkHttp 4 / Spring RestTemplate / Java 11 HttpClient） | 不预先锁定，需 Plan 01 评估项目当前依赖后选型 |
| **Feishu payload** | markdown 类型 | 富文本可读性更好；与 `buildDiscussionAnswer` 输出格式一致 |
| **Spring 注入模式** | `NotificationService` 注入到 `AgentOrchestrator` 走"新增构造器 + 老构造器委托" | Fix-03 教训：不得用 `@RequiredArgsConstructor` 替换现有显式构造器 |

---

## 2. 范围

### 2.1 必做（P1-01 必交付）

1. **数据模型**
   - `NotificationEvent`（不可变 POJO，强类型字段）
   - `NotificationEventType` 枚举（5 类）
   - `NotificationSeverity` 枚举（3 类）
   - `NotificationStatus` 枚举（4 类）
   - `ChannelType` 枚举（2 类）
   - `NotificationRecord` JPA 实体
   - `NotificationRecordSummary` DTO（API 出口）
   - `NotificationPayloadSanitizer` 工具类

2. **通道层**
   - `NotificationChannel` 接口（`type()` / `supports()` / `send()`）
   - `WebhookChannel`（POST JSON，HMAC-SHA256 签名头，3s 超时）
   - `FeishuChannel`（飞书机器人 webhook，timestamp + sign 签名，markdown）
   - `NotificationChannelRegistry`（启动时 log warn，**不抛异常**）

3. **调度层**
   - `NotificationDispatcher`（事件 → 选通道 → 异步发送 → 写 record）
   - `NotificationService`（暴露 `emit(NotificationEvent)` API，注入到 `AgentOrchestrator`）
   - `NotificationAsyncConfig`（`@Bean notificationExecutor`）
   - `NotificationConfig`（`@ConfigurationProperties("kylinops.notification")`）

4. **持久层**
   - `NotificationRecordRepository`（JPA，含 `(event_id, channel_id)` 唯一约束）
   - 数据库迁移（H2 File Mode 自动建表；PostgreSQL 一张表）

5. **集成层**
   - `AgentOrchestrator` 5 个 emit 触发点（详见 §5.3）
   - `AuditLogService.toDetail()` 联查 `notificationRecords`
   - `AuditLogDetail` DTO 新增 `List<NotificationRecordSummary> notificationRecords`
   - `application.yml` / `application-test.yml` / `application-demo.yml` 配置

6. **触发事件**（5 类）
   - **安全类**（Phase 1）：`L4_BLOCK` / `PROMPT_INJECTION_BLOCK` / `L2_CONFIRM_REQUIRED`
   - **运维类**（Phase 2）：`SERVICE_ABNORMAL` / `DISK_RISK`

### 2.2 暂不做（P1-02+ backlog）

- 企业微信 Channel
- 邮件 Channel（IMAP/SMTP）
- 钉钉 Channel
- 失败重试（指数退避）
- 持久化重试队列（当前重试用内存 ScheduleExecutorService 即可，先不做）
- 静默时间（quiet hours）
- 通知聚合（同 eventType 5min 内合并）
- 通知规则 DSL（按 eventType × severity × timeWindow 过滤）
- 模板引擎（Velocity / Freemarker 替换 placeholder）
- 多租户隔离
- 前端通知配置 UI
- 通知中心独立页面（独立 `/notifications` 路由）
- 告警聚合 / 抑制（alert grouping / inhibition）
- 持久化重试队列（用数据库 / Redis）
- Prometheus / Zabbix 替代
- 插件市场

---

## 3. 架构

### 3.1 包结构（新包 `com.kylinops.notification`）

```
com.kylinops.notification/
├── NotificationEvent.java                 # 不可变 POJO
├── NotificationEventType.java              # 枚举
├── NotificationSeverity.java               # 枚举
├── NotificationStatus.java                # 枚举
├── ChannelType.java                       # 枚举
├── NotificationChannel.java               # 接口
├── NotificationChannelRegistry.java       # 通道注册中心（fail-open）
├── NotificationDispatcher.java            # 调度器（异步发送）
├── NotificationService.java               # 入口 API（emit）
├── NotificationConfig.java                # @ConfigurationProperties
├── NotificationAsyncConfig.java           # @Bean notificationExecutor
├── NotificationPayloadSanitizer.java      # 脱敏工具
├── NotificationRecord.java                # JPA 实体
├── NotificationRecordRepository.java      # JPA Repository
├── NotificationRecordSummary.java         # DTO（API 出口）
├── channel/
│   ├── WebhookChannel.java                # 实现 1
│   └── FeishuChannel.java                 # 实现 2
└── exception/
    └── NotificationSendException.java     # 内部异常（不外抛）
```

### 3.2 数据流

```
AgentOrchestrator.process()
    │
    ├── Step 3  handleInjectionBlock()        → notificationService.emit(PROMPT_INJECTION_BLOCK)
    │
    ├── Step 7  case BLOCK  (L4)              → notificationService.emit(L4_BLOCK)
    ├── Step 7  case CONFIRM                  → notificationService.emit(L2_CONFIRM_REQUIRED)
    │
    └── Step 7.5 后 (RCA 已生成)              → notificationService.emit(SERVICE_ABNORMAL / DISK_RISK)
                    │
                    ▼
        NotificationService.emit(event)
                    │
                    ├── ① 同步：NotificationRecord(status=PENDING) 入库（≤ 50ms）
                    │
                    └── ② 异步：notificationExecutor.execute(() -> 
                                    NotificationDispatcher.dispatch(event))
                                                    │
                                                    ├── 选通道（registry + enabled 过滤）
                                                    ├── sanitizer.maskedPayload(event)
                                                    ├── channel.send(payload)  ← try/catch 隔离
                                                    ├── 写 NotificationRecord(status=SENT/FAILED)
                                                    │
                                                    └── 异常：log.error + 写 FAILED（不重试）
```

### 3.3 关键不变量

1. **`process()` 主流程永远不被通知阻塞**：所有 `emit()` 同步部分 ≤ 50ms（只入库），发送在 `notificationExecutor` 线程池
2. **异常隔离**：`channel.send()` 任何异常都被 catch 住，写 `NotificationRecord.status=FAILED`，**绝不冒泡**
3. **Fail-open 启动**：`NotificationChannelRegistry.init()` 只 log warn，**不抛 `IllegalStateException`**（与 `LlmToolContextPolicyRegistry` 的 fail-closed 相反）
4. **不重复发送**：`UNIQUE (event_id, channel_id)` 数据库约束 + 应用层去重检查
5. **不修改 AuditLog entity**：仅在 `AuditLogDetail` DTO 加 `List<NotificationRecordSummary> notificationRecords`

### 3.4 异步执行器配置

```java
// NotificationAsyncConfig.java
@Bean(name = "notificationExecutor")
public Executor notificationExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(2);
    executor.setMaxPoolSize(5);
    executor.setQueueCapacity(100);
    executor.setThreadNamePrefix("notification-");
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    // CallerRunsPolicy：队列满时由调用方线程执行 → 主流程变慢但不丢通知
    // 调用方是 AgentOrchestrator 主流程，需评估风险（详见 §7 风险表）
    executor.initialize();
    return executor;
}
```

---

## 4. 数据模型

### 4.1 `NotificationEvent`（不可变 POJO）

```java
package com.kylinops.notification;

import com.kylinops.common.enums.IntentType;
import com.kylinops.common.enums.RiskDecision;
import com.kylinops.common.enums.RiskLevel;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 通知事件 — Notification Center 的核心 DTO。
 *
 * <p><b>设计原则</b>：</p>
 * <ul>
 *   <li>不可变 POJO（@Data + @Builder，无 setter）</li>
 *   <li>强类型字段（不用 Map<String, Object>，避免运行时类型错误）</li>
 *   <li>按 eventType 决定哪些字段有效，便于审计回放</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationEvent {

    /**
     * <p><b>逻辑不可变</b>：builder 创建后不再修改。
     * 字段非 final 是因为 {@link NotificationService#emit(NotificationEvent)} 在
     * eventId 为空时需要回填 UUID（@Data 生成 setter 即可，不开放给业务代码）。</p>
     */

    /** 事件唯一 ID（UUID，由 NotificationService 生成；与 (channelId) 联合唯一） */
    private String eventId;

    /** 事件类型 */
    private NotificationEventType eventType;

    /** 严重等级 */
    private NotificationSeverity severity;

    /** 通知标题（人类可读） */
    private String title;

    /** 通知摘要（≤ 200 字） */
    private String summary;

    /** 通知详情（≤ 500 字，超出截断） */
    private String detail;

    /** 会话 ID（来自 AgentRequest） */
    private String sessionId;

    /** 审计 ID（与 AuditLog.auditId 关联） */
    private String auditId;

    /** 风险等级（L4_BLOCK 时填，其他可空） */
    private RiskLevel riskLevel;

    /** 风险决策（BLOCK / CONFIRM / ALLOW） */
    private RiskDecision riskDecision;

    /** 意图类型（运维类事件时填） */
    private IntentType intentType;

    // ───── 强类型 context 字段（按 eventType 取舍） ─────

    /** 命中的风险规则 ID（L4_BLOCK 时填，如 "rm_root_recursive"） */
    private String matchedRuleId;

    /** 服务名（SERVICE_ABNORMAL 时填） */
    private String serviceName;

    /** 磁盘路径（DISK_RISK 时填） */
    private String diskPath;

    /** 磁盘使用率（DISK_RISK 时填，0.0-100.0） */
    private Double diskUsagePercent;

    /** Prompt 注入命中的模式（PROMPT_INJECTION_BLOCK 时填） */
    private String promptInjectionPattern;

    /** RCA 置信度（运维类事件时填，0.0-1.0） */
    private Double rcaConfidence;

    /** 发生时间（Clock 注入） */
    private LocalDateTime occurredAt;
}
```

### 4.2 `NotificationEventType` 枚举

```java
public enum NotificationEventType {
    /** L4 绝对阻断（rm -rf / 等） */
    L4_BLOCK,
    /** Prompt 注入拦截 */
    PROMPT_INJECTION_BLOCK,
    /** L2 待用户确认 */
    L2_CONFIRM_REQUIRED,
    /** 服务异常（SERVICE_DIAGNOSIS + RCA 置信度 ≥ 0.7） */
    SERVICE_ABNORMAL,
    /** 磁盘风险（DISK_DIAGNOSIS + 磁盘 ≥ 85% 或 RCA 置信度 ≥ 0.7） */
    DISK_RISK
}
```

### 4.3 `NotificationSeverity` 枚举

```java
public enum NotificationSeverity {
    /** 严重：必须立即处理（L4_BLOCK / PROMPT_INJECTION_BLOCK） */
    CRITICAL,
    /** 警告：需要关注（L2_CONFIRM_REQUIRED） */
    WARNING,
    /** 信息：仅供参考（SERVICE_ABNORMAL / DISK_RISK） */
    INFO
}
```

### 4.4 `NotificationStatus` 枚举

```java
public enum NotificationStatus {
    /** 已入队，等待发送 */
    PENDING,
    /** 发送成功 */
    SENT,
    /** 发送失败 */
    FAILED,
    /** 预留：重试中（本期不实现，状态机走 PENDING → SENT/FAILED） */
    RETRYING
}
```

### 4.5 `ChannelType` 枚举

```java
public enum ChannelType {
    /** 通用 Webhook（POST JSON） */
    WEBHOOK,
    /** 飞书机器人 */
    FEISHU
}
```

### 4.6 `NotificationRecord` JPA 实体

```java
package com.kylinops.notification;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "notification_records",
       uniqueConstraints = @UniqueConstraint(name = "uk_event_channel",
                                             columnNames = {"event_id", "channel_id"}),
       indexes = {
           @Index(name = "idx_audit_id", columnList = "audit_id"),
           @Index(name = "idx_created_at", columnList = "created_at")
       })
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class NotificationRecord {

    @Id
    @Column(length = 36)
    private String recordId;   // UUID

    @Column(nullable = false, length = 36)
    private String eventId;    // 联合唯一

    @Column(nullable = false, length = 36)
    private String auditId;    // 索引

    @Column(nullable = false, length = 100)
    private String channelId;  // 通道实例标识（如 "webhook-prod"），联合唯一

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChannelType channelType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationStatus status;

    /** 脱敏后的请求 payload（JSON 字符串） */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String requestPayload;

    /** 响应体（截断到 1KB） */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String responseBody;

    /** HTTP 响应码 */
    private Integer responseCode;

    /** 错误信息（脱敏后） */
    @Column(length = 500)
    private String errorMessage;

    /** 重试次数（本期固定 0，P1-02 启用） */
    @Column(nullable = false)
    private int retryCount;

    /** 实际发送完成时间 */
    private LocalDateTime sentAt;

    /** 记录创建时间（Clock 注入） */
    @Column(nullable = false)
    private LocalDateTime createdAt;
}
```

### 4.7 `NotificationRecordSummary` DTO（API 出口）

```java
package com.kylinops.notification;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 通知记录摘要 — API 出口 DTO。
 *
 * <p><b>安全约束</b>：</p>
 * <ul>
 *   <li>不含 requestPayload（防止 secret 通过 API 泄露到前端）</li>
 *   <li>不含 responseBody（仅运维诊断用，API 不暴露）</li>
 *   <li>errorMessage 已脱敏</li>
 * </ul>
 */
@Data
@Builder
public class NotificationRecordSummary {
    private String recordId;
    private String eventId;
    private String auditId;
    private String channelId;
    private ChannelType channelType;
    private NotificationStatus status;
    private Integer responseCode;
    private String errorMessage;
    private int retryCount;
    private LocalDateTime sentAt;
    private LocalDateTime createdAt;
}
```

### 4.8 `NotificationChannel` 接口

```java
package com.kylinops.notification;

/**
 * 通知通道接口。
 *
 * <p><b>设计原则</b>：</p>
 * <ul>
 *   <li>实现类必须是 Spring @Component，自动注册到 NotificationChannelRegistry</li>
 *   <li>supports() 返回 false 时 dispatcher 跳过该通道</li>
 *   <li>send() 抛出异常时 dispatcher 捕获并写 FAILED 记录</li>
 * </ul>
 */
public interface NotificationChannel {

    /** 通道类型（用于 registry 索引） */
    ChannelType type();

    /** 通道实例 ID（来自配置：kylinops.notification.channels.{type}.id） */
    String channelId();

    /**
     * 是否处理该事件。
     * <p>默认返回 true；子类可按 eventType / severity 过滤</p>
     */
    default boolean supports(NotificationEvent event) {
        return true;
    }

    /**
     * 发送通知。**任何异常必须被抛回 dispatcher 统一处理**，不在实现类内 catch。
     *
     * @param event          事件
     * @param maskedPayload  脱敏后的 payload（dispatcher 已处理）
     * @return 发送结果（包含 responseCode / responseBody）
     */
    NotificationSendResult send(NotificationEvent event, String maskedPayload);
}
```

### 4.9 `NotificationSendResult`

```java
package com.kylinops.notification;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class NotificationSendResult {
    private boolean success;
    private Integer responseCode;
    private String responseBody;  // 截断到 1KB
    private String errorMessage;  // 失败时填
}
```

### 4.10 `NotificationConfig`（`@ConfigurationProperties`）

```java
package com.kylinops.notification;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.List;
import java.util.Map;

/**
 * 通知中心配置。
 *
 * <p>命名空间：<code>kylinops.notification.*</code></p>
 *
 * <p>application.yml 示例：</p>
 * <pre>
 * kylinops:
 *   notification:
 *     enabled: false          # 全局开关（默认 false；demo profile 开启）
 *     dry-run: false          # true 时只 log 不真发
 *     channels:
 *       - id: webhook-prod
 *         type: WEBHOOK
 *         enabled: true
 *         url: https://ops.example.com/webhook
 *         secret: change-me
 *       - id: feishu-prod
 *         type: FEISHU
 *         enabled: true
 *         webhookUrl: https://open.feishu.cn/hook/xxx
 *         secret: change-me
 * </pre>
 */
@Data
@ConfigurationProperties(prefix = "kylinops.notification")
public class NotificationConfig {

    /** 全局开关（默认 false） */
    private boolean enabled = false;

    /** dry-run 模式（true 时只 log 不真发） */
    private boolean dryRun = false;

    /** 通道列表 */
    private List<ChannelConfig> channels = List.of();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ChannelConfig {
        /** 通道实例 ID（全局唯一） */
        private String id;
        /** 通道类型（WEBHOOK / FEISHU） */
        private ChannelType type;
        /** 是否启用（默认 true） */
        private boolean enabled = true;
        /**
         * 通道 URL（WEBHOOK 与 FEISHU 共用字段）。
         * <ul>
         *   <li>WEBHOOK：通用 webhook 接收端点</li>
         *   <li>FEISHU：飞书机器人 incoming webhook URL（形如 https://open.feishu.cn/hook/xxx）</li>
         * </ul>
         * 空字符串视为未配置，dispatcher 自动跳过该通道
         */
        private String url;
        /** HMAC 签名密钥（WEBHOOK 可选，FEISHU 必填） */
        private String secret;
        /** 连接 / 读超时（ms，默认 3000） */
        private Integer timeoutMs;
    }
}
```

### 4.11 `NotificationPayloadSanitizer`

```java
package com.kylinops.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 通知 payload 脱敏器。
 *
 * <p>规则：</p>
 * <ul>
 *   <li>匹配 key 包含 secret/token/password/api_key/access_key 的字段 → 替换为 "***"</li>
 *   <li>匹配 value 包含 /etc/passwd / /etc/shadow / ~/.ssh/ 的路径 → 替换为 "/etc/[REDACTED]" 等</li>
 *   <li>匹配 userInput 原文（风险命令） → 仅保留前 50 字符 + "..."</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class NotificationPayloadSanitizer {

    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(?i).*(secret|token|password|api_?key|access_?key|private_?key).*");
    private static final Pattern SENSITIVE_PATH = Pattern.compile(
            "(/etc/(passwd|shadow)|/root/\\.ssh/|/home/[^/]+/\\.ssh/|/var/lib/mysql/)");

    private final ObjectMapper objectMapper;

    /**
     * 脱敏 payload。返回 JSON 字符串。
     */
    public String mask(NotificationEvent event) {
        // 实际实现：把 event 转 Map → 遍历 key/value 匹配规则 → 序列化回 JSON
    }
}
```

### 4.12 `NotificationChannelRegistry`

```java
package com.kylinops.notification;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 通知通道注册中心。
 *
 * <p><b>启动策略：fail-open</b>（与 LlmToolContextPolicyRegistry 相反）：</p>
 * <ul>
 *   <li>有通道 → 全部注册，log info</li>
 *   <li>无通道（enabled=false 或未配置） → log warn，<b>不抛异常</b></li>
 *   <li>重复 channelId → 覆盖 + log warn</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationChannelRegistry {

    private final List<NotificationChannel> channels;
    private final NotificationConfig config;

    private final Map<String, NotificationChannel> channelMap = new LinkedHashMap<>();

    @PostConstruct
    public void init() {
        for (NotificationChannel channel : channels) {
            String id = channel.channelId();
            if (id == null || id.isBlank()) {
                log.warn("通道 [{}] 缺少 channelId，跳过注册", channel.getClass().getSimpleName());
                continue;
            }
            NotificationChannel existing = channelMap.put(id, channel);
            if (existing != null) {
                log.warn("通道 [{}] 被覆盖: {} -> {}", id,
                        existing.getClass().getSimpleName(), channel.getClass().getSimpleName());
            }
        }
        log.info("NotificationChannelRegistry 初始化完成: 共 {} 个通道", channelMap.size());
        if (log.isDebugEnabled()) {
            log.debug("已注册通道: {}", channelMap.keySet());
        }
    }

    /**
     * 按 eventType + enabled 过滤出可用通道。
     */
    public List<NotificationChannel> resolveChannels(NotificationEvent event) {
        if (!config.isEnabled() || config.isDryRun()) {
            return List.of();
        }
        // 按 config.channels 顺序遍历，匹配 type + enabled + channelMap.contains
        List<NotificationChannel> result = new ArrayList<>();
        for (NotificationConfig.ChannelConfig cc : config.getChannels()) {
            if (!cc.isEnabled()) continue;
            NotificationChannel ch = channelMap.get(cc.getId());
            if (ch == null) continue;
            if (ch.type() != cc.getType()) continue;
            if (!ch.supports(event)) continue;
            result.add(ch);
        }
        return result;
    }

    public boolean hasAnyChannel() {
        return !channelMap.isEmpty();
    }
}
```

### 4.13 `NotificationDispatcher`

```java
package com.kylinops.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 通知调度器。
 *
 * <p><b>执行模式</b>：</p>
 * <ul>
 *   <li>由 NotificationService.emit() 触发（同步）</li>
 *   <li>实际发送在 @Async notificationExecutor 线程池</li>
 *   <li>任何 channel.send() 异常都被 try/catch，写 NotificationRecord.status=FAILED</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationDispatcher {

    private final NotificationChannelRegistry registry;
    private final NotificationPayloadSanitizer sanitizer;
    private final NotificationRecordRepository recordRepository;
    private final Clock clock;  // 测试用 Clock 注入

    /**
     * 异步发送入口。**在 @Async 线程池执行**。
     */
    @Async("notificationExecutor")
    public void dispatchAsync(String eventId) {
        // 1. 从 eventId 查 NotificationRecord（PENDING）
        // 2. 解析 event（用 mapper 从 record.requestPayload 反序列化）
        // 3. registry.resolveChannels(event) 选通道
        // 4. 对每个通道：try/catch channel.send() → 更新 record.status
        // 5. recordRepository.save(record)
    }
}
```

### 4.14 `NotificationService`

```java
package com.kylinops.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.UUID;

/**
 * 通知服务 — AgentOrchestrator 的唯一入口。
 *
 * <p><b>职责</b>：</p>
 * <ul>
 *   <li>生成 eventId</li>
 *   <li>创建 NotificationRecord(status=PENDING) — 同步入库，≤ 50ms</li>
 *   <li>触发 dispatcher.dispatchAsync(eventId) — 异步发送</li>
 * </ul>
 *
 * <p><b>安全约束</b>：emit() 永远不抛异常；任何异常 log.error 即可</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationDispatcher dispatcher;
    private final NotificationRecordRepository recordRepository;
    private final NotificationPayloadSanitizer sanitizer;
    private final Clock clock;

    public void emit(NotificationEvent event) {
        try {
            if (event.getEventId() == null) {
                event.setEventId(UUID.randomUUID().toString());
            }
            String maskedPayload = sanitizer.mask(event);
            NotificationRecord record = NotificationRecord.builder()
                    .recordId(UUID.randomUUID().toString())
                    .eventId(event.getEventId())
                    .auditId(event.getAuditId())
                    .channelId("__pending__")  // 异步发送时填实际 channelId
                    .channelType(null)          // 异步发送时填
                    .status(NotificationStatus.PENDING)
                    .requestPayload(maskedPayload)
                    .retryCount(0)
                    .createdAt(java.time.LocalDateTime.now(clock))
                    .build();
            recordRepository.save(record);
            dispatcher.dispatchAsync(event.getEventId());
        } catch (Exception e) {
            log.error("通知 emit 失败: eventId={}, eventType={}", 
                    event.getEventId(), event.getEventType(), e);
            // 永不抛
        }
    }
}
```

---

## 5. 触发事件

### 5.1 5 类事件总览

| 事件 | 严重等级 | 触发位置 | 触发条件 | 阶段 |
|---|---|---|---|---|
| `L4_BLOCK` | CRITICAL | `AgentOrchestrator.process()` Step 7 `case BLOCK` | `decision == BLOCK && riskLevel == L4` | Phase 1（必做） |
| `PROMPT_INJECTION_BLOCK` | CRITICAL | `AgentOrchestrator.handleInjectionBlock()` 出口 | `injection.isInjectionDetected() == true` | Phase 1（必做） |
| `L2_CONFIRM_REQUIRED` | WARNING | `AgentOrchestrator.process()` Step 7 `case CONFIRM` | `decision == CONFIRM` | Phase 1（必做） |
| `SERVICE_ABNORMAL` | INFO | `AgentOrchestrator.process()` Step 7.5 之后 | `intent == SERVICE_DIAGNOSIS && rootCauseChain != null && rootCauseChain.confidence >= 0.7` | Phase 2（必做） |
| `DISK_RISK` | INFO | `AgentOrchestrator.process()` Step 7.5 之后 | `intent == DISK_DIAGNOSIS && (diskUsagePercent >= 85.0 || rootCauseChain.confidence >= 0.7)` | Phase 2（必做） |

### 5.2 触发条件可靠性

| 事件 | 可靠性 | 误报风险 | 说明 |
|---|---|---|---|
| `L4_BLOCK` | 极高 | 0% | 决策点硬条件 |
| `PROMPT_INJECTION_BLOCK` | 高 | 极低 | regex 命中（已有 PromptInjectionDetector 单测覆盖） |
| `L2_CONFIRM_REQUIRED` | 极高 | 0% | 决策点硬条件 |
| `SERVICE_ABNORMAL` | 中 | 低 | 仅在 RCA 给出结论时触发；confidence 门槛挡住误报 |
| `DISK_RISK` | 中 | 低 | 同上；diskUsagePercent 来自 disk_usage_tool 数据 |

### 5.3 触发位置（精确到方法 / 行号）

基于 `AgentOrchestrator.java` 当前实现：

| 事件 | 触发点 | 关键不变量 |
|---|---|---|
| `PROMPT_INJECTION_BLOCK` | `handleInjectionBlock()` 出口（行 331-358） | 必须先于任何工具调用；injection 检测在 Step 3 |
| `L4_BLOCK` | `process()` Step 7 `case BLOCK`（行 245-249） | 必须在 riskCheckService 之后；不修改 RiskRuleEngine |
| `L2_CONFIRM_REQUIRED` | `process()` Step 7 `case CONFIRM`（行 218-244） | 必须在 createAction 之后；**绝不调 confirm()** |
| `SERVICE_ABNORMAL` | `process()` Step 7.5 之后（行 251-259 之后） | 必须有 RCA；confidence < 0.7 不发 |
| `DISK_RISK` | `process()` Step 7.5 之后 | 同上 |

**关键不变量**：
- 所有 `emit()` 调用都在 `process()` 的 `try` 块内（行 134-323），任何异常都被 catch
- `handleInjectionBlock()` 是独立 try/finally，`emit()` 在 finally 块之前（保证发出）
- `NotificationService.emit()` 自身 try/catch，**永不抛**

### 5.4 Phase 1（安全类）— 必做

1. `L4_BLOCK` — 必做
2. `PROMPT_INJECTION_BLOCK` — 必做
3. `L2_CONFIRM_REQUIRED` — 必做

### 5.5 Phase 2（运维类）— 必做

1. `SERVICE_ABNORMAL` — 必做
2. `DISK_RISK` — 必做

> Phase 1 和 Phase 2 在同一 spec 内，由 writing-plans 拆为两个 plan 顺序交付。

---

## 6. 安全约束（P0 不变量继承 + P1-01 新增）

### 6.1 P0 已确立的不变量（不可破坏）

1. **COMMAND_EXECUTION 永远优先于 synonym / FAQ / LLM**
2. **Prompt Inject 检测在 intent 分类之前**
3. **L4 BLOCK（rm -rf /、chmod -R 777 /）仍 100% 拦截**
4. **L2 CONFIRM 不跳过二次确认**
5. **LLM 不参与安全决策**
6. **AuditLog 全链路审计**
7. **No raw shell / No /api/exec**

### 6.2 P1-01 新增约束

1. **通知失败不能影响 Agent 主流程**
   - `emit()` 同步部分只入库（≤ 50ms）
   - 发送在异步线程池
   - 任何异常 log.error，绝不抛

2. **Webhook / 飞书失败**
   - 写 `NotificationRecord.status=FAILED`
   - **不影响** chat/send 200 响应

3. **通知内容脱敏**
   - `NotificationPayloadSanitizer` 替换 `secret` / `token` / `password` / `api_key` / `access_key` / `private_key`
   - 替换敏感路径 `/etc/passwd` / `/etc/shadow` / `/root/.ssh/` / `/var/lib/mysql/`

4. **不推送敏感字段**
   - webhook secret / SMTP password / SSH key **绝不**进 `NotificationRecord.requestPayload`（sanitizer 统一处理）

5. **webhook secret 不暴露到前端**
   - `NotificationRecordSummary` DTO **不包含** secret 字段
   - `NotificationConfig` 在 `/api/notifications/config` 端点（本期不实现）也**不返回** secret

6. **不发到 Report.bodyMarkdown**
   - `ReportService.generate()` 不修改
   - 通知记录在 `AuditLogDetail` 单独展示（`notificationRecords` 字段）

7. **通过 auditId 追溯**
   - `NotificationRecord.auditId` 强约束 NOT NULL + 索引
   - `AuditLogService.toDetail()` 联查并返回 `NotificationRecordSummary` 列表

8. **不重复发送**
   - `UNIQUE (event_id, channel_id)` 数据库约束（H2 / PostgreSQL 兼容）
   - 应用层去重检查：`recordRepository.findByEventIdAndChannelId()`

9. **测试不写死数字**
   - 动态基线：`Tests run ≥ N, Failures=0, Errors=0, Skipped ≤ 1`
   - 不允许出现"必须 = 580"这种硬编码

10. **Fail-open 启动**
    - `NotificationChannelRegistry.init()` 只 log warn，**不抛** `IllegalStateException`
    - `notification.enabled=false` 时，emit() 直接 log.debug 跳过

---

## 7. 风险表

| # | 风险 | 影响 | 建议缓解 | 阻塞 MVP? |
|---|---|---|---|---|
| 1 | 通知阻塞主流程 | chat/send 响应 P99 翻倍 | 同步只入库（≤ 50ms），发送在 `notificationExecutor`；`CallerRunsPolicy` 兜底 | ❌ 不阻塞 |
| 2 | Webhook 超时 | 发送线程堆积 | OkHttp `timeout(3, SECONDS)` + 异步 cancel；连接池上限 10 | ❌ 不阻塞 |
| 3 | 飞书接口失败 | 通知丢失 | MVP 不做重试；写 FAILED 记录；P1-02 再加重试 | ❌ 不阻塞 |
| 4 | 通知风暴 | 性能 + 客户投诉 | `(event_id, channel_id)` 唯一约束 + 通道级 `enabled` + `dry-run` 模式 | ⚠️ 必须实现 |
| 5 | 密钥泄露（webhook secret 入 DB / 入前端） | 合规事故 | `NotificationPayloadSanitizer` + `NotificationRecordSummary` 不含 secret | ⚠️ 必须实现 |
| 6 | 敏感命令泄露（payload 含 `rm -rf /`） | 合规事故 | sanitizer 替换 `userInput` 为"已脱敏"，仅保留 `eventType + decision + severity` | ⚠️ 必须实现 |
| 7 | AuditLog 关联混乱 | 审计追溯失败 | `NotificationRecord.auditId` 强约束 NOT NULL + 索引 | ❌ 不阻塞 |
| 8 | 重复推送 | 客户投诉 | `(event_id, channel_id)` 唯一 + 应用层去重 | ⚠️ 必须实现 |
| 9 | Spring Bean 注入失败（Fix-02 教训） | 全栈测试炸裂 | `NotificationChannelRegistry` 启动只 log warn，**不抛**；`notification.enabled=false` 时跳过全部 emit | ⚠️ 必须实现 |
| 10 | 测试环境无外网 | WebhookChannel 单测失败 | `OkHttp MockWebServer`（项目已用 OkHttp）；真实 URL 走 mock | ⚠️ 必须实现 |
| 11 | Windows dev vs Linux/Kylin 差异 | 路径解析不同 | 通知层不直接调 OS，**无此风险** | ❌ 不阻塞 |
| 12 | E2E 依赖第三方 webhook 不稳定 | E2E flaky | E2E 用 mock webhook 端点（localhost mock server） | ⚠️ 必须实现 |
| 13 | dispatcher 在主流程同步执行 | 主流程变慢 | 同步部分只入库；发送在 `notificationExecutor` | ❌ 不阻塞 |
| 14 | `Clock` 未注入 | 时间相关测试 flaky | 注入 `Clock` bean，测试用 `Clock.fixed()` | ❌ 不阻塞 |
| 15 | 飞书签名算法错误 | 所有飞书通知失败 | 严格按飞书官方文档实现 + 单测覆盖 sign 计算 | ⚠️ 必须验证 |
| 16 | `AgentOrchestrator` 改造打破现有测试 | Fix-03 教训 | 走"新增构造器 + 老构造器委托"模式；**不得用** `@RequiredArgsConstructor` 替换现有显式构造器 | ❌ 不阻塞 |
| 17 | H2 schema 自动建表失败 | 启动失败 | `@Entity` + `@Table` + `uniqueConstraints` 标准 JPA 注解；H2 File Mode 自动支持 | ❌ 不阻塞 |
| 18 | 通知与 RCA 同帧竞争 | 顺序错乱 | 通知触发在 RCA 生成**之后**（Step 7.5 之后） | ❌ 不阻塞 |

---

## 8. 测试策略

### 8.1 测试矩阵（13 个测试类，约 40–50 个测试方法）

| # | 测试类 | 覆盖范围 | mock 策略 |
|---|---|---|---|
| 1 | `NotificationEventTest` | builder / 字段校验 / 强类型 context | 纯 POJO |
| 2 | `NotificationPayloadSanitizerTest` | secret / token / path 脱敏 | 纯单元 |
| 3 | `WebhookChannelTest` | HMAC 签名 / 超时 / 4xx/5xx / 异常 | `OkHttp MockWebServer` |
| 4 | `FeishuChannelTest` | timestamp + sign 算法 / markdown payload | `MockWebServer` |
| 5 | `NotificationChannelRegistryTest` | fail-open 启动 / enabled 过滤 / supports | `@SpringBootTest` + mock channel |
| 6 | `NotificationDispatcherTest` | 通道选择 / 异步执行 / 失败隔离 | mock 2 个 channel + 注入 fake `TaskExecutor` |
| 7 | `NotificationServiceTest` | emit API / eventId 去重 / 同步入库 / 永不抛 | mock dispatcher + JPA repository |
| 8 | `NotificationRecordRepositoryTest` | JPA CRUD / 唯一约束 / 索引 | `@DataJpaTest` + H2 |
| 9 | `L4BlockNotificationIntegrationTest` | L4 触发 / payload 内容 | `@SpringBootTest` + mock channel |
| 10 | `PromptInjectionNotificationTest` | handleInjectionBlock 路径 | 同上 |
| 11 | `L2ConfirmNotificationTest` | L2 CONFIRM 触发 / 不 auto-confirm | 同上 |
| 12 | `AuditLogNotificationDetailTest` | toDetail 联查 / 不含 requestPayload | `@SpringBootTest` |
| 13 | `NotificationEnabledDisabledTest` | `enabled=false` / `dry-run=true` / channel.enabled=false 不发 | mock channel，断言 send() 调用次数 = 0 |
| 14 | `ServiceDiskAbnormalNotificationTest` | Phase 2：SERVICE_ABNORMAL / DISK_RISK 触发条件 | `@SpringBootTest` + mock RCA |

**14 个测试类，约 45–55 个测试方法**。

### 8.2 测试原则

1. **不依赖真实飞书 / 外网**：所有 HTTP 用 `OkHttp MockWebServer` 或 Spring `MockRestServiceServer`
2. **mock channel 注入**：测试类用 `@MockBean NotificationChannel` 注入
3. **通知失败不影响 AgentResult**：`L4BlockNotificationIntegrationTest` 验证 webhook 500 时 chat/send 仍返回 200
4. **失败必记 FAILED / 成功必记 SENT**：`NotificationServiceTest` 覆盖两个分支
5. **重复 eventId+channelId 不重发**：`NotificationRecordRepositoryTest` 用 `eventId1 + channelId1` 两次插入，断言抛 `DataIntegrityViolationException`
6. **敏感字段脱敏**：`NotificationPayloadSanitizerTest` 输入 `webhook-secret=abc123` → 输出 `webhook-secret=***`
7. **L4 BLOCK 仍保持原行为**：`L4BlockNotificationIntegrationTest` 验证 emit 后 L4 决策不变
8. **Prompt Inject 仍保持原行为**：`PromptInjectionNotificationTest` 同上
9. **测试数量动态基线**：`mvn test -q` → `Tests run ≥ N, Failures=0, Errors=0, Skipped ≤ 1`
10. **Clock 注入可控**：`NotificationServiceTest` 用 `Clock.fixed(...)` 验证 createdAt

### 8.3 验收不变量（动态基线）

```text
□ 后端 mvn test -q → Tests run ≥ 550 + N, Failures=0, Errors=0, Skipped ≤ 1
□ 前端 npm run test:unit -- --run → 190/190 passed, failed=0
□ E2E npx playwright test → 19/19 + 3 skipped, failed=0
□ L4 BLOCK 仍 100% 拦截（与 P0 基线一致）
□ Prompt Inject 仍 100% 拦截（与 P0 基线一致）
□ L2 CONFIRM 仍 100% 要求用户确认（与 P0 基线一致）
□ 通知失败时 /api/chat/send 仍返回 200
□ 测试环境 notification.enabled=false，零外网请求
□ application-test.yml 强制 notification.enabled=false
```

---

## 9. 配置文件

### 9.1 `application.yml`（默认 / 生产基线）

```yaml
kylinops:
  notification:
    enabled: false  # 默认关闭（demo profile 开启）
    dry-run: false
    channels: []    # 默认无通道（运维按需配置）
```

### 9.2 `application-demo.yml`

```yaml
kylinops:
  notification:
    enabled: true
    dry-run: false
    channels:
      - id: webhook-demo
        type: WEBHOOK
        enabled: true
        url: ${KYLINOPS_DEMO_WEBHOOK_URL:}  # env 注入；空字符串 = 该通道不生效
        secret: ${KYLINOPS_DEMO_WEBHOOK_SECRET:}
        timeoutMs: 3000
      - id: feishu-demo
        type: FEISHU
        enabled: true
        url: ${KYLINOPS_DEMO_FEISHU_URL:}
        secret: ${KYLINOPS_DEMO_FEISHU_SECRET:}
        timeoutMs: 3000
```

### 9.3 `application-test.yml`

```yaml
kylinops:
  notification:
    enabled: false  # 测试环境强制关闭，不允许真实外网请求
    dry-run: false
    channels: []
```

### 9.4 关键不变量

- `application.yml` 默认 `enabled: false`
- `application-demo.yml` 显式 `enabled: true`（演示场景）
- `application-test.yml` 显式 `enabled: false`（测试环境禁外网）
- 通道 URL 全部走 env 变量（**禁止**硬编码到 yml）
- secret 全部走 env 变量

---

## 10. 实施顺序

### 10.1 Phase 1（安全类事件）— 必做

writing-plans 拆为 **Plan 01：NotificationCenter 骨架**（含所有通道 + 3 个安全事件触发）

1. 数据模型（Event / EventType / Severity / Status / ChannelType）
2. NotificationRecord + Repository + JPA 表
3. NotificationConfig + application.yml 多 profile
4. NotificationChannel 接口 + NotificationChannelRegistry（fail-open）
5. WebhookChannel + FeishuChannel
6. NotificationPayloadSanitizer
7. NotificationAsyncConfig（@Bean notificationExecutor）
8. NotificationService + NotificationDispatcher
9. AgentOrchestrator 集成（3 个 emit 点：L4_BLOCK / PROMPT_INJECTION_BLOCK / L2_CONFIRM_REQUIRED）
10. AuditLogService.toDetail 联查 + AuditLogDetail.notificationRecords
11. Phase 1 测试矩阵（10 个测试类）

### 10.2 Phase 2（运维类事件）— 必做

writing-plans 拆为 **Plan 02：SERVICE_ABNORMAL / DISK_RISK 触发**（仅触发点 + 2 个新测试类）

1. AgentOrchestrator 集成（2 个新 emit 点：SERVICE_ABNORMAL / DISK_RISK）
2. RCA 置信度判断逻辑（在 AgentOrchestrator 新增 helper 方法）
3. Phase 2 测试（2 个新测试类）

---

## 11. 验收标准（DoD）

### 11.1 Phase 1 验收（必做）

```text
□ 后端 mvn test -q → Tests run ≥ 550 + N1, Failures=0, Errors=0, Skipped ≤ 1
□ 前端 npm run test:unit -- --run → 190/190 passed, failed=0
□ E2E npx playwright test → 19/19 + 3 skipped, failed=0
□ L4 BLOCK 触发通知（rm -rf / → notification_records 表新增 1 条 SENT/FAILED）
□ PROMPT_INJECTION_BLOCK 触发通知（"忽略规则" → notification_records 新增 1 条）
□ L2_CONFIRM_REQUIRED 触发通知（重启 nginx → notification_records 新增 1 条）
□ AuditLogDetail 包含 notificationRecords 列表（不含 requestPayload）
□ 测试环境零外网请求（application-test.yml 强制 enabled=false）
□ notification.enabled=false 时 emit() 跳过（不写 record）
□ webhook 5xx 错误时 chat/send 仍 200，NotificationRecord.status=FAILED
□ secret 字段在 NotificationRecord.requestPayload 中被 *** 替换
□ (event_id, channel_id) 唯一约束生效（重复插入抛 DataIntegrityViolationException）
```

### 11.2 Phase 2 验收（必做）

```text
□ SERVICE_DIAGNOSIS + RCA confidence ≥ 0.7 → 触发 SERVICE_ABNORMAL
□ DISK_DIAGNOSIS + 磁盘 ≥ 85% → 触发 DISK_RISK
□ RootCauseChain 为 null 时不触发
□ RCA confidence < 0.7 时不触发
□ 后端 mvn test -q → Tests run ≥ 550 + N1 + N2, Failures=0, Errors=0
```

### 11.3 通用不变量

```text
□ 通知失败不影响 /api/chat/send
□ 通知内容脱敏（secret / token / 敏感路径）
□ NotificationRecordSummary 不含 requestPayload
□ UNIQUE (event_id, channel_id) 约束生效
□ 测试数字全部动态基线
□ Spring Context 启动无 BeanCreationException
□ NotificationChannelRegistry 启动 fail-open（无通道不抛）
```

---

## 12. Hard Don'ts

- ❌ 不要在 P1-01 引入新 `IntentType` 枚举值（必须沿用现有 10 个）
- ❌ 不要修改 `RiskRuleEngine` / `PromptInjectionDetector`（P0 安全基线）
- ❌ 不要修改 `Report.bodyMarkdown`（P0 v-text 安全契约）
- ❌ 不要让通知发送阻塞主流程（同步部分 ≤ 50ms）
- ❌ 不要把 webhook secret / SMTP password 入 DB / 入前端
- ❌ 不要在 `application-test.yml` 启用通知（测试环境禁外网）
- ❌ 不要写死测试数字（动态基线）
- ❌ 不要用 `@RequiredArgsConstructor` 替换现有显式构造器（Fix-03 教训）
- ❌ 不要让 `NotificationChannelRegistry` 启动 fail-closed（与 P0 教训相反）
- ❌ 不要在 P1-01 范围引入多主机 / 回滚 / 巡检 / RAG / Workflow / 插件市场

---

## 13. 后续动作

本文档批准后，调用 `superpowers:writing-plans` skill 产出实施计划：

- `2026-06-17-p1-01-notification-center-plan-01-foundation.md`（Phase 1 骨架）
- `2026-06-17-p1-01-notification-center-plan-02-ops-events.md`（Phase 2 运维事件）

每个 plan 含具体 commit 切分、单测模板、PR 检查清单。**先 Plan 01 合入 + tag `p1-01-foundation-done` 后再启动 Plan 02**。

完成后打 `p1-01-notification-center-done` tag。

---

## 14. 附录 A：与 P0 验收报告的对照

| P0 项 | P1-01 是否影响 | 处理 |
|---|---|---|
| 后端 550 + 1 skipped | 仅在 N 上加 | 动态基线 N ≥ 550 |
| 前端 190/190 | 不影响 | 不动前端测试 |
| E2E 19 + 3 skipped | 不影响 | 不动 E2E（除非加新 spec 测试通知落库） |
| 4 演示场景端到端 | 不破坏 | emit 同步部分不影响 chat/send 响应 |
| L4 拦截 | 强化 | 拦截同时推通知 |
| Prompt Inject 拦截 | 强化 | 拦截同时推通知 |
| L2 二次确认 | 不变 | 通知只说"待确认"，不调 confirm() |
| AuditLog 全链路 | 强化 | 通知记录通过 auditId 联查 |

---

## 15. 附录 B：术语表

| 术语 | 含义 |
|---|---|
| NotificationEvent | 通知事件 POJO，包含 eventType / severity / context |
| NotificationRecord | 通知记录 JPA 实体，每条 (event, channel) 一条 |
| NotificationRecordSummary | API 出口 DTO，不含 requestPayload |
| NotificationChannel | 通知通道接口，WebHook / Feishu 实现 |
| NotificationDispatcher | 调度器，异步执行发送 |
| NotificationService | 入口 API，AgentOrchestrator 调用 |
| NotificationChannelRegistry | 通道注册中心，fail-open 启动 |
| NotificationPayloadSanitizer | payload 脱敏器 |
| CallerRunsPolicy | 线程池满时由调用方线程执行（兜底） |
| Fail-open | 缺配置不抛异常（与 fail-closed 相反） |
| Dry-run | 只 log 不真发（演示 / 测试用） |
