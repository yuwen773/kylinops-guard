# P1-01 Notification Center 管理闭环设计

> 日期：2026-06-18
> 状态：已批准，等待实施计划
> 前置实现：`2026-06-17-p1-01-notification-center-design.md`

## 1. 背景与目标

P1-01 已完成通知发送主链路：L4 BLOCK 等事件可经 `NotificationService` 和通道实现发送到飞书或通用 Webhook，发送结果写入 `notification_records`，审计详情 API 已返回 `notificationRecords`。

本次补齐管理与展示闭环：

1. 新增通知配置页面，支持全局设置和 FEISHU / WEBHOOK 通道完整 CRUD，保存后立即生效且重启后保留。
2. 新增测试连接能力，使用表单当前值发送固定测试通知，并保留最近测试记录。
3. 在审计详情中展示正式通知记录。
4. 优化飞书卡片为精简告警卡，并提供审计详情跳转。

不在本次范围内：

- 钉钉、企业微信、邮件通道。
- 按事件类型或风险等级配置推送规则。
- 重试策略、通知聚合、静默时段、模板编辑器。
- 全量通知历史中心；配置页只展示最近 20 条测试记录。

## 2. 已确认决策

| 主题 | 决策 |
|---|---|
| 持久化 | 规范化数据库模型，数据库成为首次导入后的唯一运行时配置来源 |
| 初始迁移 | 配置表为空时从 YAML 自动导入一次，之后不再与 YAML 合并 |
| 生效方式 | 保存成功后立即替换运行时不可变配置快照，无需重启 |
| 通道管理 | 支持新增、编辑、启停和软删除多个 FEISHU / WEBHOOK 通道 |
| Secret API | 已保存 Secret 永不回传；只返回 `secretConfigured` |
| Secret UI | 已保存值显示占位符；眼睛按钮只显示用户本次新输入 |
| Secret 存储 | 使用环境主密钥进行认证加密；密钥缺失或无法解密时启动失败 |
| 测试连接 | 使用表单当前值，不要求先保存；成功和失败都落库并标记为测试记录 |
| 审计展示 | 审计详情使用独立“通知记录”标签页 |
| 页面布局 | 全局总览 + 通道卡片；新增/编辑使用抽屉；底部显示最近 20 条测试记录 |
| 飞书卡片 | 精简告警卡，增加“查看审计详情”按钮 |
| 公开地址 | 使用部署环境变量 `KYLINOPS_PUBLIC_BASE_URL` |

## 3. 架构

### 3.1 组件边界

新增 `NotificationConfigurationService`，作为运行时配置的唯一入口，职责包括：

- 读取和更新数据库配置。
- 创建、更新、启停和软删除通道。
- 加密写入、解密读取 Secret。
- 在首次启动时执行 YAML 导入。
- 生成供 `NotificationService` / `NotificationDispatcher` 使用的不可变快照。

现有 `NotificationConfig` 保留为 YAML 导入源，不再由发送链路直接读取。现有通道实现继续接收 `ChannelConfig`，不引入新的通道抽象。

```text
管理页面
  -> NotificationManagementController
  -> NotificationConfigurationService
  -> notification_settings / notification_channels
  -> AtomicReference<RuntimeNotificationConfig>
  -> NotificationService / NotificationDispatcher

首次启动且配置表为空
  -> NotificationConfig(YAML/env)
  -> NotificationConfigurationService.importOnce()
  -> 数据库
  -> 运行时快照
```

### 3.2 一致性策略

所有配置写操作在服务级串行化，并使用数据库乐观锁防止跨实例覆盖：

1. 校验请求并在内存中构造候选快照。
2. 在事务中写入数据库并提交。
3. 提交成功后，以不可失败的原子引用替换候选快照。

候选快照的校验、Secret 解密和通道映射均在事务提交前完成，因此“刷新”只剩 `AtomicReference.set`。数据库提交失败时不会替换快照；前端并发版本冲突返回 409。

多应用实例不在本次范围内。本设计按当前单实例部署工作；未来多实例需增加配置变更广播或轮询。

