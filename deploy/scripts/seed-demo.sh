#!/bin/bash
# ============================================================
# 演示数据 seeding 脚本 — 麒麟安全智能运维 Agent
# 用途: 准备演示视频脚本 §3.3 与 test-scenarios/ 所需的演示数据
# ============================================================
# 使用方法:
#   sudo bash deploy/scripts/seed-demo.sh              # 默认 200MB app.log
#   sudo bash deploy/scripts/seed-demo.sh --size-mb 50 # 自定义大小
#   sudo bash deploy/scripts/seed-demo.sh --dry-run    # 仅打印计划
# 清理方法:
#   sudo bash deploy/scripts/seed-demo-cleanup.sh
# ============================================================
# 警告:
#   - 本脚本会创建 /var/log/app.log 与 /tmp/cache-demo/,需要 root 权限
#   - 本脚本不会修改任何真实系统服务 / 配置 / 数据库
#   - 本脚本不会安装 nginx;只验证 nginx 是否存在
#   - 在 Kylin / LoongArch / Ubuntu / CentOS / RHEL 上验证过
#   - 在 WSL2 / macOS / 容器 中可能失败(仅支持真实 Linux 主机)
# ============================================================

set -euo pipefail

# ---- 默认配置 ----
APP_LOG_PATH="/var/log/app.log"
CACHE_DEMO_DIR="/tmp/cache-demo"
SENSITIVE_PATH="/var/lib/mysql"
APP_LOG_SIZE_MB=200
CACHE_FILES_COUNT=8
CACHE_FILE_AVG_SIZE_KB=512
DRY_RUN=false
FORCE=false

# ---- 颜色 ----
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

# ---- 参数解析 ----
while [ $# -gt 0 ]; do
    case "$1" in
        --size-mb)
            APP_LOG_SIZE_MB="$2"
            shift 2
            ;;
        --size-mb=*)
            APP_LOG_SIZE_MB="${1#*=}"
            shift
            ;;
        --dry-run)
            DRY_RUN=true
            shift
            ;;
        --force)
            FORCE=true
            shift
            ;;
        -h|--help)
            echo "用法: sudo bash $0 [--size-mb N] [--dry-run] [--force]"
            echo "  --size-mb N : app.log 大小(默认 200MB)"
            echo "  --dry-run   : 仅打印计划,不实际写入"
            echo "  --force     : 跳过磁盘空间警告"
            exit 0
            ;;
        *)
            echo -e "${RED}[ERROR]${NC} 未知参数: $1"
            exit 1
            ;;
    esac
done

# ---- 横幅 ----
echo "=========================================="
echo " 麒麟安全智能运维 Agent — 演示数据 Seeding"
echo "=========================================="
echo ""

# ---- 操作系统检查 ----
OS_NAME=$(uname -s 2>/dev/null || echo "Unknown")
if [ "$OS_NAME" != "Linux" ]; then
    echo -e "${RED}[FATAL]${NC} 当前系统 ${OS_NAME} 不支持 seeding"
    echo "  本脚本仅支持真实 Linux 主机(麒麟 / Ubuntu / CentOS / RHEL 等)"
    echo "  开发机(Windows / macOS)请使用 'deploy/scripts/start-*.sh' 直接启动,无需 seeding"
    exit 1
fi

# ---- 权限检查 ----
if [ "$(id -u)" -ne 0 ]; then
    echo -e "${RED}[ERROR]${NC} 需要 root 权限(请用 sudo 执行)"
    exit 1
fi

# ---- 计划打印 ----
echo -e "${CYAN}计划:${NC}"
echo "  • ${APP_LOG_PATH}: ${APP_LOG_SIZE_MB}MB 随机字节(磁盘异常根因)"
echo "  • ${CACHE_DEMO_DIR}: ${CACHE_FILES_COUNT} 个文件,平均 ${CACHE_FILE_AVG_SIZE_KB}KB(可清理缓存)"
echo "  • ${SENSITIVE_PATH}: 目录标记(用于演示 large_file_scan_tool 标记为敏感路径)"
echo ""

if [ "$DRY_RUN" = true ]; then
    echo -e "${YELLOW}[DRY-RUN]${NC} 仅打印计划,不实际写入"
    echo ""
    echo "完成后实际执行: sudo bash $0"
    exit 0
fi

# ---- 磁盘空间检查 ----
VAR_LOG_FS=$(df --output=target "$APP_LOG_PATH" 2>/dev/null | tail -1 || echo "/")
AVAIL_MB=$(df --output=avail -m "$APP_LOG_PATH" 2>/dev/null | tail -1 | tr -d ' ' || echo 0)
NEEDED_MB=$((APP_LOG_SIZE_MB + 50))

if [ "$AVAIL_MB" -lt "$NEEDED_MB" ] && [ "$FORCE" = false ]; then
    echo -e "${YELLOW}[WARN]${NC} ${VAR_LOG_FS} 剩余 ${AVAIL_MB}MB,需要至少 ${NEEDED_MB}MB"
    echo "  使用 --force 跳过此检查(可能写满磁盘)"
    exit 1
fi

