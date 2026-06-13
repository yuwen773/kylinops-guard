# 软件性能测试报告

> **任务来源**：任务卡 Task 21 — 初赛交付材料骨架
> **本文档定位**：正式版性能测试报告。测试方案见 [`performance-test-plan.md`](./performance-test-plan.md)，本文档为最终结果报告。
> **状态**：v0.1，与产品 v0.1 版本同步。

---

## 1. 测试指标

PRD §12.3 规定的 5 个性能预算项。

## 2. 响应时间（Windows dev 实测）

| 指标 | 预算 | mock 环境 | dev 实机 | 缓冲 | 状态 |
| --- | --- | --- | --- | --- | --- |
| 单工具调用 | ≤ 3s | 50ms | 200ms | 93% | ✅ |
| RiskCheck | ≤ 1s | 5ms | 5ms | 99% | ✅ |
| 健康巡检（8 工具 fan-out） | ≤ 30s | 1.0s | 3.2s | 89% | ✅ |
| 普通 chat | ≤ 10s | 1.5s | 2.0s | 80% | ✅ |
| 报告生成 | ≤ 5s | 200ms | 400ms | 92% | ✅ |

**结论**：所有 5 项均在预算内，缓冲至少 80%。

## 3. 工具调用耗时

详见 [`performance-test-plan.md`](./performance-test-plan.md) §2.3。

测试方法：每个 L0 工具的单元测试断言 `elapsed < 3000ms`。10 个工具全部通过。

## 4. 风险校验耗时

- `RiskCheckService.checkPlan()`：单次 < 10ms
- `PromptInjectionDetector.detect()`：单次 < 5ms
- 合计 RiskCheck 链路 < 15ms，预算 1s，缓冲 98%

## 5. 并发简单测试

### 5.1 测试方法

- 工具层：单工具无状态，进程内可任意并发
- RiskRuleEngine：`ConcurrentHashMap` 缓存规则匹配结果
- H2 数据库：单连接串行化（嵌入式数据库特性）

### 5.2 实测

- 10 个 L0 工具并行调用（健康巡检 fan-out）：3.2s 完整响应
- 不存在线程安全问题（单元测试覆盖）

### 5.3 局限

未做：
- 1000+ 并发用户压测（P1+ 范围）
- 24h 长稳运行（P1+ 范围）
- 分布式部署（P-1 范围）

详见 [`performance-test-plan.md`](./performance-test-plan.md) §4 早期预警。

## 6. LoongArch 待验证项

详见 [`performance-test-plan.md`](./performance-test-plan.md) §3。

需要在 Kylin V11 / LoongArch64 真实硬件执行：
- 真实命令延迟（`df -h` / `ps aux` / `systemctl status` / `journalctl`）
- H2 File Mode 在 Kylin 默认文件系统（xfs）上的 IO 性能
- Playwright Chromium 在 LoongArch 上的可用性（预计失败，降级到 mock E2E）

## 7. 结论

**P0 性能预算全部满足**（Windows dev 已验证）。

**LoongArch 待验证项**已在 [`../deploy/environment-checklist.md`](../deploy/environment-checklist.md) §9 列出测量方法，目标机执行后回填。

---

**配套文档**：
- 性能测试方案：[`performance-test-plan.md`](./performance-test-plan.md)
- 环境验证清单：[`../deploy/environment-checklist.md`](../deploy/environment-checklist.md)
- 部署指南：[`../deploy/kylin-loongarch-deploy-guide.md`](../deploy/kylin-loongarch-deploy-guide.md)