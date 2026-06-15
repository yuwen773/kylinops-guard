#!/bin/bash
# ============================================================
# 后端启动脚本 — 麒麟安全智能运维 Agent
#
# 行为：
#   1. 确保当前在 repo 根（`backend/` 与 `deploy/scripts/` 的父目录）
#   2. 检测 JAR 是否过期（任何 src/main/java 改动比 jar 新 → 重打）
#   3. 检测 8080 端口是否被旧 JVM 占用，是则自动 kill
#   4. JAR 缺失则自动 `mvn clean package -DskipTests`
#   5. nohup 启动；最多等 30s 验证 /api/health
# ============================================================

set -e

APP_NAME="kylin-ops-guard"
JAR_FILE="backend/target/${APP_NAME}.jar"
DATA_DIR="data"
LOG_DIR="logs"
CONFIG_DIR="config"
HEALTH_URL="http://localhost:8080/api/health"

RED='\033[0;31m'
GREEN='\033[0.32m'
YELLOW='\033[1;33m'
NC='\033[0m'

# ---- 1. 确保在 repo 根 ----
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# 脚本位于 deploy/scripts/，repo 根是其祖父目录的父目录
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
if [ "$(pwd)" != "${REPO_ROOT}" ]; then
    echo "切换工作目录到 repo 根: ${REPO_ROOT}"
    cd "${REPO_ROOT}"
fi

mkdir -p "backend/${DATA_DIR}" "backend/${LOG_DIR}" "backend/${CONFIG_DIR}"

echo "=========================================="
echo " 麒麟安全智能运维 Agent — 后端启动"
echo " 工作目录: $(pwd)"
echo "=========================================="