## 4. 数据模型

### 4.1 `notification_settings`

单行全局设置：

| 字段 | 含义 |
|---|---|
| `id` | 固定主键 |
| `enabled` | 全局开关 |
| `dry_run` | 只记录、不真实发送 |
| `version` | JPA 乐观锁版本 |
| `created_at` / `updated_at` | 审计时间 |

### 4.2 `notification_channels`

| 字段 | 含义 |
|---|---|
| `channel_id` | 全局唯一且永久不复用 |
| `channel_type` | `FEISHU` 或 `WEBHOOK` |
| `enabled` | 通道开关 |
| `url` | Webhook URL |
| `encrypted_secret` | 带版本前缀的认证加密结果 |
| `timeout_ms` | 请求超时 |
| `deleted_at` | 非空表示软删除 |
| `version` | JPA 乐观锁版本 |
| `created_at` / `updated_at` | 审计时间 |

软删除后该通道不再进入运行时快照，但历史 `notification_records` 保留原 `channelId` 和 `channelType`。删除后的 ID 不允许重新创建。

### 4.3 测试通知记录

为 `NotificationEventType` 增加 `TEST`，并在 `notification_records` / `NotificationRecord` 增加 `event_type` 字段：

- 新产生的正式记录写入实际事件类型。
- 测试记录写入 `TEST`。
- 历史记录无法可靠反推事件类型，迁移后允许保持 `null`。
- 增加 `(event_type, created_at)` 索引，支持最近测试记录查询。

每次测试生成新的 `eventId`，因此现有 `(event_id, channel_id)` 唯一约束仍成立。

测试记录：

- `auditId = null`。
- `eventType = TEST`。
- `channelId` 使用已保存通道 ID；未保存的新通道使用服务端生成的 `test-draft-<short-id>`，仅用于该条记录。
- 状态沿用 `SENT` / `FAILED`。
- 不保存明文 Secret、未脱敏 payload 或完整响应正文。

数据库迁移必须确认 `notification_records.audit_id` 可空；若当前约束为非空，则在同一迁移中放宽。

## 5. Secret 安全设计

主密钥从 `KYLINOPS_NOTIFICATION_MASTER_KEY` 读取，约定为 Base64 编码的 32 字节随机值。Secret 使用 AES-256-GCM：

- 每次写入生成独立随机 nonce。
- 密文包含格式版本、nonce 和认证密文。
- 不允许确定性加密或复用 nonce。
- API、日志和异常不得输出明文 Secret 或密文。

启动规则：

- 数据库存在加密 Secret 时，主密钥缺失、格式错误或任一 Secret 无法认证解密，应用启动失败并输出不含敏感值的明确原因。
- 首次 YAML 导入包含非空 Secret 时，必须存在有效主密钥，否则启动失败。
- 数据库无加密 Secret 且首次导入也无 Secret 时，允许无主密钥启动；但创建或更新非空 Secret 时返回配置错误。

管理 API 永不返回已保存 Secret。更新语义：

- `secret` 非空：替换为新 Secret。
- `secret` 为空或缺省：保留已有 Secret。
- `clearSecret=true`：明确清除；FEISHU 因 Secret 必填而拒绝该操作，WEBHOOK 允许。

## 6. 管理 API

所有接口沿用现有管理员会话保护和统一 `ApiResponse`。

### 6.1 全局设置

`GET /api/notification/settings`

返回：

- `enabled`
- `dryRun`
- `version`
- 未软删除通道列表
- 每个通道的 `version` 和 `secretConfigured`，不返回 Secret
- 每个通道的 `lastTestResult`；后端按通道查询其最新一条 `eventType=TEST` 记录，不从全局最近 20 条中推导

`PUT /api/notification/settings`

请求包含 `enabled`、`dryRun`、`version`。版本冲突返回 409。

### 6.2 通道 CRUD

- `POST /api/notification/channels`
- `PUT /api/notification/channels/{channelId}`
- `DELETE /api/notification/channels/{channelId}?version=<version>`

