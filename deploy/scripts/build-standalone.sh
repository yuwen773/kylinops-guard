#!/bin/bash
# ============================================================
# Standalone 单 JAR 构建脚本 — 麒麟安全智能运维 Agent
#
# 构建流程：
#   1. npm ci + npm run build（构建前端）
#   2. 复制 frontend/dist/ → backend/src/main/resources/static/
#   3. mvn clean package -DskipTests
#   4. 清理 static/（不污染工作树）
#
# 启动：
#   java -jar backend/target/kylin-ops-guard.jar \
#     --spring.profiles.active=prod,standalone
#
# 兼容性：Windows (Git Bash) / Linux / macOS
# ============================================================

set -e

APP_NAME="kylin-ops-guard"
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

# ---- 1. 确保在 repo 根 ----
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
if [ "$(pwd)" != "${REPO_ROOT}" ]; then
    echo "切换工作目录到 repo 根: ${REPO_ROOT}"
    cd "${REPO_ROOT}"
fi

echo "=========================================="
echo " ${APP_NAME} — Standalone 单 JAR 构建"
echo " 工作目录: $(pwd)"
echo "=========================================="

# ---- 2. 构建前端 ----
echo ""
echo "--- Step 1/4: 构建前端 ---"
if [ ! -d "frontend/node_modules" ]; then
    echo "安装前端依赖 (npm ci)..."
    (cd frontend && npm ci)
fi

echo "构建前端 (npm run build)..."
(cd frontend && npm run build)

echo -e "${GREEN}前端构建完成 ✅${NC}"

# ---- 3. 复制前端资源 ----
echo ""
echo "--- Step 2/4: 复制前端到后端 ---"
STATIC_DIR="backend/src/main/resources/static"
mkdir -p "${STATIC_DIR}"
cp -r frontend/dist/* "${STATIC_DIR}/"
echo "已复制 $(find "${STATIC_DIR}" -type f | wc -l) 个文件到 ${STATIC_DIR}"

# ---- 4. 打包后端 JAR ----
echo ""
echo "--- Step 3/4: 打包后端 JAR ---"
(cd backend && mvn clean package -DskipTests)
echo -e "${GREEN}JAR 打包完成 ✅${NC}"

JAR_FILE="backend/target/${APP_NAME}.jar"
if [ -f "${JAR_FILE}" ]; then
    JAR_SIZE=$(ls -lh "${JAR_FILE}" | awk '{print $5}')
    echo "  ${JAR_FILE} (${JAR_SIZE})"
fi

# ---- 5. 清理临时 static/ ----
echo ""
echo "--- Step 4/4: 清理临时 static/ ---"
rm -rf "${STATIC_DIR}"
echo "已清理 ${STATIC_DIR}"

# ---- 6. 完成 ----
echo ""
echo "=========================================="
echo -e "${GREEN} Standalone 单 JAR 构建完成！${NC}"
echo ""
echo "启动命令："
echo "  java -jar backend/target/${APP_NAME}.jar \\"
echo "    --spring.profiles.active=prod,standalone"
echo ""
echo "或搭配 H2（开发 / 演示）："
echo "  java -jar backend/target/${APP_NAME}.jar \\"
echo "    --spring.profiles.active=dev,standalone"
echo ""
echo "访问：http://localhost:8080"
echo "=========================================="