# ---- 1.5 解析 JAVA 可执行文件（修复 DEFER-003）----
# Windows dev 上 PATH 里的 java 可能是 Oracle JRE stub（`java -version` 无输出）
# 会让 nohup java -jar 静默失败。解析顺序：PATH 中可用 java → JAVA_HOME → Windows 常见 JDK 路径。
resolve_java() {
    if command -v java >/dev/null 2>&1 && java -version >/dev/null 2>&1; then
        command -v java
        return 0
    fi
    if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
        echo "${JAVA_HOME}/bin/java"
        return 0
    fi
    # Windows + Git Bash 常见路径（dev 常见 JDK 23 / 17 安装位置）
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
    echo "  提示 (Windows dev): PATH 里的 Oracle JRE stub 会静默失败（`java -version` 无输出）"
    echo "  已知可用: D:/Program Files/Java/jdk-23/bin/java.exe (JDK 23)"
    exit 1
fi
echo "Java: $("${JAVA}" -version 2>&1 | head -1)"

# ---- 2. 检测端口占用 & 自动 kill 旧 JVM ----
free_port() {
    local port="$1"
    if command -v lsof >/dev/null 2>&1; then
        ! lsof -iTCP:"${port}" -sTCP:LISTEN -P >/dev/null 2>&1
    elif command -v netstat >/dev/null 2>&1; then
        ! netstat -ano 2>/dev/null | grep -E ":${port} " | grep -q LISTENING
    elif command -v ss >/dev/null 2>&1; then
        ! ss -ltn "( sport = :${port} )" | grep -q LISTEN
    else
        # fallback：直接尝试连接
        ! curl -fsS -m 1 "${HEALTH_URL}" >/dev/null 2>&1
    fi
}

kill_pid() {
    local pid="$1"
    if [ -z "${pid}" ] || [ "${pid}" = "0" ]; then return 0; fi
    if kill -0 "${pid}" 2>/dev/null; then
        echo -e "${YELLOW}检测到 ${APP_NAME} 旧进程 (PID ${pid})，正在终止...${NC}"
        kill "${pid}" 2>/dev/null || true
        # 最多等 5s
        for i in 1 2 3 4 5; do
            sleep 1
            if ! kill -0 "${pid}" 2>/dev/null; then break; fi
        done
        if kill -0 "${pid}" 2>/dev/null; then
            echo -e "${YELLOW}进程 ${pid} 未响应 SIGTERM，发送 SIGKILL${NC}"
            kill -9 "${pid}" 2>/dev/null || true
        fi
    fi
}

find_holder_pid() {
    local port="$1"
    if command -v lsof >/dev/null 2>&1; then
        lsof -tiTCP:"${port}" -sTCP:LISTEN -P 2>/dev/null | head -1
    elif command -v netstat >/dev/null 2>&1; then
        # Windows + Git Bash: netstat -ano 输出包含 PID 在最后一列
        netstat -ano 2>/dev/null | grep -E ":${port} " | grep LISTENING | awk '{print $NF}' | head -1
    elif command -v ss >/dev/null 2>&1; then
        ss -ltnp "( sport = :${port} )" 2>/dev/null | grep -oE 'pid=[0-9]+' | head -1 | cut -d= -f2
    fi
}

if ! free_port 8080; then
    holder=$(find_holder_pid 8080)
    if [ -n "${holder}" ]; then
        # 仅当 holder 是 java -jar kylin-ops-guard 时自动 kill；其他进程不动
        cmdline=$(ps -p "${holder}" -o command= 2>/dev/null || true)
        if echo "${cmdline}" | grep -q "kylin-ops-guard"; then
            kill_pid "${holder}"
            sleep 1
        else
            echo -e "${RED}端口 8080 被其他进程占用 (PID ${holder}: ${cmdline})${NC}"
            echo "请手动处理后重试，或修改 server.port。"
            exit 1
        fi
    else
        echo -e "${YELLOW}端口 8080 已被占用，但无法确定占用进程。${NC}"
        echo "请执行 'lsof -i :8080' / 'netstat -ano | grep 8080' 排查。"
        exit 1
    fi
fi

# ---- 3. 检测 stale jar vs 源码 ----
is_jar_stale() {
    local jar="$1"
    [ -f "${jar}" ] || return 0   # 缺失视为过期
    local jar_mtime
    jar_mtime=$(stat -c %Y "${jar}" 2>/dev/null || stat -f %m "${jar}" 2>/dev/null || echo 0)
    # 任意 src/main/java/com/kylinops/**/*.java 比 jar 新 → 视为过期
    local newest_src=0
    if command -v find >/dev/null 2>&1; then
        while IFS= read -r f; do
            [ -z "${f}" ] && continue
            local mt
            mt=$(stat -c %Y "${f}" 2>/dev/null || stat -f %m "${f}" 2>/dev/null || echo 0)
            [ "${mt}" -gt "${newest_src}" ] && newest_src="${mt}"
        done < <(find backend/src -type f -name '*.java' 2>/dev/null)
    fi
    [ "${newest_src}" -gt "${jar_mtime}" ]
}

if is_jar_stale "${JAR_FILE}"; then
    reason="缺失"
    [ -f "${JAR_FILE}" ] && reason="源码比 jar 新"
    echo -e "${YELLOW}${APP_NAME}.jar ${reason}，开始 mvn clean package...${NC}"
    (cd backend && mvn -B -q clean package -DskipTests)
fi

if [ ! -f "${JAR_FILE}" ]; then
    echo -e "${RED}编译后仍未找到 ${JAR_FILE}，请检查 Maven 依赖。${NC}"
    exit 1
fi

# ---- 4. 启动 ----
# Spring profile 选择：
#   - 默认 dev：H2 文件模式 + Flyway 禁用 + ddl-auto=update + 默认 BCrypt 密码哈希（首次跑 README 即可登录）
#   - 显式覆盖：通过环境变量 SPRING_PROFILES_ACTIVE=test|prod
#   - 修复 DEFER-002：原值 "default" 会让 base yml 缺 datasource + Flyway 占位符 ${lob_type} 未定义 → 启动失败
PROFILE="${SPRING_PROFILES_ACTIVE:-dev}"
echo "启动 ${APP_NAME} (profile: ${PROFILE}) ..."
cd backend
nohup "${JAVA}" -jar "target/${APP_NAME}.jar" \
    --spring.profiles.active="${PROFILE}" \
    > "${LOG_DIR}/backend.log" 2>&1 &
PID=$!
cd ..
echo -e "${GREEN}${APP_NAME} 已启动 (PID: ${PID})${NC}"
echo "日志文件: backend/${LOG_DIR}/backend.log"
echo "数据目录: backend/${DATA_DIR}/"
echo "Spring profile: ${PROFILE}（可通过 SPRING_PROFILES_ACTIVE 环境变量覆盖）"
echo ""

# ---- 5. 等待健康检查 ----
echo "等待服务启动..."
for i in $(seq 1 30); do
    if curl -fsS -m 2 "${HEALTH_URL}" > /dev/null 2>&1; then
        echo -e "${GREEN}服务启动成功！${NC}"
        curl -sS "${HEALTH_URL}" 2>/dev/null || true
        echo ""
        exit 0
    fi
    sleep 1
done

echo -e "${RED}[ERROR]${NC} 服务启动超时（30s），请检查 backend/${LOG_DIR}/backend.log"
exit 1
