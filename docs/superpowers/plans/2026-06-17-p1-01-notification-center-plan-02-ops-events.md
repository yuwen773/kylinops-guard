# P1-01 Plan 02：SERVICE_ABNORMAL / DISK_RISK 触发

> **状态**：⏳ 待实施（Plan 01 合入 + tag `p1-01-foundation-done` 后启动）
> **版本**：v0.1
> **日期**：2026-06-17
> **来源 Spec**：[2026-06-17-p1-01-notification-center-design.md](../specs/2026-06-17-p1-01-notification-center-design.md)
> **前置依赖**：Plan 01 合入 + tag `p1-01-foundation-done`（通道层、调度层、安全类事件已就绪）

---

## 概述

在 Plan 01 骨架基础上，新增 2 个运维类事件触发点：`SERVICE_ABNORMAL` 和 `DISK_RISK`。核心工作是：

1. 集中运维类触发条件判断逻辑（`NotificationTriggerEvaluator` 或 AgentOrchestrator helper）
2. 在 AgentOrchestrator 的 Step 7.5 之后插入 emit 调用
3. Phase 2 测试

**架构要点**：
- NotificationTriggerEvaluator 集中判断触发条件（不散落在多个 switch case 中）
- 只在 RCA 存在时触发；SERVICE_ABNORMAL 以 confidence ≥ 0.7 为主门槛挡住误报；DISK_RISK 在 confidence 不达标时仍可凭磁盘 ≥ 85% 触发
- 使用 Plan 01 已实现的 NotificationEventFactory 构造事件

---

## 任务拆分

### Task 1：NotificationTriggerEvaluator（或 AgentOrchestrator private helper）

**目的**：集中运维类触发条件的判断逻辑，避免散落在 AgentOrchestrator 的多个分支里。

**优先实现方案**：独立 `@Component` 类
```java
@Component
public class NotificationTriggerEvaluator {

    public boolean shouldEmitServiceAbnormal(IntentType intent, RootCauseChain rca, List<ToolResult> toolResults) {
        if (intent != IntentType.SERVICE_DIAGNOSIS) return false;
        if (rca == null) return false;
        if (rca.getConfidence() < 0.7) return false;
        return extractServiceName(rca, toolResults).isPresent();
    }

    public boolean shouldEmitDiskRisk(IntentType intent, RootCauseChain rca, List<ToolResult> toolResults) {
        if (intent != IntentType.DISK_DIAGNOSIS) return false;
        if (rca == null) return false;                       // 严格：rca null 一定不触发（避免 raw 用量触发空结论）
        if (rca.getConfidence() >= 0.7) return true;
        Optional<Double> usage = extractDiskUsagePercent(toolResults);
        return usage.isPresent() && usage.get() >= 85.0;
    }

    public Optional<Double> extractDiskUsagePercent(List<ToolResult> toolResults) { ... }
    public Optional<String> extractDiskPath(RootCauseChain rca, List<ToolResult> toolResults) { ... }
    public Optional<String> extractServiceName(RootCauseChain rca, List<ToolResult> toolResults) { ... }
}
```

**降级方案**（如果独立类成本过高）：集中到 AgentOrchestrator 的一个 private helper 区域，不允许散落在多个 switch case 中。

**关键不变量（最终口径）**：
- RootCauseChain 为 null 时 SERVICE_ABNORMAL / DISK_RISK 都不触发
- `SERVICE_ABNORMAL` 触发条件：`intent == SERVICE_DIAGNOSIS` 且 `rca != null` 且 `rca.confidence >= 0.7` 且 `serviceName` 可提取
- `DISK_RISK` 触发条件：`intent == DISK_DIAGNOSIS` 且 `rca != null`，且满足 `rca.confidence >= 0.7` **或** `diskUsagePercent >= 85.0` 任一
- RCA confidence < 0.7 时：
  - SERVICE_ABNORMAL 不触发
  - DISK_RISK 若 `diskUsagePercent >= 85.0` 仍触发（凭用量门槛触发，RCA 不必高 confidence）

---

### Task 2：AgentOrchestrator 集成（2 个新 emit 点）

**修改文件**：

| 文件 | 操作 |
|---|---|
| `AgentOrchestrator.java` | Step 7.5 之后新增 2 个 emit 调用 |

**触发位置**：`process()` Step 7.5（RCA 已生成）之后

