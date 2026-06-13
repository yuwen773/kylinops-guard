# 麒麟 / LoongArch 部署指南

> **任务来源**：任务卡 Task 20 — 麒麟 / LoongArch 部署文档
> **目的**：面向评委与运维人员的部署文档，覆盖 Kylin Advanced Server V11 (LoongArch64) 完整部署步骤。
> **诚实声明**：本文档区分**已验证**（Windows 开发主机）与**待验证**（Kylin V11 / LoongArch64 目标机）环境。所有 LoongArch 步骤是**理论可行**的部署路径，必须在目标机执行后回填实测数据。

---

## 1. 系统环境要求

### 1.1 硬件

| 项 | 最低 | 推荐 | 备注 |
| --- | --- | --- | --- |
| CPU | LoongArch64 单核 1.5GHz | LoongArch64 4 核 2.5GHz | 麒麟 V11 默认 CPU 架构 |
| 内存 | 4 GB | 8 GB+ | H2 数据库默认加载到内存 |
| 磁盘 | 2 GB | 5 GB+ | `data/`、`logs/`、构建产物 |
| 网络 | 100 Mbps | 1 Gbps | LLM API 调用需外网（如启用） |

### 1.2 操作系统

| 系统 | 版本 | 状态 | 备注 |
| --- | --- | --- | --- |
| 麒麟 Advanced Server | V11 (LoongArch64) | **目标机待验证** | 赛题要求 |
| Ubuntu | 22.04 LTS+ | 已验证（dev 主机的 WSL） | 开发参考 |
| CentOS / RHEL | 8+ | 未验证 | 理论兼容 |
| Windows | 11 + Git Bash | 已验证（开发主机） | 仅 dev，**不推荐生产** |
| macOS | 13+ | 未验证 | 仅 dev |

---

## 2. CPU 架构要求

| 架构 | 状态 | 备注 |
| --- | --- | --- |
| x86_64 | 已验证 | Windows dev、Ubuntu dev |
| loongarch64 | **目标机待验证** | 赛题要求；Java 字节码中立，依赖 LoongArch JDK |
| aarch64 | 未验证 | 理论兼容；ARM JDK 可移植 |

**核心约束**：项目代码无 native 依赖（仅 Java + JS），架构兼容性由 JDK 承担。LoongArch 上需安装 LoongArch 专属 JDK 17。

---

## 3. JDK / Node / 数据库要求

### 3.1 JDK

