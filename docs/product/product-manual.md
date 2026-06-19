# 软件产品说明书

> **任务来源**：任务卡 Task 21 — 初赛交付材料骨架
> **本文档定位**：面向最终用户的**操作手册**。
> **状态**：v0.1，与产品 v0.1 版本同步。

---

## 1. 产品介绍

**麒麟安全智能运维 Agent (KylinOps Guard / 麒麟智维盾)** —— 面向麒麟 Advanced Server V11 / LoongArch64 的安全可控智能运维平台。

通过自然语言完成系统巡检、问题诊断、安全处置。**不是聊天机器人**，**不是任意命令执行器**。所有 OS 访问通过 MCP-style OpsTool 抽象，所有操作经 RiskCheck 校验，所有执行经审计追溯。

## 2. 功能说明

| 功能 | 入口 | 说明 |
| --- | --- | --- |
| 自然语言对话 | ChatConsole | 输入中文指令，Agent 自主规划工具调用 |
| 系统健康巡检 | ChatConsole「系统健康巡检」按钮 | 8 工具并行 fan-out，≤ 30s |
| 磁盘诊断 | ChatConsole「磁盘空间分析」按钮 | 根因 + 安全清理建议 |
| 服务诊断 + L2 确认 | ChatConsole「服务状态诊断」按钮 | 3 工具诊断 + 黄色确认卡片 |
| 危险命令拦截 | （自动） | L4 BLOCK + 安全替代建议 |
| Prompt Injection 防护 | （自动） | 注入 + 命令 = L4 BLOCK |
| 审计追溯 | AuditLog 页面 | 完整 auditId 链路回放 |
| 报告生成 | ReportCenter | 5 类 Markdown 报告 |
| 工具目录 | ToolCenter | 10 个 L0 工具元数据 |
| 安全中心 | SecurityCenter | 风险规则 + 拦截事件 |
| 通知中心 | NotificationSettings | 飞书 / Webhook 通道配置 + 连接测试 + 卡片模板（详见 §4.7） |

## 3. 使用流程

### 3.1 标准使用流程

```
1. 启动系统: bash deploy/scripts/start-backend.sh + start-frontend.sh
2. 浏览器打开 http://localhost:5173
3. ChatConsole 输入运维问题（如「检查系统健康」）
4. Agent 自动调用工具，返回结果
5. 如涉及执行：前端弹出 L2 确认卡片 → 用户点击确认 → SafeExecutor 执行
6. 进入 AuditLog 查看完整链路
```

### 3.2 演示录像流程（6:30）

详见 [`../demo/demo-script-v0.1.md`](../demo/demo-script-v0.1.md) + [`../../演示视频脚本%20v0.1.md`](../../演示视频脚本%20v0.1.md)。

4 段核心演示：
- 00:55 – 02:00 系统健康巡检
- 02:00 – 03:25 磁盘异常分析
- 03:25 – 04:35 服务诊断 + L2 确认
- 04:35 – 05:45 危险命令 + Prompt Injection 拦截

## 4. 页面说明

### 4.1 ChatConsole（智能运维对话台）

- 5 个快捷按钮：系统健康巡检 / 磁盘空间分析 / 服务状态诊断 / 危险命令拦截 / Prompt Inject 测试
- 工具调用过程卡片
- 风险等级标签（L0–L4）
- L2 执行确认卡片
- 审计编号展示
- 生成报告按钮

### 4.2 Dashboard（系统状态总览）

6 项系统指标卡片（CPU / 内存 / 磁盘 / 服务异常 / 错误日志 / 在线工具数）+ 健康评分

### 4.3 ToolCenter（MCP 工具中心）

10 个 L0 工具的元数据 + 调用统计

### 4.4 SecurityCenter（安全护栏中心）

风险规则目录 + BLOCK 事件列表

### 4.5 AuditLog（审计日志中心）

按 riskLevel / status / keyword / 时间筛选 + 详情回放

### 4.6 ReportCenter（报告中心）

5 类 Markdown 报告 + 详情查看

### 4.7 NotificationSettings（通知配置）

**入口**：左侧菜单「通知配置」(`/notification-settings`)，仅 admin 角色可见。

**功能分区**：

1. **全局设置**
   - **启用通知**：总开关。关闭后所有通知事件仅记录到 `notification_records` 表，不实际发送（便于回放 / 调试）。
   - **Dry-Run 模式**：开启后通知按真实链路发送，但显式标记为演练（`dryRun=true`），便于回归验证。
   - **配置版本**：乐观锁版本号，所有写操作均通过版本号保证一致性。

2. **通知通道**
   - 支持 **Webhook**（通用 POST JSON + HMAC-SHA256 签名）和 **飞书**（timestamp + sign 签名 + interactive 卡片）两种类型。
   - **新建通道**：填写 ID、名称、URL、密钥（飞书必填，Webhook 可选）、订阅事件类型、是否启用、是否 Dry-Run、超时。
   - **连接测试**：点击「测试」按钮发送一条 `TEST` 类型事件，结果记录到 `notification_records`。
   - **密钥不回显**：编辑时留空表示保持原值；密钥在 DB 中以 **AES-256-GCM 信封加密** 存储，主密钥由部署侧 `KYLINOPS_NOTIFICATION_MASTER_KEY` 注入。

