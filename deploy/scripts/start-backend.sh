#!/bin/bash
# ============================================================
# 后端启动脚本 — 麒麟安全智能运维 Agent
# ============================================================

set -e

APP_NAME="kylin-ops-guard"
JAR_FILE="backend/target/${APP_NAME}.jar"
DATA_DIR="data"
LOG_DIR="logs"
CONFIG_DIR="config"

# 颜色
RED='\033[0;31m'
GREEN='\033[0;32m'
NC='\033[0m'

echo "=========================================="
echo " 麒麟安全智能运维 Agent — 后端启动"
echo "=========================================="

# 确保目录存在
mkdir -p "${DATA_DIR}" "${LOG_DIR}" "${CONFIG_DIR}"

# 检查 JAR 是否存在，不存在则编译
if [ ! -f "${JAR_FILE}" ]; then
    echo "未找到 ${JAR_FILE}，开始编译..."
    cd backend && mvn clean package -DskipTests
    cd ..
    if [ ! -f "${JAR_FILE}" ]; then
        echo -e "${RED}[ERROR]${NC} 编译失败，请检查 Maven 依赖"
        exit 1
    fi
fi

echo "启动 ${APP_NAME} ..."
nohup java -jar "${JAR_FILE}" \
    --spring.profiles.active=default \
    > "${LOG_DIR}/backend.log" 2>&1 &

PID=$!
echo -e "${GREEN}${APP_NAME} 已启动 (PID: ${PID})${NC}"
echo "日志文件: ${LOG_DIR}/backend.log"
echo ""

# 等待服务启动
echo "等待服务启动..."
for i in $(seq 1 30); do
    if curl -s http://localhost:8080/api/health > /dev/null 2>&1; then
        echo -e "${GREEN}服务启动成功！${NC}"
        curl -s http://localhost:8080/api/health | python3 -m json.tool 2>/dev/null || echo ""
        exit 0
    fi
    sleep 1
done

echo -e "${RED}[ERROR]${NC} 服务启动超时，请检查日志"
exit 1