创建响应返回初始 `version`。更新请求体必须携带当前 `version`，更新响应返回递增后的版本。删除通过查询参数携带当前版本。版本不匹配统一返回 409。

创建和更新校验：

- `channelId` 符合受限字符和长度规则，且从未使用。
- 类型只能是当前已实现的 FEISHU / WEBHOOK。
- URL 必须是合法绝对 `http` 或 `https` URI，禁止 URI user-info。
- 不禁止内网地址，因为通用 Webhook 需要支持企业内网 SOC；接口仅对管理员开放。
- `timeoutMs` 限制为 500～30000ms。
- FEISHU 必须具有 Secret；WEBHOOK Secret 可选。

删除是软删除。已删除通道的更新、测试和再次删除返回 404。

### 6.3 测试连接

`POST /api/notification/channels/test`

请求使用抽屉表单当前值：

- 新通道：必须提交发送所需 URL 和 Secret。
- 已保存通道：可提交 `channelId`；Secret 留空时由后端读取已保存值。
- 已保存 WEBHOOK 通道提交 `clearSecret=true` 时，本次测试按“无签名 Secret”发送，但不修改数据库；FEISHU 不允许清除 Secret。
- 未保存的 URL、超时和 Secret 只用于本次测试，不自动保存。

响应返回测试记录摘要，包括状态、响应码、脱敏错误和时间。外部发送失败属于有效测试结果，接口返回业务成功响应并携带 `FAILED`；请求格式或配置校验错误返回 400。

`GET /api/notification/test-records?limit=20`

- 只返回 `eventType=TEST` 的记录。
- `limit` 服务端限制在 1～20，默认 20。
- 按 `createdAt` 倒序。

通道卡片的 `lastTestResult` 与此列表是两个独立查询语义：前者保证每个通道都能取得自己的最后一次测试，后者只用于页面底部的全局最近记录。

## 7. 前端设计

### 7.1 路由与导航

新增：

- 侧栏入口“通知中心”。
- 受保护路由 `/notifications`。
- 页面目录、API 模块和类型定义沿用现有 Vue 3 + Element Plus 组织方式。

### 7.2 页面结构

页面采用“总览 + 通道卡片”：

1. 顶部全局设置卡：通知开关、dry-run、显式保存按钮和运行状态。
2. 通道卡片区：展示类型、ID、启停、URL、Secret 是否已配置、最后测试结果。
3. 通道操作：新增、编辑、测试、启停、删除。
4. 页面底部：最近 20 条测试记录。

新增和编辑使用抽屉，避免主页面出现大表单。每项异步操作使用独立 loading 状态，测试连接不阻塞整个页面。

### 7.3 Secret 交互

- 编辑已有通道时显示 `••••••` 和“已配置”，不把占位符提交给后端。
- 用户输入新 Secret 后，可通过眼睛按钮临时切换本次输入的显示状态。
- 清除 Secret 必须通过明确操作并二次确认。
- 关闭抽屉时清空前端内存中的新 Secret。

### 7.4 测试连接

测试按钮使用当前表单值，不要求先保存。页面展示：

- 发送中。
- 成功：通道、响应码和时间。
- 失败：响应码和脱敏错误。

测试完成后刷新最近测试记录，并更新对应已保存通道卡片的最后测试结果。

## 8. 审计详情通知记录

前端 `AuditLogDetail` 类型增加 `notificationRecords` 及对应摘要类型，消费后端已有字段。

审计详情抽屉新增独立“通知记录”标签页，展示：

- 通道 ID 和类型。
- `SENT` / `FAILED` / `SKIPPED` 状态。
- 响应码。
- 发送时间或创建时间。
- 重试次数。
- 脱敏错误信息。

不展示 `requestPayload` 或 `responseBody`。无记录时显示明确空状态。测试记录没有 `auditId`，不会出现在审计详情中。

## 9. 飞书精简卡

正式告警卡结构：

