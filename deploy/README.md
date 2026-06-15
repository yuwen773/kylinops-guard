# 麒麟安全智能运维 Agent — 部署包结构

> 任务来源：P4-T1（Phase 4 / Task 1）。
> 目的：让运维在 Kylin V11 / LoongArch64 目标机一键安装生产部署。

## 目录布局

```
deploy/
├── README.md                      ← 本文件
├── scripts/                       ← 启停/环境检查/演示数据/更新脚本
│   ├── check-env.sh
│   ├── start-backend.sh
│   ├── start-frontend.sh
│   ├── update.sh
│   ├── seed-demo.sh
│   └── seed-demo-cleanup.sh
├── config/                        ← 部署期外挂配置 (P4-T1 新增)
│   ├── application-prod.yml       ← 部署侧 yml overlay, 不打进 JAR
│   └── kylinops.env.example       ← 环境变量模板, 零 secret
├── systemd/                       ← systemd unit (P4-T1 新增)
│   └── kylinops-guard.service
└── nginx/                         ← Nginx 站点配置 (P4-T1 新增)
    └── kylinops-guard.conf
```

## 安装步骤 (麒麟 V11 / LoongArch64)

按以下顺序执行。**所有 sudo 命令只在新装机器上跑**；幂等可重复。

### 1. 准备服务账户与目录

```bash
sudo useradd --system --shell /usr/sbin/nologin --home /opt/kylinops kylinops
sudo mkdir -p /opt/kylinops \
                /var/lib/kylinops/frontend \
                /var/lib/kylinops/backup \
                /var/lib/kylinops/certbot \
                /var/log/kylinops \
                /etc/kylinops/tls
sudo chown -R kylinops:kylinops /opt/kylinops /var/lib/kylinops /var/log/kylinops
sudo chmod 0750 /opt/kylinops /var/lib/kylinops /var/log/kylinops
```

### 2. 部署 JAR 与前端 dist

```bash
# 后端 (Spring Boot fat JAR, P4-T1 与 master mvn package 输出一致)
sudo cp backend/target/kylin-ops-guard.jar /opt/kylinops/
sudo chown kylinops:kylinops /opt/kylinops/kylin-ops-guard.jar

# 前端
sudo mkdir -p /var/lib/kylinops/frontend
sudo cp -r frontend/dist/* /var/lib/kylinops/frontend/dist/
sudo chown -R kylinops:kylinops /var/lib/kylinops/frontend
```

### 3. 部署 env 文件 (敏感)

```bash
sudo cp deploy/config/kylinops.env.example /etc/kylinops/kylinops.env
sudo chown root:kylinops /etc/kylinops/kylinops.env
sudo chmod 0640 /etc/kylinops/kylinops.env   # 或 0600 更严
sudo -e /etc/kylinops/kylinops.env           # 填 DB_PASSWORD / ADMIN_PASSWORD_HASH / LLM_API_KEY
```

### 4. 部署 application-prod.yml (非敏感)

```bash
sudo cp deploy/config/application-prod.yml /etc/kylinops/application-prod.yml
sudo chown root:kylinops /etc/kylinops/application-prod.yml
sudo chmod 0644 /etc/kylinops/application-prod.yml
```

### 5. 部署 systemd unit

```bash
sudo cp deploy/systemd/kylinops-guard.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now kylinops-guard
sudo systemctl status kylinops-guard
```

### 6. 部署 Nginx 站点

```bash
sudo cp deploy/nginx/kylinops-guard.conf /etc/nginx/conf.d/
sudo cp <你的证书> /etc/kylinops/tls/fullchain.pem
sudo cp <你的私钥> /etc/kylinops/tls/privkey.pem
sudo chown -R root:kylinops /etc/kylinops/tls
sudo chmod 0640 /etc/kylinops/tls/*
sudo nginx -t
sudo systemctl reload nginx
```

### 7. 验收

```bash
# 1. 服务健康
sudo systemctl status kylinops-guard
curl -I https://<你的域名>/api/health

# 2. 安全 sandbox 自检
systemctl show kylinops-guard | grep -E 'NoNewPrivileges|PrivateTmp|ProtectSystem|User='
# 预期: User=kylinops / NoNewPrivileges=yes / PrivateTmp=yes / ProtectSystem=strict

# 3. 端到端 smoke (P4-T2 acceptance-smoke.sh)
bash deploy/scripts/acceptance-smoke.sh   # 详见 P4-T2
```

## 安全契约 (P4-T1 必须满足)

| 检查项 | 命令 | 预期 |
| --- | --- | --- |
| systemd 不以 root 运行 | `systemctl show kylinops-guard \| grep ^User=` | `User=kylinops` |
| 禁止提权 | `systemctl show ... \| grep NoNewPrivileges` | `yes` |
| /tmp 隔离 | `... \| grep PrivateTmp` | `yes` |
| env 文件权限 | `stat -c '%a %U:%G' /etc/kylinops/kylinops.env` | `640` / `root:kylinops` |
| Nginx 后端指向 loopback | `grep proxy_pass .../kylinops-guard.conf` | `127.0.0.1:8080` |
| TLS 协议 | `grep ssl_protocols ...` | `TLSv1.2 TLSv1.3` |
| 请求体大小 | `grep client_max_body_size ...` | `1m` |

## 与 master 部署的区别

| 维度 | master (现有) | P4-T1 (新增) |
| --- | --- | --- |
| 进程管理 | 手工 nohup / start-backend.sh | systemd unit + 开机自启 + 失败重启 |
| 反向代理 | 文档示例 (无配置) | 完整 nginx site, 含 TLS + 安全头 |
| 凭据管理 | 无 env 文件 | /etc/kylinops/kylinops.env 0600 |
| 进程隔离 | 无 | NoNewPrivileges / PrivateTmp / ProtectSystem=strict |
| 配置文件位置 | JAR 内 (application-prod.yml) | 部署侧外挂 (/etc/kylinops/) |
| 重启策略 | 手工 | systemd 自动 + Restart=on-failure |

## 不在 P4-T1 范围 (P4-T2..P4-T5)

- `deploy/scripts/migrate-legacy-h2.sh` / `backup-postgres.sh` / `restore-postgres.sh` / `acceptance-smoke.sh` — P4-T2
- LoongArch 实测回填（环境检查清单的"实测值"列） — P4-T3
- 故障恢复 + 10 并发测试 — P4-T4
- functional / performance / security 报告回填 — P4-T5

## 维护

- 文档版本：v0.1（与产品版本同步）
- 修订触发：JDK 版本变化 / 部署路径变更 / systemd / nginx 配置变化
- 维护责任：Phase 4 / Task 20-21
- 下次审查：复赛启动前 / LoongArch 实测完成后