| 项 | 版本 | 来源 | 验证 |
| --- | --- | --- | --- |
| JDK | 17 LTS | LoongArch JDK 17（[龙芯官网下载](https://www.loongson.cn/)） | **目标机待验证** |
| JDK | 17 LTS | OpenJDK / Eclipse Temurin | 已验证（x86_64 dev） |
| Maven | 3.9+ | apt / yum / 手动 | 已验证 |

**注意**：LoongArch 上不能直接用 x86_64 JDK 二进制；必须使用龙芯官方提供的 LoongArch JDK 17。

### 3.2 Node.js

| 项 | 版本 | 验证 |
| --- | --- | --- |
| Node.js | 18 LTS+ | 已验证（Windows dev）；目标机可用 NodeSource / nvm |
| npm | 9+ | 同上 |

### 3.3 数据库

P0：H2 File Mode（嵌入式，无需独立服务）
- 数据文件位置：`./data/kylinops.mv.db`
- 备份：直接复制 `data/` 目录
- 目标机验证：H2 是纯 Java 库，LoongArch 上无特殊问题

P1：可平滑迁移到 PostgreSQL / MySQL（Repository 抽象已就位），URL 是唯一变更点。

---

## 4. 后端构建步骤

### 4.1 在目标机（Kylin V11）首次部署

```bash
# 1. 安装 JDK 17 (LoongArch)
sudo yum install -y java-17-openjdk-devel  # 假设 yum 可用
# 或手动安装龙芯 JDK（推荐 LoongArch 优化版本）
# 解压到 /opt/jdk-17，配置 JAVA_HOME

# 2. 安装 Maven
sudo yum install -y maven
# 或从 Apache 官网下载二进制包

# 3. 验证
java -version    # 应输出 17.x
mvn -version     # 应输出 3.9.x

# 4. 克隆代码（私有仓库需先配置 SSH）
git clone <repo-url> kylin-ops
cd kylin-ops

# 5. 构建后端
cd backend
mvn -B clean package -DskipTests
# 产物：target/kylin-ops-guard.jar
```

### 4.2 配置文件

`backend/src/main/resources/application.yml` 关键配置：

```yaml
spring:
  datasource:
    url: jdbc:h2:file:./data/kylinops   # H2 File Mode
    username: sa
    password: ""
  jpa:
    hibernate:
      ddl-auto: update                  # 自动建表

server:
  port: 8080

logging:
  file:
    path: ./logs                        # 日志输出目录
```

可通过 `application-{profile}.yml` 覆盖：
- `application-dev.yml` — 开发环境
- `application-prod.yml` — 生产环境（生产建议禁用 H2 console）

### 4.3 启动后端

```bash
# 使用项目脚本（推荐）
bash deploy/scripts/start-backend.sh

# 或手动
java -jar backend/target/kylin-ops-guard.jar
# 后台启动
nohup java -jar backend/target/kylin-ops-guard.jar > logs/backend.log 2>&1 &
```

启动成功标志：`curl http://localhost:8080/api/health` 返回 `{"status":"UP"}`

### 4.4 停止后端

```bash
# 查找 PID
ps aux | grep kylin-ops-guard
# 优雅停止
kill <PID>
# 强制停止
kill -9 <PID>

# 或用 pkill
pkill -f kylin-ops-guard.jar
```

---

## 5. 前端构建步骤

### 5.1 在目标机构建

```bash
# 1. 安装 Node.js 18+
curl -fsSL https://rpm.nodesource.com/setup_18.x | sudo bash -
sudo yum install -y nodejs

# 2. 构建前端
cd frontend
npm ci                  # 严格按 package-lock.json 安装
npm run build           # vue-tsc + vite build → dist/

# 产物：frontend/dist/ 静态文件
```

### 5.2 部署前端静态文件

将 `frontend/dist/` 复制到 nginx / apache：

```nginx
# /etc/nginx/conf.d/kylin-ops.conf
server {
    listen 80;
    server_name your-domain;

    root /var/www/kylin-ops;
    index index.html;

    location / {
        try_files $uri $uri/ /index.html;  # SPA 路由 fallback
    }

    location /api/ {
        proxy_pass http://127.0.0.1:8080;   # 代理到后端
        proxy_set_header Host $host;
    }
}
```

### 5.3 开发模式（仅 dev）

```bash
cd frontend
npm run dev    # Vite dev server → http://localhost:5173
# Vite 配置 proxy: /api → http://localhost:8080
```

---

## 6. 启动命令（汇总）

```bash
# 1. 环境检查
bash deploy/scripts/check-env.sh

# 2. (Linux only, 可选) 演示数据 seeding
sudo bash deploy/scripts/seed-demo.sh

# 3. 启动后端
bash deploy/scripts/start-backend.sh

# 4. 启动前端（dev 模式）或部署 dist/ 到 nginx（生产模式）
bash deploy/scripts/start-frontend.sh

# 5. 健康检查
curl http://localhost:8080/api/health
```

---

## 7. 停止命令

```bash
pkill -f kylin-ops-guard.jar     # 停止后端
pkill -f "vite"                   # 停止 dev 前端
sudo systemctl stop nginx        # 停止 nginx（生产部署）
```

---

## 8. 日志目录

| 路径 | 内容 | 轮转 |
| --- | --- | --- |
| `logs/backend.log` | Spring Boot 主日志 | 启动脚本 `start-backend.sh` 用 `nohup` 输出 |
| `logs/audit/` | 审计日志（由 `AuditLogService` 持久化） | DB 持久化，无需文件轮转 |
| H2 数据库 | `data/kylinops.mv.db` + `data/kylinops.trace.db` | 备份 `data/` 即可 |

日志查看：
```bash
tail -f logs/backend.log
# 或
journalctl -u kylin-ops -f    # 若注册为 systemd 服务
```

---

## 9. 健康检查

### 9.1 HTTP 端点

```bash
curl http://localhost:8080/api/health
# 预期：{"status":"UP"}
```

### 9.2 工具注册状态

```bash
curl http://localhost:8080/api/tools | python3 -m json.tool | head -30
# 应列出 10 个 L0 工具
```

### 9.3 风险规则目录

```bash
curl http://localhost:8080/api/security/rules | python3 -m json.tool
```

### 9.4 端到端 smoke

```bash
curl -X POST http://localhost:8080/api/chat/send \
  -H "Content-Type: application/json" \
  -d '{"content":"查看磁盘状态"}'
# 预期：JSON 含 intentType=DISK_DIAGNOSIS / toolCalls 非空 / auditId 非空
```

---

## 10. 常见问题（FAQ）

### Q1: 端口 8080 被占用
```bash
# 找出占用进程
sudo lsof -i :8080
# 或
sudo netstat -tlnp | grep 8080

# 修改 application.yml 的 server.port
```

### Q2: LLM API 无法访问
默认 LLM 是 optional。RiskRuleEngine、PromptInjectionDetector、SafeExecutor、AuditLog 全部不依赖 LLM。即使 `LLM_BASE_URL` 不可达，系统仍可演示。

### Q3: H2 console 报错
生产环境**禁用** H2 console。开发环境：
```yaml
spring.h2.console.enabled: true
spring.h2.console.path: /h2-console
```
访问：`http://localhost:8080/h2-console`，JDBC URL = `jdbc:h2:file:./data/kylinops`

### Q4: OS 工具返回 failed ToolResult
正常行为。Windows / macOS / 容器环境无 Linux 二进制时，`NetworkPortTool`、`SystemInfoTool` 等会返回 `status: "failed"` + 降级说明。生产部署在 Kylin 上不会出现此问题。

### Q5: 前端 npm install 慢 / 失败
使用 npmmirror：
```bash
npm config set registry https://registry.npmmirror.com
npm ci
```

### Q6: LoongArch 上 Playwright 装不上
Playwright 官方镜像无 LoongArch Chromium 构建。**降级方案**：跳过 E2E live 模式，依赖 mock E2E + 手工 smoke。答辩中说明此为平台依赖，非产品代码问题。

---

## 11. 国产化适配表述

- **CPU**：LoongArch64（龙芯自主指令集）
- **OS**：Kylin Advanced Server V11（中科方德 / 麒麟软件国产操作系统）
- **JDK**：龙芯 JDK 17（LoongArch 优化版）
- **数据库**：H2 File Mode（嵌入式，纯 Java，跨架构）
- **前端**：Vue 3 + Element Plus（开源，跨平台）
- **LLM**：DeepSeek / Qwen（国产开源 LLM，符合国产化合规）

项目零 native 依赖，零 x86 指令集假设，理论兼容所有国产化技术栈。**目标机待实测回填**。

---

## 12. LoongArch 验证清单

> 以下步骤**必须**在 LoongArch64 真实硬件上执行，并回填实测结果。

```bash
# A. 环境信息
uname -m                  # 应输出 loongarch64
cat /etc/os-release       # 应包含 Kylin V11
java -version             # 应输出 17.x LoongArch 版
mvn -version              # 应输出 3.9.x

# B. 构建后端
cd backend && mvn -B clean package -DskipTests
# 预期：BUILD SUCCESS，产物 backend/target/kylin-ops-guard.jar

# C. 运行后端测试
mvn -B test
# 预期：Tests run: 280, Failures: 0, Errors: 0, Skipped: 0

# D. 启动后端 + 健康检查
java -jar backend/target/kylin-ops-guard.jar &
curl http://localhost:8080/api/health
# 预期：{"status":"UP"}

# E. 注册工具数量
curl http://localhost:8080/api/tools | jq '. | length'
# 预期：≥ 10

# F. 危险命令拦截
curl -X POST http://localhost:8080/api/chat/send \
  -H "Content-Type: application/json" \
  -d '{"content":"直接执行 rm -rf /"}'
# 预期：riskLevel=L4, decision=BLOCK, toolCallCount=0

# G. 性能预算
time curl -X POST http://localhost:8080/api/chat/send \
  -H "Content-Type: application/json" \
  -d '{"content":"检查系统健康状态"}'
# 预期：real < 30s
```

回填模板：见 `docs/deploy/environment-checklist.md`。

---

## 13. 麒麟 V11 验证清单

> 以下步骤在 Kylin Advanced Server V11 上执行（x86_64 或 LoongArch64 均可）。

```bash
# H. 系统标识
cat /etc/kylin-release      # Kylin V11
# 或
rpm -q kylin-release        # 应输出 kylin-release-x-x.x

# I. 软件源
sudo yum repolist           # 应含 kylin 源

# J. 服务管理
systemctl status kylin-ops-guard   # 若注册为服务
# 或
ps aux | grep kylin-ops-guard

# K. 系统日志
journalctl -u kylin-ops-guard -n 50   # 最近 50 行

# L. 防火墙
sudo firewall-cmd --list-ports | grep 8080   # 8080 应开放
# 开放命令
sudo firewall-cmd --permanent --add-port=8080/tcp
sudo firewall-cmd --reload
```

---

## 14. 文档维护

- 文档版本：v0.1（与产品版本同步）
- 修订触发：JDK 版本变化 / 部署路径变更 / 新增 systemd 集成
- 维护责任：Phase 4 / Task 20
- 下次审查：复赛启动前 / LoongArch 实测完成后

---

**诚实声明重申**：本文档中所有"目标机待验证"项均未在 Kylin V11 / LoongArch64 真实硬件上跑通。文档提供完整部署路径与验证命令，但实测结果需目标机执行后回填。开发机（x86_64 + Windows）的所有验证数据已在 [`docs/test/functional-test-report-draft.md`](../test/functional-test-report-draft.md) 中记录。