#### 4.7.1 飞书卡片字段说明

触发 5 类事件时，飞书通道会发送一张 interactive 卡片，包含以下信息：

| 区块 | 内容 | 何时显示 |
| --- | --- | --- |
| **Header** | 配色（红=CRITICAL / 橙=WARNING / 蓝=INFO / 灰=无）+ 短标题（"高风险操作已阻断" / "Prompt 注入拦截" / "待确认操作" / "服务异常" / "磁盘风险" / "通道连接测试"） | 总是 |
| **事件 + 时间**（双列） | 左：事件类型（如 "L4 阻断"）；右：发生时间 | 总是 |
| **摘要** | 风险结论 / 健康评分 / 关键发现 | 非 TEST 事件 |
| **关键对象** | 注入模式 / 命中规则 / 服务名 / 磁盘路径+使用率（按优先级命中其一） | 对应字段非空 |
| **审计 ID** | `aud-xxx` 编号 | 非 TEST 且 auditId 存在 |
| **「查看审计详情」按钮** | 跳转到 `KYLINOPS_PUBLIC_BASE_URL + /audit?auditId=xxx` | 非 TEST **且** 公网 URL 已配置 |

#### 4.7.2 「查看审计详情」按钮前置条件

> **重要**：飞书卡片里的「查看审计详情」按钮**只在部署侧配置了 `KYLINOPS_PUBLIC_BASE_URL` 时才出现**。
>
> - **缺省行为**：启动期日志 warn 一次（`publicBaseUrl is not configured or invalid; audit links will be omitted from notification cards`），按钮静默丢弃。
> - **生产部署**：必须配为飞书用户能访问的公网 https URL（如 `https://kylin-ops.example.com`），按钮才能在手机上点击跳转。
> - **dev / 演示**：可不配（手机点 `localhost` 必败）。若用 ngrok / frp 临时暴露后端做演示，需配为公网隧道地址。
> - **校验规则**：必须是 `http://` 或 `https://` 开头、必须含 host、禁止 `userinfo`（如 `user:pwd@host`）。
>
> 详见 [`../deploy/install-and-deploy-guide.md`](../deploy/install-and-deploy-guide.md) §2.4。

## 5. 常见问题（用户视角）

### Q1: Agent 不回复 / 回复很慢
- 检查后端是否启动：`curl http://localhost:8080/api/health`
- 检查浏览器 DevTools → Network → 是否有失败请求
- 检查 LLM API 连通（如启用）

### Q2: 快捷按钮点击没反应
- 检查前端是否在 5173 端口监听
- 浏览器 console 是否有报错
- 必要时硬刷新（Ctrl+Shift+R）

### Q3: 危险命令没被拦截
- 这是**绝对不应该发生**的情况——立即记录 auditId → 报告开发团队
- 当前所有 L4 阻断测试已 100% 通过（[`../test/security-test-cases.md`](../test/security-test-cases.md) §1）

### Q4: L2 确认卡片点了确认但没执行
- 检查 PendingAction 状态：`curl http://localhost:8080/api/actions/pending`
- 确认 SafeExecutor 是否有 root 权限（service_restart 需要）

### Q5: 数据没保存
- 检查 `data/kylinops.mv.db` 文件是否存在
- 检查 `application.yml` 的 `spring.datasource.url` 是否指向正确路径

### Q6: 飞书卡片里没有「查看审计详情」按钮
- 这是**预期行为**，不是 bug。说明部署侧未配置 `KYLINOPS_PUBLIC_BASE_URL`。
- 进入 [`../deploy/install-and-deploy-guide.md`](../deploy/install-and-deploy-guide.md) §2.4 按生产场景配公网 URL，重启后端。
- 启动期日志会有一行 warn：`publicBaseUrl is not configured or invalid; audit links will be omitted from notification cards`，据此可一键定位。

### Q7: 通知通道「测试」按钮报错 / 飞书一直收不到
- 检查通道的 `enabled = true` 且全局「启用通知」开关已打开（否则只记录不发送）。
- 检查通道订阅的事件类型是否覆盖你要测的事件（默认全选）。
- 检查通道密钥：飞书 secret 与 webhook URL 是否匹配飞书机器人的「自定义机器人」设置。
- 查看 `notification_records` 表的 `errorMessage` / `responseBody` 字段定位具体失败原因。

---

**配套文档**：
- 架构详细设计：[`../design/software-design-document.md`](../design/software-design-document.md)
- 部署指南：[`../deploy/kylin-loongarch-deploy-guide.md`](../deploy/kylin-loongarch-deploy-guide.md)
- 演示场景：[`../test-scenarios/`](../test-scenarios/)

**维护责任**：Phase 4 / Task 21；UI/UX 重大变更触发版本升级到 v0.2。