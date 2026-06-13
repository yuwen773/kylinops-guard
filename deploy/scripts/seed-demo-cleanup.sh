#!/bin/bash
# ============================================================
# 演示数据清理脚本 — 麒麟安全智能运维 Agent
# 用途: 撤销 deploy/scripts/seed-demo.sh 创建的所有数据
# 警告: 不会删除 /var/lib/mysql/ 下的真实数据(仅删除脚本创建的 README 标记)
# ============================================================

set -euo pipefail

APP_LOG_PATH="/var/log/app.log"
CACHE_DEMO_DIR="/tmp/cache-demo"
SENSITIVE_PATH="/var/lib/mysql"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

echo "=========================================="
echo " 麒麟安全智能运维 Agent — 演示数据清理"
echo "=========================================="
echo ""

# ---- 操作系统检查 ----
OS_NAME=$(uname -s 2>/dev/null || echo "Unknown")
if [ "$OS_NAME" != "Linux" ]; then
    echo -e "${RED}[FATAL]${NC} 当前系统 ${OS_NAME} 不支持 cleanup"
    exit 1
fi

# ---- 权限检查 ----
if [ "$(id -u)" -ne 0 ]; then
    echo -e "${RED}[ERROR]${NC} 需要 root 权限(请用 sudo 执行)"
    exit 1
fi

# ---- 1. 删除 /var/log/app.log ----
if [ -f "$APP_LOG_PATH" ]; then
    SIZE=$(du -h "$APP_LOG_PATH" | awk '{print $1}')
    echo -e "${CYAN}[1/3]${NC} 删除 ${APP_LOG_PATH} (${SIZE})..."
    rm -f "$APP_LOG_PATH"
    echo -e "  ${GREEN}OK${NC}"
else
    echo -e "${YELLOW}[SKIP]${NC} ${APP_LOG_PATH} 不存在"
fi

# ---- 2. 删除 /tmp/cache-demo/ ----
if [ -d "$CACHE_DEMO_DIR" ]; then
    SIZE=$(du -sh "$CACHE_DEMO_DIR" | awk '{print $1}')
    echo -e "${CYAN}[2/3]${NC} 删除 ${CACHE_DEMO_DIR} (${SIZE})..."
    rm -rf "$CACHE_DEMO_DIR"
    echo -e "  ${GREEN}OK${NC}"
else
    echo -e "${YELLOW}[SKIP]${NC} ${CACHE_DEMO_DIR} 不存在"
fi

# ---- 3. 仅清理 seed-demo.sh 创建的标记(不删真实数据) ----
if [ -d "$SENSITIVE_PATH" ]; then
    if [ -f "${SENSITIVE_PATH}/README.txt" ] && grep -q "DEMO MARKER" "${SENSITIVE_PATH}/README.txt"; then
        echo -e "${CYAN}[3/3]${NC} 删除 ${SENSITIVE_PATH} (seed-demo 创建的标记目录)..."
        # 仅当目录"看起来"是空时删除,避免误删真实 MySQL 数据
        if [ -z "$(ls -A "$SENSITIVE_PATH" 2>/dev/null | grep -v 'README.txt')" ]; then
            rm -rf "$SENSITIVE_PATH"
            echo -e "  ${GREEN}OK${NC} 目录为空(仅含标记文件),已删除"
        else
            echo -e "  ${YELLOW}WARN${NC} 目录含其他文件,仅删除标记文件"
            rm -f "${SENSITIVE_PATH}/README.txt"
        fi
    else
        echo -e "${YELLOW}[SKIP]${NC} ${SENSITIVE_PATH} 含真实数据(非 seed-demo 创建),不动"
    fi
else
    echo -e "${YELLOW}[SKIP]${NC} ${SENSITIVE_PATH} 不存在"
fi

echo ""
echo -e "${GREEN}✓ 清理完成${NC}"
echo "  验证: df -h / | tail -1"