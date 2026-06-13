#!/bin/bash
# ============================================================
# 一键更新脚本 — 麒麟安全智能运维 Agent
# ============================================================
# 用法:
#   bash deploy/scripts/update.sh                  # 从 GitHub Releases 拉最新版
#   bash deploy/scripts/update.sh --tag v0.4.0     # 指定版本
#   bash deploy/scripts/update.sh --dry-run        # 仅打印计划
#   bash deploy/scripts/update.sh --rollback       # 回滚到上一个版本
# ============================================================
# 行为:
#   1. 检查 JDK 17+ / curl / tar
#   2. 从 GitHub Releases 下载最新 tarball
#   3. 备份当前版本 (backup/kylinops-guard.prev.tar.gz)
#   4. 停止旧后端进程
#   5. 解压并替换 JAR + dist + deploy scripts
#   6. 启动新后端
#   7. 验证 /api/health
#   8. 失败时自动回滚
# ============================================================
# 注意事项:
#   - 脚本不需要仓库 clone 来更新,只要有 deploy/ 目录即可
#   - GitHub Release 是公开 URL,不需要 token
#   - 首次部署后,后续只需下载 tarball 替换即可
# ============================================================

set -euo pipefail

# ---- 仓库配置 ----
REPO_OWNER="yuwen773"
REPO_NAME="kylinops-guard-"
RELEASE_API="https://api.github.com/repos/${REPO_OWNER}/${REPO_NAME}/releases"
DOWNLOAD_BASE="https://github.com/${REPO_OWNER}/${REPO_NAME}/releases/download"

# ---- 路径 ----
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
BACKUP_DIR="${REPO_ROOT}/backup"
DATA_DIR="${REPO_ROOT}/data"
LOG_DIR="${REPO_ROOT}/logs"
CONFIG_DIR="${REPO_ROOT}/config"
TARBALL="kylinops-guard.tar.gz"
HEALTH_URL="http://localhost:8080/api/health"
APP_PATTERN="kylin-ops-guard"

# ---- 颜色 ----
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

# ---- 状态 ----
DRY_RUN=false
SPECIFIC_TAG=""
ROLLBACK=false

# ---- 参数解析 ----
while [ $# -gt 0 ]; do
    case "$1" in
        --tag)
            SPECIFIC_TAG="$2"
            shift 2
            ;;
        --tag=*)
            SPECIFIC_TAG="${1#*=}"
            shift
            ;;
        --dry-run)
            DRY_RUN=true
            shift
            ;;
        --rollback)
            ROLLBACK=true
            shift
            ;;
        -h|--help)
            echo "用法: bash $0 [选项]"
            echo "  --tag TAG   : 指定版本标签(默认最新 release)"
            echo "  --dry-run   : 仅打印计划,不实际执行"
            echo "  --rollback  : 回滚到上一个版本"
            exit 0
            ;;
        *)
            echo -e "${RED}[ERROR]${NC} 未知参数: $1"
            exit 1
            ;;
    esac
done

# ============================================================
# 回滚模式
# ============================================================
if [ "$ROLLBACK" = true ]; then
    echo "=========================================="
    echo " 麒麟安全智能运维 Agent — 回滚"
    echo "=========================================="
    PREV_BACKUP="${BACKUP_DIR}/kylinops-guard.prev.tar.gz"
    if [ ! -f "$PREV_BACKUP" ]; then
        echo -e "${RED}[ERROR]${NC} 未找到备份: ${PREV_BACKUP}"
        exit 1
    fi
    echo -e "${CYAN}[1/4]${NC} 停止当前后端..."
    # 复用 stop 逻辑
    PID_FILE="${REPO_ROOT}/backend/PID" 2>/dev/null || true
    if [ -f "$PID_FILE" ]; then
        OLD_PID=$(cat "$PID_FILE")
        kill "$OLD_PID" 2>/dev/null || true
        sleep 2
    fi
    # 也尝试 find by pattern
    OLD_PID=$(ps aux | grep "[k]ylin-ops-guard" | awk '{print $2}' 2>/dev/null || echo "")
    if [ -n "$OLD_PID" ]; then
        kill "$OLD_PID" 2>/dev/null || true
        sleep 2
    fi

    echo -e "${CYAN}[2/4]${NC} 恢复备份..."
    cd "$REPO_ROOT"
    tar xzf "$PREV_BACKUP" -C "$REPO_ROOT"
    echo -e "  ${GREEN}OK${NC}"

    echo -e "${CYAN}[3/4]${NC} 启动后端..."
    if [ "$DRY_RUN" = false ]; then
        bash "${REPO_ROOT}/deploy/scripts/start-backend.sh" || true
    fi
    echo -e "${GREEN}✓ 回滚完成${NC}"
    exit 0
fi

# ============================================================
# 正常更新流程
# ============================================================

echo "=========================================="
echo " 麒麟安全智能运维 Agent — 自动更新"
echo "=========================================="
echo ""

