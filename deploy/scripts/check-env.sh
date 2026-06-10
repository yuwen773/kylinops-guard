#!/bin/bash
# ============================================================
# 环境检查脚本 — 麒麟安全智能运维 Agent
# 验证运行环境的依赖是否满足要求
# ============================================================

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo "=========================================="
echo " 麒麟安全智能运维 Agent — 环境检查"
echo " v0.1.0"
echo "=========================================="
echo ""

# ---- Java ----
echo -n "检查 Java ... "
if command -v java &> /dev/null; then
    JAVA_VERSION=$(java -version 2>&1 | head -1 | cut -d'"' -f2 | cut -d'.' -f1)
    if [ "$JAVA_VERSION" -ge 17 ] 2>/dev/null; then
        echo -e "${GREEN}OK${NC} ($(java -version 2>&1 | head -1))"
    else
        echo -e "${YELLOW}WARN${NC} (需要 Java 17+, 当前: $(java -version 2>&1 | head -1))"
    fi
else
    echo -e "${RED}MISSING${NC} (需要 Java 17+)"
fi

# ---- Maven ----
echo -n "检查 Maven ... "
if command -v mvn &> /dev/null; then
    echo -e "${GREEN}OK${NC} ($(mvn --version 2>&1 | head -1))"
else
    echo -e "${RED}MISSING${NC} (编译需要 Maven)"
fi

# ---- Node.js (前端) ----
echo -n "检查 Node.js ... "
if command -v node &> /dev/null; then
    echo -e "${GREEN}OK${NC} ($(node --version))"
else
    echo -e "${YELLOW}INFO${NC} (前端开发需要 Node.js 18+)"
fi

# ---- npm ----
echo -n "检查 npm ... "
if command -v npm &> /dev/null; then
    echo -e "${GREEN}OK${NC} ($(npm --version))"
else
    echo -e "${YELLOW}INFO${NC} (前端构建需要 npm)"
fi

# ---- 操作系统 ----
echo -n "检查操作系统 ... "
OS_NAME=$(uname -s 2>/dev/null || echo "Unknown")
ARCH=$(uname -m 2>/dev/null || echo "Unknown")
echo -e "${GREEN}${OS_NAME} / ${ARCH}${NC}"
if [ "$OS_NAME" = "Linux" ] && [ "$ARCH" = "loongarch64" ]; then
    echo "  → 目标环境: 麒麟 Advanced Server V11 (LoongArch64) ✓"
elif [ "$OS_NAME" = "Linux" ]; then
    echo "  → 开发环境: Linux (非 LoongArch — 部分 OS 依赖需模拟)"
else
    echo "  → 非 Linux 环境 — OS 工具将以模拟模式运行"
fi

echo ""
echo "=========================================="
echo " 环境检查完成"
echo "=========================================="
