# LoongArch 真机验收 Runbook (P4-T3 §1.2)

> **目的**: 在 Kylin Advanced Server V11 / LoongArch64 真机上逐格回填
> [`phase4-loongarch-acceptance.md`](./phase4-loongarch-acceptance.md) §1.2 的 ⏳ 格子。
> **配套设计**: [`../superpowers/specs/2026-06-15-loongarch-acceptance-design.md`](../superpowers/specs/2026-06-15-loongarch-acceptance-design.md)
> **配套脚本**: `deploy/scripts/{acceptance-smoke,loongarch-runtime-extras,loongarch-auth-extras,loongarch-perf-gate}.sh`

---

## 0. 上传清单（一次性）

从 Windows 上传以下文件到 LoongArch `/opt/kylinops/`（或任意工作目录）：

- `backend/target/kylin-ops-guard.jar`
- `deploy/scripts/start-standalone.sh`
- `deploy/scripts/seed-demo.sh`
- `deploy/scripts/check-env.sh`
- `deploy/scripts/acceptance-smoke.sh`
- `deploy/scripts/loongarch-runtime-extras.sh`
- `deploy/scripts/loongarch-auth-extras.sh`
- `deploy/scripts/loongarch-perf-gate.sh`
- 本文件 `docs/test/loongarch-runbook.md`（仅供参考）

## 1. 启动 (Pre-flight)

```bash
cd /opt/kylinops  # 或你上传到的目录

# 1.1 环境指纹
bash check-env.sh | tee evidence/01-env.txt

# 1.2 准备 demo data (sudo + 200MB)
sudo bash seed-demo.sh | tee evidence/02-seed-demo.txt

# 1.3 启动后端 (dev,standalone profile + H2 文件模式)
# 注意: prod profile 强制 PostgreSQL driver, 不能用! 必须用 dev,standalone.
SPRING_PROFILES_ACTIVE=dev,standalone nohup bash start-standalone.sh > backend-startup.log 2>&1 &

# 1.4 等待健康
for i in $(seq 1 30); do
    curl -fsS http://localhost:8080/api/health >/dev/null 2>&1 && break
    sleep 1
done
curl -s http://localhost:8080/api/health | tee evidence/03-health.txt
# 预期: {"code":200,"data":{"status":"UP",...}}
```

## 2. §1.2.1 运行时 & 数据库 Gate (7 cells, 5 testable + 2 N/A)

```bash
# Cell 1.1: JDK 兼容性
java -version 2>&1 | tee evidence/runtime/01-java-version.txt
# 预期: openjdk version "17.x.x"

# Cell 1.2: /api/health
curl -s http://localhost:8080/api/health | tee evidence/runtime/02-health.txt

# Cell 1.3: /api/health/live
curl -s -o /dev/null -w "HTTP %{http_code}\n" http://localhost:8080/api/health/live | tee evidence/runtime/03-live.txt

# Cell 1.4: /api/health/ready
curl -s -o /dev/null -w "HTTP %{http_code}\n" http://localhost:8080/api/health/ready | tee evidence/runtime/04-ready.txt

# Cell 1.5 + 1.6 + 1.7: Flyway / OS-tool 硬超时 / H2→PG fingerprint
# (covered by loongarch-runtime-extras.sh; Flyway + H2→PG are N/A on dev profile)
export BASE_URL=http://localhost:8080
export SMOKE_USERNAME=admin
export SMOKE_PASSWORD='test-admin-pwd'
bash loongarch-runtime-extras.sh 2>&1 | tee evidence/runtime/05-runtime-extras.txt
```

## 3. §1.2.2 认证 & 会话 Gate (7 cells, 4 testable via smoke + 2 via auth-extras + 2 N/A)

```bash
# Cells 2.1-2.4: anon 401 / login+cookie / CSRF / 跨 session (covered by smoke.sh)
export BASE_URL=http://localhost:8080
export SMOKE_USERNAME=admin
export SMOKE_PASSWORD='test-admin-pwd'
bash acceptance-smoke.sh 2>&1 | tee evidence/auth/01-smoke.txt

# Cells 2.5-2.6: 失败锁定 5x + 锁定后正确密码也被拒
bash loongarch-auth-extras.sh 2>&1 | tee evidence/auth/02-lockout.txt
# ⚠ 此命令会把 admin 锁定 ~15 分钟. 跑完后建议重启后端恢复.

# Cells 2.7-2.8: Idle 30m / Absolute 8h (N/A — 需等 30 分钟/8 小时, 单元测试已覆盖)
# Cells 2.9: 执行前审计闭锁 (N/A — 需破坏审计子系统, 单元测试已覆盖)
```

## 4. §1.2.3 LLM Gate (5 cells, 2 testable + 3 N/A)

```bash
# Cell 3.1: LLM 关闭 → 规则兜底
curl -s -X POST http://localhost:8080/api/chat/send \
    -H 'Content-Type: application/json' \
    -H "X-XSRF-TOKEN: $(grep XSRF-TOKEN /tmp/kylinops-smoke-cookies.txt | awk '{print $7}')" \
    -b /tmp/kylinops-smoke-cookies.txt \
    -d '{"content":"检查系统健康"}' | tee evidence/llm/01-disabled-fallback.txt
# 预期: 正常返回 (rule-based intent 生效)

# Cell 3.2: LLM 调用审计 V3 (查 llm_call records)
curl -s "http://localhost:8080/api/audit/logs?actionType=llm_call&size=5" \
    -b /tmp/kylinops-smoke-cookies.txt | tee evidence/llm/02-audit-v3.txt
# 预期: items 列表 (即使 LLM 关闭, 也可能有 "disabled" 条目记录)

# Cells 3.3-3.5: DeepSeek / Qwen / 无效 key → N/A (无 key)
echo "DeepSeek real request: N/A (no API key)" > evidence/llm/03-deepseek-NA.txt
echo "Qwen real request: N/A (no API key)" > evidence/llm/04-qwen-NA.txt
echo "Invalid key fallback: N/A (no API key)" > evidence/llm/05-invalid-key-NA.txt
```

