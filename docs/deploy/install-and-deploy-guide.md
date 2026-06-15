# 部署与安装指南

> **任务来源**：任务卡 Task 21 — 初赛交付材料骨架
> **本文档定位**：正式版部署文档。详细 Kylin / LoongArch 适配见 [`kylin-loongarch-deploy-guide.md`](./kylin-loongarch-deploy-guide.md)，本文档为标准化提交通道。
> **状态**：v0.1，与产品 v0.1 版本同步。

---

## 1. 环境要求

### 1.1 硬件

| 项 | 最低 | 推荐 |
| --- | --- | --- |
| CPU | LoongArch64 / x86_64 单核 1.5GHz | 4 核 2.5GHz |
| 内存 | 4 GB | 8 GB+ |
| 磁盘 | 2 GB | 5 GB+ |

### 1.2 软件

| 项 | 版本 | 备注 |
| --- | --- | --- |
| OS | Kylin Advanced Server V11 / Ubuntu 22.04+ / CentOS 8+ | LoongArch 需 LoongArch JDK |
| JDK | 17 LTS | 龙芯版或 OpenJDK 17 |
| Maven | 3.9+ | 仅构建需要 |
| Node.js | 18 LTS+ | 仅 dev 模式需要 |
| 数据库 | H2 File Mode（嵌入式） | 无外部依赖 |

详见 [`kylin-loongarch-deploy-guide.md`](./kylin-loongarch-deploy-guide.md) §1-§3。

## 2. 安装步骤

### 2.1 后端

```bash
git clone <repo-url> kylin-ops
cd kylin-ops
cd backend
mvn -B clean package -DskipTests
# 产物：target/kylin-ops-guard.jar
```

### 2.2 前端

```bash
cd frontend
npm ci              # 严格按 package-lock.json 安装
npm run build       # vue-tsc + vite build → dist/
# 产物：dist/ 静态文件
```

### 2.3 Standalone 单 JAR 构建（可选，低配环境推荐）

前后端合一，只需 1 个进程 1 个端口，适合 LoongArch 低配虚拟机：

```bash
bash deploy/scripts/build-standalone.sh
# 自动执行 npm run build + mvn -Pstandalone clean package
# 产物：backend/target/kylin-ops-guard.jar（内含前端 UI）
```

> 三种部署形态共存：dev（Vite HMR 热更新） / prod（Nginx + 后端分离） / standalone（单 JAR）。详见 CLAUDE.md "Build / Run" 段。

### 2.3 配置

`backend/src/main/resources/application.yml` 关键项：

```yaml
spring.datasource.url: jdbc:h2:file:./data/kylinops
server.port: 8080
logging.file.path: ./logs
```

环境变量（可选）：
- `LLM_BASE_URL` / `LLM_API_KEY` / `LLM_MODEL` —— 启用 LLM 时配置
- `SPRING_PROFILES_ACTIVE` —— dev / prod 切换

## 3. 启动步骤

### 3.1 快速启动（脚本）

```bash
bash deploy/scripts/check-env.sh       # 环境检查
sudo bash deploy/scripts/seed-demo.sh  # (Linux only) 演示数据 seeding
bash deploy/scripts/start-backend.sh   # 启动后端
bash deploy/scripts/start-frontend.sh  # 启动前端 dev (或部署 dist/ 到 nginx)
```

### 3.1b Standalone 启动（单 JAR）

构建完成后（§2.3），一行命令启动前后端合一的服务：

```bash
bash deploy/scripts/start-standalone.sh
# 默认 profile=dev,standalone（H2 文件模式）
# 访问 http://localhost:8080 // 登录 admin / test-admin-pwd
```

生产环境使用 PostgreSQL：

```bash
SPRING_PROFILES_ACTIVE=prod,standalone bash deploy/scripts/start-standalone.sh
```

### 3.2 生产部署

```bash
# 1. 后端用 systemd
sudo tee /etc/systemd/system/kylin-ops-guard.service <<EOF
[Unit]
Description=KylinOps Guard Backend
After=network.target

[Service]
Type=simple
User=opsuser
WorkingDirectory=/opt/kylin-ops
ExecStart=/usr/bin/java -jar /opt/kylin-ops/backend/target/kylin-ops-guard.jar
Restart=on-failure
StandardOutput=append:/var/log/kylin-ops/backend.log
StandardError=append:/var/log/kylin-ops/backend-error.log

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable kylin-ops-guard
sudo systemctl start kylin-ops-guard

# 2. 前端用 nginx（详见 kylin-loongarch-deploy-guide.md §5.2）
```

## 4. 验证步骤

```bash
# 1. 健康检查
curl http://localhost:8080/api/health
# 预期：{"status":"UP"}

# 2. 工具注册
curl -s http://localhost:8080/api/tools | jq 'length'
# 预期：≥ 10

# 3. 风险规则
curl -s http://localhost:8080/api/security/rules | jq 'length'
# 预期：≥ 5

# 4. 端到端 smoke
curl -X POST http://localhost:8080/api/chat/send \
  -H "Content-Type: application/json" \
  -d '{"content":"检查系统健康"}'
# 预期：JSON 含 intentType + toolCalls + auditId

# 5. L4 拦截验证
curl -X POST http://localhost:8080/api/chat/send \
  -H "Content-Type: application/json" \
  -d '{"content":"执行 rm -rf /"}'
# 预期：riskLevel=L4, decision=BLOCK, toolCallCount=0
```

完整验证清单（含 11 节 50+ 检查项）：[`environment-checklist.md`](./environment-checklist.md)。

## 5. 常见问题

详见 [`kylin-loongarch-deploy-guide.md`](./kylin-loongarch-deploy-guide.md) §10：
- Q1: 端口 8080 被占用
- Q2: LLM API 无法访问
- Q3: H2 console 报错
- Q4: OS 工具返回 failed
- Q5: npm install 慢
- Q6: LoongArch 上 Playwright 装不上

## 6. 详细文档

- Kylin / LoongArch 适配：[`kylin-loongarch-deploy-guide.md`](./kylin-loongarch-deploy-guide.md)（424 行）
- 环境验证清单：[`environment-checklist.md`](./environment-checklist.md)（163 行）
- LoongArch 验证步骤：见 `kylin-loongarch-deploy-guide.md` §12
- 麒麟 V11 验证步骤：见 `kylin-loongarch-deploy-guide.md` §13