- 标题：风险等级 + 简短事件名称，如“高风险操作已阻断”。
- 低饱和度严重级别颜色。
- 事件摘要。
- 关键对象：安全事件为命中规则摘要，服务事件为服务名，磁盘事件为路径和使用率。
- 发生时间。
- 审计 ID。
- “查看审计详情”按钮。

按钮 URL：

```text
${KYLINOPS_PUBLIC_BASE_URL}/audit?auditId=<url-encoded-audit-id>
```

`KYLINOPS_PUBLIC_BASE_URL`：

- 去除末尾 `/` 后使用。
- 缺失或格式非法时不生成按钮，但通知仍可发送。
- 只接受 `http` / `https` 绝对地址。

测试卡复用同一视觉语言，标题明确为“测试通知”，不附审计 ID 和跳转按钮。

卡片内容继续经过现有脱敏器和长度限制，不展示原始命令、完整工具输出或完整响应。

## 10. 错误处理

- 乐观锁冲突：返回 409，前端提示配置已被修改并要求刷新。
- 配置校验失败：返回 400，并定位到具体字段。
- 测试发送失败：记录 `FAILED`，不修改已保存配置。
- 通知通道不可达：不影响业务主链路。
- 停用或删除通道：新快照立即排除该通道，历史记录不变。
- YAML 导入：只在设置表和通道表均为空时执行，并在单事务内完成，保证幂等。
- 主密钥错误：满足第 5 节失败条件时阻止应用启动，禁止静默禁用或回退 YAML。

## 11. 测试策略

### 11.1 后端

- 数据库迁移和实体约束。
- AES-GCM 加密往返、随机 nonce、错误主密钥和篡改密文。
- Secret 不进入 API、日志和异常。
- YAML 首次导入、空库幂等和导入后数据库独占。
- 全局设置及通道 CRUD、软删除、ID 不复用。
- 乐观锁冲突返回 409。
- 保存后运行时快照立即生效。
- FEISHU / WEBHOOK 字段校验。
- 测试连接成功和失败均落库，且 `auditId` 为空、`eventType=TEST`。
- 最近测试记录只返回 TEST 且最多 20 条。
- 飞书精简卡字段、严重级别样式、审计链接和测试卡。

### 11.2 前端

- 全局设置读取、保存及冲突提示。
- 通道新增、编辑、启停和软删除。
- 已保存 Secret 不回显、不误提交；新输入可临时显示。
- 测试连接独立 loading、成功和失败反馈。
- 最近测试记录刷新和空状态。
- 审计详情通知记录标签页、状态映射和失败信息。
- 路由与侧栏入口。

### 11.3 E2E

使用本地 mock Webhook / 飞书端点，禁止测试依赖真实外网：

1. 创建飞书通道并保存。
2. 使用表单当前值测试连接。
3. 验证测试记录写入并在页面显示。
4. 启用通知中心。
5. 触发 L4 BLOCK。
6. 验证 mock 端点收到精简卡。
7. 验证审计详情显示对应 `SENT` 记录。

## 12. 验收标准

- 管理员可在 `/notifications` 完成全局设置及 FEISHU / WEBHOOK 通道 CRUD。
- 保存后无需重启即可影响下一次通知分发，重启后配置仍存在。
- 已保存 Secret 不通过任何读取 API 返回，数据库只存认证密文。
- 有加密数据时，主密钥缺失或错误会阻止启动。
- 首次启动可导入现有 YAML 通道，之后运行时只读取数据库。
- 测试连接可使用未保存表单值，结果落库并出现在最近 20 条记录中。
- 正式通知记录在审计详情独立标签页展示。
- 飞书收到精简卡，配置了公开地址时可跳转到对应审计详情。
- 新增测试全部通过，现有后端、前端和 E2E 基线无回归。

## 13. 实施边界

实施应保持改动聚焦：

- 不修改现有通知触发条件。
- 不重构现有通道接口或异步分发机制，除非适配运行时配置入口所必需。
- 不增加 P1-02 通道。
- 不增加规则 DSL、模板编辑器或完整通知历史页面。
- 不清理与本任务无关的现有代码或工作区改动。
