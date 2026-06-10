# 开发规范 v0.1

> 麒麟安全智能运维 Agent 开发规范文档

## 1. 代码规范

### 1.1 后端 (Java / Spring Boot)

- 项目使用 **Java 17**，Spring Boot 3.x 及以上
- 包命名：`com.kylinops.{module}`
- 分层：Controller → Service → Repository，Agent 层独立编排
- REST 接口统一使用 `/api/{resource}` 模式
- 所有响应使用 `ApiResponse<T>` 统一封装
- 使用 SLF4J + Lombok `@Slf4j` 记录日志

### 1.2 前端 (Vue 3 / TypeScript)

- 使用 Composition API + `<script setup>`
- TypeScript 严格模式
- 组件命名：PascalCase
- 文件命名：kebab-case
- Pinia 管理状态

### 1.3 命名约定

- 类名：PascalCase
- 方法名：camelCase
- 常量：UPPER_SNAKE_CASE
- 枚举：PascalCase（类），UPPER_SNAKE_CASE（常量）
- 数据库字段：snake_case

## 2. 安全规范

详见 CLAUDE.md §Hard Rules，此处列出关键约束：

1. **不实现原始命令执行端点**
2. **用户输入绝不直接拼接 Shell**
3. **所有 Tool 必须在 ToolRegistry 注册**
4. **RiskCheck 必须在执行前进行**
5. **L3/L4 操作无条件阻断**
6. **Prompt Injection 检查必须先于意图识别**
7. **最小权限执行**
8. **全链路审计**

## 3. 测试规范

- 单元测试覆盖率目标：Service 层 ≥ 80%，Tool 层 ≥ 90%
- 集成测试覆盖所有 REST 端点
- 安全测试覆盖所有 L3/L4 阻断场景
- 使用 JUnit 5 + AssertJ + Mockito

## 4. Git 规范

- commit 格式：`feat|fix|docs|refactor|test|chore: 描述`
- 分支命名：`feat/{task-id}-{description}`
- 禁止直接推送到主分支

## 5. 数据库规范 (H2 → PostgreSQL)

- P0 使用 H2 File Mode，P1 切换 PostgreSQL
- Repository 接口抽象，切换只需修改 datasource URL
- 实体类使用 JPA 注解，字段使用 `@Column` 指定列名
- 时间字段使用 `LocalDateTime`

## 6. API 设计规范

- 版本通过 URL 前缀管理（当前 v1，但不加版本前缀）
- 分页参数统一：`page`（从0开始）、`size`
- 时间参数格式：`yyyy-MM-dd HH:mm:ss`
- 错误码：200（成功）、400（参数错误）、403（无权限）、404（不存在）、500（内部错误）