## 5. §1.2.4 安全 & 风险 Gate (8 cells, all testable)

```bash
# Cells 4.1-4.5: 5 个 L4 必过用例 (大部分由 smoke.sh 覆盖)
# Cell 4.6: 删除 /etc/passwd (DEFER-001 修复验证)
curl -s -X POST http://localhost:8080/api/chat/send \
    -H 'Content-Type: application/json' \
    -H "X-XSRF-TOKEN: $(grep XSRF-TOKEN /tmp/kylinops-smoke-cookies.txt | awk '{print $7}')" \
    -b /tmp/kylinops-smoke-cookies.txt \
    -d '{"content":"删除 /etc/passwd"}' | tee evidence/security/06-delete-passwd.txt
# 预期修复后: riskLevel=L3+, riskDecision=BLOCK, matchedRules 含 block_path_root

# Cell 4.7: /api/security/risk-check path=/etc/passwd
curl -s -X POST http://localhost:8080/api/security/risk-check \
    -H 'Content-Type: application/json' \
    -H "X-XSRF-TOKEN: $(grep XSRF-TOKEN /tmp/kylinops-smoke-cookies.txt | awk '{print $7}')" \
    -b /tmp/kylinops-smoke-cookies.txt \
    -d '{"content":"/etc/passwd","targetType":"path"}' | tee evidence/security/07-risk-check-path.txt
# 预期: riskLevel=L3+, decision=BLOCK, matchedRules 含 block_path_root

# Cell 4.8: production action 注册表
curl -s http://localhost:8080/api/tools \
    -b /tmp/kylinops-smoke-cookies.txt | python3 -m json.tool | tee evidence/security/08-action-whitelist.txt
# 预期: 工具列表, 其中 safe_service_restart 标注 production action
```

## 6. §1.2.5 性能 Gate (5 metrics)

```bash
# 重要: 必须等 backend 至少 warmup 30s 再跑 hot metric
# runtime-extras.sh 跑完后再跑这个
export BASE_URL=http://localhost:8080
export SMOKE_USERNAME=admin
export SMOKE_PASSWORD='test-admin-pwd'
bash loongarch-perf-gate.sh 2>&1 | tee evidence/perf/01-perf-gate.txt
# 预期: 6 个指标全在预算内 (cold/hot/RiskCheck/health/chat/report)
```

## 7. §1.2.6 前端 & E2E Gate (6 cells, 2 testable + 4 N/A)

```bash
# Cell 6.1: 前端单元 179 + E2E 18 (N/A — 不在 LoongArch 上跑)
echo "Frontend 179 unit + 18 mock E2E: N/A (run on x86-dev / CI)" \
    > evidence/frontend-e2e/01-unit-e2e-NA.txt

# Cell 6.2: Playwright live mode 3/3 (N/A — LoongArch 无 Chromium)
echo "Playwright E2E live: N/A (no Chromium on LoongArch)" \
    > evidence/frontend-e2e/02-live-e2e-NA.txt

# Cell 6.3: JAR 内嵌的 frontend/dist/index.html 可达
curl -s -o /dev/null -w "HTTP %{http_code}\n" \
    http://localhost:8080/index.html | tee evidence/frontend-e2e/03-index-html.txt
# 预期: HTTP 200 (Spring static + SpaFallbackFilter)

# Cell 6.4: SPA 路由回退 (/chat 应回退到 index.html)
curl -s -o /dev/null -w "HTTP %{http_code}\n" \
    http://localhost:8080/chat | tee evidence/frontend-e2e/04-spa-fallback.txt
# 预期: HTTP 200 (SPA fallback 工作)

# Cell 6.5: 5 个快捷按钮 — 需要 Playwright, N/A on LoongArch
echo "5 quick-action buttons: N/A (visual UI verification, x86-dev 已 PASS)" \
    > evidence/frontend-e2e/05-quick-actions-NA.txt

# Cell 6.6: L2 确认卡片 — 同样 N/A
echo "L2 confirm card: N/A (visual UI verification)" \
    > evidence/frontend-e2e/06-confirm-card-NA.txt
```

## 8. 故障排查

### L1 - 单 cell 失败
- 把响应 body + 后端日志 tail 贴回对话
- 不要继续往下跑, 等待诊断

### L2 - smoke.sh FAIL
- 把 smoke 完整 stdout 贴回
- 把 `tail -50 backend/logs/backend.log` 贴回

### L3 - 启动失败
- 立即停止
- 把 `head -200 backend/logs/backend.log` 贴回

### 锁定恢复
- 锁定后 admin 15 分钟内无法登录
- 快速恢复: `kill $(pgrep -f kylin-ops-guard.jar)` 然后 `SPRING_PROFILES_ACTIVE=dev,standalone bash start-standalone.sh`
- 锁定状态在内存中, 重启后丢失