# ---- 1. 创建 /var/log/app.log ----
if [ -f "$APP_LOG_PATH" ]; then
    EXIST_MB=$(( $(stat -c%s "$APP_LOG_PATH") / 1024 / 1024 ))
    echo -e "${YELLOW}[SKIP]${NC} ${APP_LOG_PATH} 已存在(${EXIST_MB}MB),跳过"
else
    echo -e "${CYAN}[1/3]${NC} 创建 ${APP_LOG_PATH} (${APP_LOG_SIZE_MB}MB)..."
    # 使用 dd + /dev/urandom 创建随机字节文件(无敏感内容)
    dd if=/dev/urandom of="$APP_LOG_PATH" bs=1M count="$APP_LOG_SIZE_MB" status=none
    chmod 644 "$APP_LOG_PATH"
    echo -e "  ${GREEN}OK${NC} $(ls -lh "$APP_LOG_PATH" | awk '{print $5}')"
fi

# ---- 2. 创建 /tmp/cache-demo/ ----
if [ -d "$CACHE_DEMO_DIR" ]; then
    FILE_COUNT=$(find "$CACHE_DEMO_DIR" -type f | wc -l)
    echo -e "${YELLOW}[SKIP]${NC} ${CACHE_DEMO_DIR} 已存在(${FILE_COUNT} 个文件),跳过"
else
    echo -e "${CYAN}[2/3]${NC} 创建 ${CACHE_DEMO_DIR}..."
    mkdir -p "$CACHE_DEMO_DIR"
    chmod 755 "$CACHE_DEMO_DIR"
    for i in $(seq 1 $CACHE_FILES_COUNT); do
        dd if=/dev/urandom of="${CACHE_DEMO_DIR}/cache-${i}.dat" \
            bs=1K count=$((CACHE_FILE_AVG_SIZE_KB + RANDOM % 512)) status=none
    done
    echo -e "  ${GREEN}OK${NC} $(find "$CACHE_DEMO_DIR" -type f | wc -l) 个文件,$(du -sh "$CACHE_DEMO_DIR" | awk '{print $1}')"
fi

# ---- 3. 标记 /var/lib/mysql/ ----
if [ -d "$SENSITIVE_PATH" ]; then
    echo -e "${YELLOW}[SKIP]${NC} ${SENSITIVE_PATH} 已存在,跳过(说明:真实 MySQL 数据目录存在)"
else
    echo -e "${CYAN}[3/3]${NC} 创建 ${SENSITIVE_PATH} 标记目录..."
    mkdir -p "$SENSITIVE_PATH"
    cat > "${SENSITIVE_PATH}/README.txt" <<EOF
DEMO MARKER FILE — DO NOT DELETE
This directory is created by deploy/scripts/seed-demo.sh as a sensitive path marker.
The large_file_scan_tool will flag this directory but never propose it for deletion.
Remove with: sudo bash deploy/scripts/seed-demo-cleanup.sh
EOF
    chmod 755 "$SENSITIVE_PATH"
    echo -e "  ${GREEN}OK${NC} 已创建(仅含 README 标记,无真实数据)"
fi

# ---- 磁盘使用率报告 ----
echo ""
echo "=========================================="
echo " Seeding 完成 — 当前状态"
echo "=========================================="
df -h "$APP_LOG_PATH" 2>/dev/null | tail -1 | awk '{printf "  %-20s %s 已用 / %s 总计 / %s 可用 (%s)\n", $1, $3, $2, $4, $5}'
echo ""

# ---- nginx 状态检查(不安装) ----
echo -e "${CYAN}nginx 服务状态:${NC}"
if command -v systemctl &>/dev/null; then
    if systemctl list-unit-files nginx.service &>/dev/null; then
        NGINX_STATE=$(systemctl is-active nginx 2>/dev/null || echo "unknown")
        NGINX_ENABLED=$(systemctl is-enabled nginx 2>/dev/null || echo "unknown")
        echo "  状态: ${NGINX_STATE}"
        echo "  启用: ${NGINX_ENABLED}"
        echo "  → 演示场景 3 可用"
    else
        echo -e "  ${YELLOW}nginx.service 未安装${NC}"
        echo "  → 演示场景 3 需要先安装:"
        echo "      麒麟/CentOS: sudo yum install -y nginx"
        echo "      Ubuntu/Debian: sudo apt-get install -y nginx"
        echo "  → 不影响演示场景 1/2/4/5"
    fi
else
    echo -e "  ${YELLOW}systemctl 不可用${NC} (容器或非 systemd 环境)"
    echo "  → 演示场景 3 在本机无法演示,但场景 1/2/4/5 仍可用"
fi

# ---- 总结 ----
echo ""
echo -e "${GREEN}✓ Seeding 完成${NC}"
echo "  下一步:"
echo "    1. bash deploy/scripts/start-backend.sh"
echo "    2. bash deploy/scripts/start-frontend.sh"
echo "    3. 按 test-scenarios/*.md 顺序演示"
echo ""
echo "  完成后清理:"
echo "    sudo bash deploy/scripts/seed-demo-cleanup.sh"