```java
// Step 7.5 之后 — RCA 已完成
if (notificationTriggerEvaluator.shouldEmitServiceAbnormal(intent, rootCauseChain, toolResults)) {
    String serviceName = notificationTriggerEvaluator
            .extractServiceName(rootCauseChain, toolResults)
            .orElseThrow(); // 已由 shouldEmitServiceAbnormal 保证存在
    NotificationEvent event = notificationEventFactory.serviceAbnormal(
            auditId, sessionId, serviceName,
            rootCauseChain.getConfidence(), rootCauseChain.getConclusion());
    notificationService.emit(event);
}

if (notificationTriggerEvaluator.shouldEmitDiskRisk(intent, rootCauseChain, toolResults)) {
    String diskPath = notificationTriggerEvaluator
            .extractDiskPath(rootCauseChain, toolResults)
            .orElse("/"); // 可缺失，缺省用根路径
    Double diskUsage = notificationTriggerEvaluator
            .extractDiskUsagePercent(toolResults)
            .orElse(null);
    NotificationEvent event = notificationEventFactory.diskRisk(
            auditId, sessionId, diskPath, diskUsage,
            rootCauseChain.getConfidence(), rootCauseChain.getConclusion());
    notificationService.emit(event);
}
```

**关键不变量**：
- emit() 在 process() 的 try 块内，异常被外层 catch
- 不修改 RiskRuleEngine / PromptInjectionDetector
- 不修改 NotificationService / Dispatcher / Channel 层（Plan 01 已实现）
- Evaluator 提取逻辑自包含，不复用 `AgentOrchestrator.extractServiceName(ToolPlan)`（签名不匹配）

---

### Task 3：Phase 2 测试

**新增测试类**：

| # | 测试类 | 覆盖范围 |
|---|---|---|
| 1 | `ServiceDiskAbnormalNotificationTest` | SERVICE_ABNORMAL / DISK_RISK 触发条件：SERVICE_ABNORMAL (RCA confidence ≥ 0.7 + serviceName 可提取)、DISK_RISK (RCA confidence ≥ 0.7 或 磁盘 ≥ 85%)、RCA null 不触发、confidence < 0.7 但磁盘 ≥ 85% DISK_RISK 仍触发、serviceName 不可提取时不触发 SERVICE_ABNORMAL、diskPath 不可提取时 DISK_RISK 仍用 "/" 兜底 |

**测试要点**：
- @SpringBootTest + mock RCA
- 验证 factory 构造的事件字段正确
- 验证 NotificationTriggerEvaluator 各条件分支
- 验证 SERVICE_ABNORMAL 在 RCA confidence < 0.7 时不 emit
- 验证 DISK_RISK 在 RCA confidence < 0.7 但磁盘 ≥ 85% 时仍 emit（口径关键正向用例）
- 验证 RootCauseChain null 时不 emit（SERVICE_ABNORMAL / DISK_RISK 均不触发）
- 验证 serviceName 不可提取时 SERVICE_ABNORMAL 不 emit
- 验证 diskPath 不可提取时 DISK_RISK 仍 emit，diskPath 字段为 "/"

---

## PR 检查清单

```text
□ 后端 mvn test -q → Tests run ≥ 550 + N1 + N2, Failures=0, Errors=0, Skipped ≤ 1
□ 前端 npm run test:unit -- --run → 190/190 passed, failed=0（不影响前端）
□ E2E npx playwright test → 19/19 + 3 skipped, failed=0（不影响 E2E）
□ SERVICE_DIAGNOSIS + RCA 存在 + confidence ≥ 0.7 + serviceName 可提取 → 触发 SERVICE_ABNORMAL
□ DISK_DIAGNOSIS + RCA 存在 + (confidence ≥ 0.7 或 磁盘 ≥ 85%) → 触发 DISK_RISK
□ RootCauseChain 为 null 时 SERVICE_ABNORMAL / DISK_RISK 都不触发
□ RCA confidence < 0.7 时 SERVICE_ABNORMAL 不触发
□ RCA confidence < 0.7 时 DISK_RISK 若 diskUsagePercent ≥ 85% 仍触发（关键正向用例）
□ serviceName 不可提取时 SERVICE_ABNORMAL 不 emit（避免"未知服务异常"虚警）
□ diskPath 不可提取时 DISK_RISK 仍 emit，diskPath 字段为 "/"
□ 不修改 RiskRuleEngine / PromptInjectionDetector（P0 安全基线）
□ 触发规则集中在 NotificationTriggerEvaluator（或 helper）一个区域
□ Evaluator 提取逻辑自包含，不复用 AgentOrchestrator.extractServiceName(ToolPlan)
□ Plan 01 全部验收标准仍通过
```

---

## 提交切分建议

```bash
# Task 1: Evaluator
git commit -m "feat(notification): add NotificationTriggerEvaluator for ops events"

# Task 2-3: Orchestrator integration + tests
git commit -m "feat(notification): integrate SERVICE_ABNORMAL and DISK_RISK emit points"
```

---

## 后续

合入后打 tag：

```bash
git tag p1-01-notification-center-done
```

P1-01 告警通道全部完成。
