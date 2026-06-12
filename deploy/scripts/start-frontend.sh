#!/bin/bash
# ============================================================
# 前端启动脚本 — 麒麟安全智能运维 Agent
# ============================================================

set -e

echo "=========================================="
echo " 麒麟安全智能运维 Agent — 前端启动"
echo "=========================================="

cd frontend

if [ ! -d "node_modules" ]; then
    echo "安装前端依赖..."
    npm ci
fi

echo "启动 Vite 开发服务器..."
echo "提示: 前端页面可访问 http://localhost:5173"
echo ""

# Playwright E2E hint:
#   第一次跑 npm run test:e2e 之前需要先装浏览器：
#     npx playwright install chromium
#   离线（mock）模式直接：npm run test:e2e
#   真后端 smoke 模式：E2E_LIVE=true npx playwright test tests/e2e/demo-live.spec.ts
#   详细见 frontend/README.md 的 "E2E tests (Playwright)" 段。
echo "Playwright E2E: 首次跑前请执行 'npx playwright install chromium'"
echo ""

npm run dev
