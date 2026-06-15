#!/bin/bash
# ============================================================
# PostgreSQL 恢复脚本 — 麒麟安全智能运维 Agent
# ============================================================
# 用法:
#   sudo bash deploy/scripts/restore-postgres.sh \
#     --source /var/lib/kylinops/backup/postgres-20260615T120000Z.dump \
#     --target-db kylinops_restore \
#     --confirm-restore
#
#   # 默认拒绝 --target-db kylinops; 若必须恢复生产库, 必须额外加 --allow-prod
#   sudo bash deploy/scripts/restore-postgres.sh \
#     --source /var/lib/kylinops/backup/postgres-20260615T120000Z.dump \
#     --target-db kylinops \
#     --allow-prod \
#     --confirm-restore
#
# 强制参数:
#   --source <path>      备份文件路径 (必须存在; 必须在白名单 /var/lib/kylinops/backup/ 内;
#                        拒绝 .., 拒绝符号链接逃逸, 拒绝 /etc /usr /bin /boot 路径)
#   --target-db <name>   目标数据库名 (kylinops 默认拒绝; 须 --allow-prod)
#   --confirm-restore    显式确认; 缺则拒绝运行
#
# 安全:
#   * 永不删除任意路径 — 只 --clean via pg_restore
#   * source 路径白名单校验 (realpath)
#   * 目标 db 名格式校验 (^[a-zA-Z_][a-zA-Z0-9_]*$)
#   * set -euo pipefail
# ============================================================

set -euo pipefail

# ---- 默认值 ----
ENV_FILE="${ENV_FILE:-/etc/kylinops/kylinops.env}"
ALLOWED_DIR="${ALLOWED_DIR:-/var/lib/kylinops/backup}"

# ---- 颜色 ----
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log() { echo -e "${GREEN}[$(date -u +'%Y-%m-%dT%H:%M:%SZ')]${NC} $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $*" >&2; }
err() { echo -e "${RED}[ERROR]${NC} $*" >&2; }

# ---- 帮助 ----
if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
    sed -n '2,30p' "$0"
    exit 0
fi

# ---- 解析参数 ----
SOURCE=""
TARGET_DB=""
CONFIRM=false
ALLOW_PROD=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --source)
            [[ $# -ge 2 ]] || { err "--source 需要参数"; exit 2; }
            SOURCE="$2"
            shift 2
            ;;
        --target-db)
            [[ $# -ge 2 ]] || { err "--target-db 需要参数"; exit 2; }
            TARGET_DB="$2"
            shift 2
            ;;
        --confirm-restore)
            CONFIRM=true
            shift
            ;;
        --allow-prod)
            ALLOW_PROD=true
            shift
            ;;
        *)
            err "未知参数: $1"
            exit 2
            ;;
    esac
done

# ---- 校验必填 ----
if [[ "${CONFIRM}" != "true" ]]; then
    err "缺 --confirm-restore; 拒绝运行"
    err "  这是破坏性操作 — 必须显式确认"
    err "  用法: $0 --source <dump> --target-db <dbname> --confirm-restore"
    exit 1
fi

if [[ -z "${SOURCE}" ]]; then
    err "--source 必填"
    exit 2
fi

if [[ -z "${TARGET_DB}" ]]; then
    err "--target-db 必填"
    exit 2
fi

# 目标 db 名格式校验 (白名单字符, 防止注入)
if [[ ! "${TARGET_DB}" =~ ^[a-zA-Z_][a-zA-Z0-9_]*$ ]]; then
    err "非法 --target-db 名 (仅允许字母数字下划线, 首字符非数字): ${TARGET_DB}"
    exit 1
fi

if [[ "${TARGET_DB}" == "kylinops" && "${ALLOW_PROD}" != "true" ]]; then
    err "--target-db kylinops 是生产库, 默认拒绝覆盖"
    err "  若确需恢复生产, 请额外加 --allow-prod"
    err "  ⚠  强烈建议先在临时库验证: --target-db kylinops_restore"
    exit 1
fi

# ---- 1. 校验 source 路径 ----
# 1a. 显式拒绝 .. 字符串 (双重防御, realpath 之后再验一次)
if [[ "${SOURCE}" == *".."* ]]; then
    err "拒绝包含 .. 的路径: ${SOURCE}"
    exit 1
fi

# 1b. realpath 解算
if ! REAL_SOURCE="$(realpath "${SOURCE}" 2>/dev/null)"; then
    err "无法解析 source 路径: ${SOURCE}"
    exit 1
fi

if [[ ! -f "${REAL_SOURCE}" ]]; then
    err "source 文件不存在: ${REAL_SOURCE}"
    exit 1
fi

# 1c. 白名单校验: 必须位于 ALLOWED_DIR 之下
ALLOWED_REAL="$(realpath "${ALLOWED_DIR}" 2>/dev/null || echo "")"
if [[ -z "${ALLOWED_REAL}" ]]; then
    err "白名单目录不存在: ${ALLOWED_DIR}"
    exit 1
fi

# 必须以 "${ALLOWED_REAL}/" 开头 (前缀匹配, 避免 /var/lib/kylinops/backup-evil 绕过)
if [[ "${REAL_SOURCE}" != "${ALLOWED_REAL}/"* ]]; then
    err "source 路径不在白名单 ${ALLOWED_DIR} 内: ${REAL_SOURCE}"
    err "  仅允许从官方备份目录恢复 — 防止误指向 /etc /usr /bin /boot"
    exit 1
fi

# 1d. 文件名格式校验 (必须是 postgres-*.dump, 防止指向任意文件)
FNAME="$(basename "${REAL_SOURCE}")"
if [[ ! "${FNAME}" =~ ^postgres-[0-9]{8}T[0-9]{6}Z\.dump$ ]]; then
    err "source 文件名格式不符 (期望 postgres-YYYYMMDDTHHMMSSZ.dump): ${FNAME}"
    exit 1
