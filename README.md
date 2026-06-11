# 麒麟安全智能运维 Agent (KylinOps Guard / 麒麟智维盾)

> **面向麒麟 Advanced Server V11 (LoongArch64) 的安全可控智能运维 Agent**

## 项目简介

一款**安全可控**的操作系统智能运维 Agent。它不是普通聊天机器人，也不是任意命令执行器，而是一个面向麒麟操作系统的、**以安全护栏为核心竞争力**的运维智能体。

### 安全闭环

```
自然语言输入 → Agent 意图识别 → MCP-style Tool 规划
    → 已注册 Tool 调用 → OS 实时感知 → 安全风险校验 (RiskCheck)
    → 最小权限执行 (SafeExecutor) → 审计日志 (AuditLog) → 报告生成
```

### 核心原则

- 所有 OS 操作必须通过注册的 OpsTool 封装
- 所有 Tool 必须声明 riskLevel 和 permissionType
- L2（中等风险）操作必须用户确认后才执行
- L3/L4（高风险）操作直接阻断
- Prompt 注入检测优先于任何意图识别
- 不允许为了 Demo 绕过安全逻辑

## 技术栈

| 层 | 技术选型 |
|---|---|
| 前端 | Vue 3 + TypeScript + Vite + Element Plus + Axios |
| 后端 | Java 17 + Spring Boot 3.x + Maven |
| 数据库 | H2 File Mode (P0) / PostgreSQL (P1) |
| Agent | 自研轻量 AgentOrchestrator |
| MCP | 内部 MCP-style Tool Registry |
| OS 感知 | 受控 Linux 命令白名单封装 |
| 安全 | RiskRuleEngine + PromptInjectionDetector |
| 审计 | AuditLogService |

## 项目结构

```
kylin-ops/
├── backend/                    # Spring Boot 后端
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/kylinops/
│   │   │   │   ├── KylinOpsApplication.java
│   │   │   │   ├── common/          # 通用响应、异常处理、枚举
│   │   │   │   ├── config/          # 应用配置
│   │   │   │   ├── chat/            # 聊天接口 (ChatController + ChatService)
│   │   │   │   ├── agent/           # Agent 编排 (IntentClassifier, ToolPlanningService, AgentOrchestrator)
│   │   │   │   ├── tool/            # Tool 注册与管理 (OpsTool, ToolRegistry, ToolExecutor)
│   │   │   │   ├── os/              # OS 感知工具 (8 个只读工具, L0)
│   │   │   │   ├── security/        # 安全风险校验 (PromptInjectionDetector, RiskCheckService)
│   │   │   │   ├── executor/        # 安全执行器与确认流程
│   │   │   │   ├── audit/           # 审计日志 (AuditLogService, AuditLog)
│   │   │   │   ├── report/          # 报告生成 (TODO)
│   │   │   │   └── dashboard/       # 仪表盘 (TODO)
│   │   │   └── resources/
│   │   │       ├── application.yml
│   │   │       └── application-h2.yml
│   │   └── test/
│   │       └── java/com/kylinops/
│   └── pom.xml
├── frontend/                   # Vue 3 前端 (TODO)
├── deploy/
│   └── scripts/
│       ├── check-env.sh        # 环境检查
│       ├── start-backend.sh    # 后端启动
│       └── start-frontend.sh   # 前端启动
├── *.md                        # 产品规格/架构设计文档
└── README.md                   # 本文件
```

## 快速开始

### 环境要求

- Java 17+
- Maven 3.8+
- Node.js 18+ (前端开发)

### 编译与运行

```bash
# 1. 环境检查
bash deploy/scripts/check-env.sh

# 2. 编译后端
cd backend && mvn clean package -DskipTests

# 3. 启动后端
java -jar backend/target/kylin-ops-guard.jar

# 4. 验证
curl http://localhost:8080/api/health
```

### 验证结果示例

```json
{
  "code": 200,
  "message": "服务运行正常",
  "data": {
    "status": "UP",
    "service": "kylin-ops-guard",
    "version": "0.1.0",
    "timestamp": "2026-06-10T12:00:00Z",
    "jvm": { "...": "..." }
  },
  "timestamp": 1750000000000
}
```

## 开发顺序

根据规格文档，按以下顺序开发（Phase 1 → 4）：

### Phase 1 — 后端安全闭环 (Task 00→11) — 已完成
后端核心安全闭环已通过全量测试、打包和 Git 检查验收。包括 Spring Boot 骨架、JPA 实体、OS 工具、安全风险引擎、Agent 编排层、确认执行、审计 API 和 Chat API。`POST /api/chat/send` 已可用。

### Phase 2 — 前端演示闭环 (Task 02→17)
六页面前端 (ChatConsole, Dashboard, ToolCenter, SecurityCenter, AuditLog, ReportCenter)

### Phase 3 — 执行器与报告 (Task 06→12)
SafeExecutor、PendingAction 确认流程、报告生成

### Phase 4 — 交付材料 (Task 18→21)
演示脚本、部署文档、环境验证、最终整合

## 四演示场景

1. **系统健康检查** — 多 Tool 发散 → 健康评分 + 报告
2. **磁盘诊断** — 根因分析 + 安全清理建议 (需 CONFIRM)
3. **服务诊断 + L2 确认** — nginx 检查 + 安全重启流程
4. **危险命令阻断** — rm -rf / + Prompt Injection → BLOCK + 审计

## 安全红线

- ❌ 无 `/api/shell`、`/api/exec`、`/api/command/run` 端点
- ❌ 不将用户输入直接拼接为 Shell 命令
- ❌ 不以 `/bin/sh -c` 执行用户原始输入
- ✅ 所有 OS 操作通过 OpsTool 封装
- ✅ 所有 Tool 注册到 ToolRegistry
- ✅ 所有执行动作经过 RiskCheckService
- ✅ L2 操作必须确认，L3/L4 必须阻断
- ✅ 全量审计日志

## License

本项目为竞赛参赛项目。