# ---- 1. 检查依赖 ----
echo -e "${CYAN}[检查]${NC} 依赖..."
FAIL=false

if ! command -v java &>/dev/null; then
    echo -e "  ${RED}✗${NC} Java — 未安装,请先安装 JDK 17+"
    FAIL=true
else
    JAVA_VER=$(java -version 2>&1 | head -1 | cut -d'"' -f2 | cut -d'.' -f1)
    if [ "$JAVA_VER" -ge 17 ] 2>/dev/null; then
        echo -e "  ${GREEN}✓${NC} Java $(java -version 2>&1 | head -1)"
    else
        echo -e "  ${RED}✗${NC} Java 版本 ($(java -version 2>&1 | head -1)) 需要 17+"
        FAIL=true
    fi
fi

if ! command -v curl &>/dev/null; then
    echo -e "  ${RED}✗${NC} curl — 未安装"
    FAIL=true
else
    echo -e "  ${GREEN}✓${NC} curl $(curl --version | head -1 | awk '{print $2}')"
fi

if ! command -v tar &>/dev/null; then
    echo -e "  ${RED}✗${NC} tar — 未安装"
    FAIL=true
else
    echo -e "  ${GREEN}✓${NC} tar"
fi

if [ "$FAIL" = true ]; then
    echo -e "${RED}[ERROR]${NC} 请修复上述依赖后重试。"
    exit 1
fi

# ---- 2. 获取版本信息 ----
echo ""
echo -e "${CYAN}[获取]${NC} 版本信息..."

if [ -n "$SPECIFIC_TAG" ]; then
    TAG="$SPECIFIC_TAG"
    echo -e "  → 指定标签: ${TAG}"
else
    echo -e "  → 查询最新 release..."
    TAG=$(curl -fsSL "${RELEASE_API}/latest" 2>/dev/null | python3 -c "import sys,json; print(json.load(sys.stdin)['tag_name'])" 2>/dev/null || echo "")
    if [ -z "$TAG" ]; then
        echo -e "  ${YELLOW}[WARN]${NC} 无法通过 API 获取最新版本,尝试 fallback..."
        # Fallback: 取 tag list 第一个
        TAG=$(curl -fsSL "${RELEASE_API}" 2>/dev/null | python3 -c "import sys,json; releases=json.load(sys.stdin); print(releases[0]['tag_name'])" 2>/dev/null || echo "v0.4-manual-acceptance")
    fi
    echo -e "  → 最新: ${TAG}"
fi

DOWNLOAD_URL="${DOWNLOAD_BASE}/${TAG}/${TARBALL}"
echo -e "  → 下载: ${DOWNLOAD_URL}"

if [ "$DRY_RUN" = true ]; then
    echo ""
    echo -e "${YELLOW}[DRY-RUN]${NC} 计划:"
    echo "  1. 下载 ${TARBALL} → 当前目录"
    echo "  2. 备份当前版本 → ${BACKUP_DIR}/kylinops-guard.prev.tar.gz"
    echo "  3. 停止旧后端"
    echo "  4. 解压 tarball 到 ${REPO_ROOT}"
    echo "  5. 启动新后端"
    echo "  6. 验证 /api/health"
    echo ""
    echo "  实际执行: bash $0${SPECIFIC_TAG:+ --tag $SPECIFIC_TAG}"
    exit 0
fi

# ---- 3. 下载 ----
echo ""
echo -e "${CYAN}[1/6]${NC} 下载 ${TARBALL}..."
cd "$REPO_ROOT"
if curl -fLSo "$TARBALL" "$DOWNLOAD_URL"; then
    echo -e "  ${GREEN}OK${NC} $(ls -lh "$TARBALL" | awk '{print $5}')"
else
    echo -e "${RED}[ERROR]${NC} 下载失败: ${DOWNLOAD_URL}"
    echo "  请检查:"
    echo "    - 网络连接(VM 是否能访问 github.com)"
    echo "    - Tag 是否存在: https://github.com/${REPO_OWNER}/${REPO_NAME}/releases/tag/${TAG}"
    exit 1
fi

# ---- 4. 备份 ----
echo -e "${CYAN}[2/6]${NC} 备份当前版本..."
mkdir -p "$BACKUP_DIR"
BACKUP_FILE="${BACKUP_DIR}/kylinops-guard.prev.tar.gz"
# 如果已有备份,移除旧备份
rm -f "$BACKUP_FILE"
# 备份当前 JAR + dist
BACKUP_TMP=$(mktemp -d)
CLEANUP_BACKUP=true
trap 'rm -rf "$BACKUP_TMP"' EXIT

if [ -f "${REPO_ROOT}/kylin-ops-guard.jar" ]; then
    cp "${REPO_ROOT}/kylin-ops-guard.jar" "${BACKUP_TMP}/"
fi
if [ -d "${REPO_ROOT}/dist" ]; then
    cp -r "${REPO_ROOT}/dist" "${BACKUP_TMP}/dist" 2>/dev/null || true