fi

log "source 文件: ${REAL_SOURCE} ($(stat -c '%s' "${REAL_SOURCE}" 2>/dev/null || stat -f '%z' "${REAL_SOURCE}") bytes)"
log "目标数据库: ${TARGET_DB} (生产覆盖标志: ${ALLOW_PROD})"

# ---- 2. 加载环境变量 ----
log "加载环境变量: ${ENV_FILE}"
if [[ ! -f "${ENV_FILE}" ]]; then
    err "环境变量文件不存在: ${ENV_FILE}"
    exit 1
fi
set -a
# shellcheck disable=SC1090
source "${ENV_FILE}"
set +a

: "${DB_URL:?DB_URL 未设置}"
: "${DB_USERNAME:?DB_USERNAME 未设置}"
if [[ -z "${DB_PASSWORD:-}" ]]; then
    err "DB_PASSWORD 未设置"
    exit 1
fi

# 解析 jdbc:postgresql://host:port/dbname
if [[ ! "${DB_URL}" =~ ^jdbc:postgresql://([^:/?#]+):([0-9]+)/([^?]+) ]]; then
    err "无法解析 DB_URL: ${DB_URL}"
    exit 1
fi
PGHOST="${BASH_REMATCH[1]}"
PGPORT="${BASH_REMATCH[2]}"
PGDATABASE="${BASH_REMATCH[3]}"
export PGHOST PGPORT PGDATABASE
export PGPASSWORD="${DB_PASSWORD}"

log "PostgreSQL: ${PGHOST}:${PGPORT} (user=${DB_USERNAME})"

# ---- 3. 检查 pg_restore 二进制 ----
if ! command -v pg_restore >/dev/null 2>&1; then
    err "pg_restore 未安装 (随 postgresql-client)"
    exit 1
fi

if ! command -v psql >/dev/null 2>&1; then
    err "psql 未安装"
    exit 1
fi

# ---- 4. 重要: pg_restore --clean 不会创建 db, 必须先确保 target_db 存在 ----
log "检查目标数据库 ${TARGET_DB} 是否存在..."
DB_EXISTS=$(psql \
    --host="${PGHOST}" --port="${PGPORT}" --username="${DB_USERNAME}" \
    --dbname="${PGDATABASE}" --tuples-only --no-align \
    --command "SELECT 1 FROM pg_database WHERE datname='${TARGET_DB}'" 2>/dev/null | tr -d ' ' || echo "")

if [[ "${DB_EXISTS}" != "1" ]]; then
    log "目标 db ${TARGET_DB} 不存在 — 正在创建..."
    if [[ "${TARGET_DB}" == "kylinops" && "${ALLOW_PROD}" != "true" ]]; then
        err "安全锁: 即使 DB 不存在, 也不允许隐式创建生产库"
        exit 1
    fi
    psql \
        --host="${PGHOST}" --port="${PGPORT}" --username="${DB_USERNAME}" \
        --dbname="${PGDATABASE}" \
        --command "CREATE DATABASE \"${TARGET_DB}\"" \
        || { err "创建数据库失败"; exit 1; }
fi

# ---- 5. 执行 pg_restore ----
log "开始恢复 → ${TARGET_DB}"
if ! pg_restore \
        --clean \
        --if-exists \
        --host="${PGHOST}" \
        --port="${PGPORT}" \
        --username="${DB_USERNAME}" \
        --dbname="${TARGET_DB}" \
        --no-owner \
        --no-privileges \
        "${REAL_SOURCE}"; then
    err "pg_restore 失败"
    exit 1
fi

log "✓ pg_restore 完成"

# ---- 6. 输出版本对比 ----
log "恢复后表行数对比:"
echo "  --- audit_log ---"
psql --host="${PGHOST}" --port="${PGPORT}" --username="${DB_USERNAME}" \
     --dbname="${TARGET_DB}" --tuples-only --no-align \
     --command "SELECT 'audit_log: ' || COUNT(*) FROM kylin_audit_log" 2>/dev/null \
     || warn "  audit_log 表不存在或不可访问"
echo "  --- report ---"
psql --host="${PGHOST}" --port="${PGPORT}" --username="${DB_USERNAME}" \
     --dbname="${TARGET_DB}" --tuples-only --no-align \
     --command "SELECT 'report: ' || COUNT(*) FROM kylin_report" 2>/dev/null \
     || warn "  report 表不存在或不可访问"
echo "  --- session ---"
psql --host="${PGHOST}" --port="${PGPORT}" --username="${DB_USERNAME}" \
     --dbname="${TARGET_DB}" --tuples-only --no-align \
     --command "SELECT 'session: ' || COUNT(*) FROM kylin_session" 2>/dev/null \
     || warn "  session 表不存在或不可访问"

# ---- 7. 回滚建议 ----
cat <<'EOF'

================================================================
 回滚建议 (强烈建议先验证再决定是否覆盖生产):
   1. 重新在临时 db 验证:
        bash deploy/scripts/restore-postgres.sh \
          --source <dump> --target-db kylinops_verify --confirm-restore
   2. 验证业务: 对照审计日志、报告内容、PendingAction 等关键数据
   3. 若确认覆盖生产:
        bash deploy/scripts/restore-postgres.sh \
          --source <dump> --target-db kylinops --allow-prod --confirm-restore
   4. 重启服务以清空缓存:
        sudo systemctl restart kylinops-guard
================================================================
EOF

# ---- 清理 ----
unset DB_PASSWORD PGPASSWORD

exit 0