#!/bin/bash
# ============================================================
# Standalone 启动脚本 — 麒麟安全智能运维 Agent
#
# 前置条件：已执行 bash deploy/scripts/build-standalone.sh
# 启动后访问 http://localhost:8080
# ============================================================

set -e

APP_NAME="kylin-ops-guard"
JAR_FILE="backend/target/${APP_NAME}.jar"

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

# ---- 2. 检查 JAR ----
if [ ! -f "${JAR_FILE}" ]; then
    echo -e "${RED}[ERROR] 未找到 ${JAR_FILE}${NC}"
    echo "请先执行 bash deploy/scripts/build-standalone.sh"
    exit 1
fi

# ---- 3. Profile 选择 ----
# 默认 dev + standalone（H2 文件模式，无需额外配置）
# 如需 prod + PostgreSQL，设置环境变量：
#   SPRING_PROFILES_ACTIVE=prod,standalone
PROFILE="${SPRING_PROFILES_ACTIVE:-dev,standalone}"

# ---- 4. 解析 JAVA（复用 start-backend.sh 的 resolve_java 逻辑）----
resolve_java() {
    if command -v java >/dev/null 2>&1 && java -version >/dev/null 2>&1; then
        command -v java
        return 0
    fi
    if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
        echo "${JAVA_HOME}/bin/java"
        return 0
    fi
    local candidate
    for candidate in \
        "/d/Program Files/Java/jdk-23/bin/java" \
        "/d/Program Files/Java/jdk-17/bin/java" \
        "/c/Program Files/Java/jdk-17/bin/java"; do
        if [ -x "${candidate}.exe" ] || [ -x "${candidate}" ]; then
            echo "${candidate}"
            return 0
        fi
    done
    return 1
}

if ! JAVA="$(resolve_java)"; then
    echo -e "${RED}[ERROR] 未找到可用的 java。请安装 JDK 17/23 或设置 JAVA_HOME${NC}"
    exit 1
fi
echo "Java: $("${JAVA}" -version 2>&1 | head -1)"

# ---- 5. 启动 ----
echo ""
echo "=========================================="
echo " ${APP_NAME} — Standalone 启动"
echo " JAR: ${JAR_FILE}"
echo " Profile: ${PROFILE}"
echo "=========================================="

nohup "${JAVA}" -jar "${JAR_FILE}" \
    --spring.profiles.active="${PROFILE}" \
    > "backend/logs/backend.log" 2>&1 &
PID=$!
echo -e "${GREEN}${APP_NAME} 已启动 (PID: ${PID})${NC}"
echo "日志: backend/logs/backend.log"
echo ""

# ---- 6. 等待健康检查 ----
echo "等待服务启动..."
HEALTH_URL="http://localhost:8080/api/health"
for i in $(seq 1 30); do
    if curl -fsS -m 2 "${HEALTH_URL}" > /dev/null 2>&1; then
        echo ""
        echo -e "${GREEN}服务启动成功！${NC}"
        echo ""
        echo "  🌐 访问地址: http://localhost:8080"
        echo "  🔑 登录账户: admin"
        echo "  🔑 登录密码: test-admin-pwd"
        echo ""
        echo "  提示：生产部署请设置 SPRING_PROFILES_ACTIVE=prod,standalone"
        echo "        并配置 KYLINOPS_ADMIN_PASSWORD_HASH 环境变量"
        echo ""
        exit 0
    fi
    sleep 1
done

echo -e "${RED}[ERROR] 服务启动超时（30s）${NC}"
echo "请查看 backend/logs/backend.log"
exit 1