fi
if [ -d "${REPO_ROOT}/deploy" ]; then
    cp -r "${REPO_ROOT}/deploy" "${BACKUP_TMP}/deploy" 2>/dev/null || true
fi
if [ -d "${REPO_ROOT}/config" ]; then
    cp -r "${REPO_ROOT}/config" "${BACKUP_TMP}/config" 2>/dev/null || true
fi

if ls "${BACKUP_TMP}"/* >/dev/null 2>&1; then
    cd "$BACKUP_TMP"
    tar czf "$BACKUP_FILE" ./*
    cd "$REPO_ROOT"
    echo -e "  ${GREEN}OK${NC} → ${BACKUP_FILE} ($(ls -lh "$BACKUP_FILE" | awk '{print $5}'))"
else
    echo -e "  ${YELLOW}跳过${NC} (无当前版本可备份)"
    BACKUP_FILE=""
fi

# ---- 5. 停止旧进程 ----
echo -e "${CYAN}[3/6]${NC} 停止旧后端..."
OLD_PID=$(ps aux | grep "[k]ylin-ops-guard" | awk '{print $2}' 2>/dev/null || echo "")
if [ -n "$OLD_PID" ]; then
    echo -e "  → PID: ${OLD_PID}"
    kill "$OLD_PID" 2>/dev/null || true
    for i in 1 2 3 4 5; do
        sleep 1
        if ! kill -0 "$OLD_PID" 2>/dev/null; then
            echo -e "  ${GREEN}✓${NC} 已停止"
            break
        fi
    done
    # Force kill if still alive
    if kill -0 "$OLD_PID" 2>/dev/null; then
        kill -9 "$OLD_PID" 2>/dev/null || true
        echo -e "  ${YELLOW}⚠ 强制终止${NC}"
    fi
else
    echo -e "  ${YELLOW}无运行中的后端${NC}"
fi

# ---- 6. 解压替换 ----
echo -e "${CYAN}[4/6]${NC} 部署新版本..."
cd "$REPO_ROOT"

# 解压 tarball (不覆盖 deploy/scripts/update.sh 自身的 symlinks, 不覆盖 data/ logs/)
tar xzf "$TARBALL" \
    --skip-old-files \
    --warning=no-unknown-keyword 2>/dev/null || true

# 确保关键目录存在
mkdir -p "${DATA_DIR}" "${LOG_DIR}" "${CONFIG_DIR}"

# 复制配置(不覆盖已有)
if [ -d config ]; then
    for f in config/*.yml; do
        fname=$(basename "$f")
        if [ ! -f "${CONFIG_DIR}/${fname}" ]; then
            cp "$f" "${CONFIG_DIR}/"
            echo -e "  → 配置: ${fname}"
        fi
    done
fi

# 记录版本
echo "${TAG} ($(date -u +'%Y-%m-%dT%H:%M:%SZ'))" >> VERSION
echo -e "  ${GREEN}OK${NC}"

# ---- 7. 启动 ----
echo -e "${CYAN}[5/6]${NC} 启动新后端..."
bash "${REPO_ROOT}/deploy/scripts/start-backend.sh" || true

# ---- 8. 验证 ----
echo ""
echo -e "${CYAN}[6/6]${NC} 验证服务..."
for i in $(seq 1 15); do
    if curl -fsS -m 2 "${HEALTH_URL}" > /dev/null 2>&1; then
        echo -e "  ${GREEN}✓${NC} 服务正常运行:"
        curl -sS "${HEALTH_URL}" | python3 -m json.tool 2>/dev/null || curl -sS "${HEALTH_URL}"
        echo ""
        echo -e "${GREEN}=========================================="
        echo " ✓ 更新完成 (${TAG})"
        echo "==========================================${NC}"
        echo "  新版本: kylin-ops-guard.jar + dist/"
        echo "  数据: ${DATA_DIR}"
        echo "  日志: ${LOG_DIR}/backend.log"
        echo "  备份: ${BACKUP_FILE}"
        echo ""
        echo "  需要回滚: bash deploy/scripts/update.sh --rollback"
        exit 0
    fi
    sleep 1
done

# ---- 失败 — 自动回滚 ----
echo -e "${RED}[ERROR]${NC} 服务启动失败,自动回滚..."
if [ -n "$BACKUP_FILE" ] && [ -f "$BACKUP_FILE" ]; then
    # 清理解压的文件
    rm -f "${REPO_ROOT}/kylin-ops-guard.jar" 2>/dev/null || true
    rm -rf "${REPO_ROOT}/dist" 2>/dev/null || true
    # 恢复备份
    tar xzf "$BACKUP_FILE" -C "$REPO_ROOT"
    echo -e "${YELLOW}→ 已回滚到备份版本,重新启动...${NC}"
    bash "${REPO_ROOT}/deploy/scripts/start-backend.sh" || true
fi
exit 1
