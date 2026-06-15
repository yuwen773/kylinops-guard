#!/bin/bash
# ============================================================
# PostgreSQL 备份脚本 — 麒麟安全智能运维 Agent
# ============================================================
# 行为:
#   1. 从 /etc/kylinops/kylinops.env 注入 DB_URL/DB_USERNAME/DB_PASSWORD
#      (set -a; source; set +a 模式; PASSWORD 仅注入到 PGPASSWORD 环境,
#       不回显到 stdout / 日志 / 错误流)
#   2. 解析 jdbc:postgresql://host:port/dbname → PGHOST/PGPORT/PGDATABASE
#   3. pg_dump --format=custom --no-owner --no-privileges
#   4. timestamped output: /var/lib/kylinops/backup/postgres-YYYYMMDDTHHMMSSZ.dump
#   5. 0600 权限 + owner kylinops:kylinops
#   6. sha256sum 输出供运维完整性校验
#   7. 保留最近 7 个备份 (轮转仅在 /var/lib/kylinops/backup/ 内的 .dump 文件)
#
# 用法:
#   sudo -u kylinops bash deploy/scripts/backup-postgres.sh
#   sudo -u kylinops bash deploy/scripts/backup-postgres.sh --help
#
# 安全:
#   * set -euo pipefail — 任何步骤失败立即 exit 非零
#   * DB_PASSWORD 仅以 PGPASSWORD 注入; 不 echo, 不 printenv, 不写日志
#   * 路径白名单: 仅写入 BACKUP_DIR=/var/lib/kylinops/backup/
# ============================================================

set -euo pipefail

# ---- 默认值 ----
ENV_FILE="${ENV_FILE:-/etc/kylinops/kylinops.env}"
BACKUP_DIR="${BACKUP_DIR:-/var/lib/kylinops/backup}"
SERVICE_USER="${SERVICE_USER:-kylinops}"
SERVICE_GROUP="${SERVICE_GROUP:-kylinops}"
RETENTION_COUNT="${RETENTION_COUNT:-7}"

# ---- 颜色 ----
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

# ---- 帮助 ----
if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
    sed -n '2,30p' "$0"
    exit 0
fi

log() { echo -e "${GREEN}[$(date -u +'%Y-%m-%dT%H:%M:%SZ')]${NC} $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $*" >&2; }
err() { echo -e "${RED}[ERROR]${NC} $*" >&2; }

# ---- 1. 加载环境变量 ----
log "加载环境变量: ${ENV_FILE}"
if [[ ! -f "${ENV_FILE}" ]]; then
    err "环境变量文件不存在: ${ENV_FILE}"
    err "  请先: sudo cp deploy/config/kylinops.env.example ${ENV_FILE}"
    exit 1
fi
# 安全加载: set -a 自动 export, set +a 关闭
# 注意: set -euo pipefail 在前面, 这条 source 失败仍会触发 errexit
set -a
# shellcheck disable=SC1090
source "${ENV_FILE}"
set +a

# ---- 2. 校验必填变量 (不回显 PASSWORD 值) ----
: "${DB_URL:?DB_URL 未设置}"
: "${DB_USERNAME:?DB_USERNAME 未设置}"
if [[ -z "${DB_PASSWORD:-}" ]]; then
    err "DB_PASSWORD 未设置"
    exit 1
fi

# ---- 3. 解析 jdbc:postgresql://host:port/dbname ----
# 期望格式: jdbc:postgresql://127.0.0.1:5432/kylinops
if [[ ! "${DB_URL}" =~ ^jdbc:postgresql://([^:/?#]+):([0-9]+)/([^?]+) ]]; then
    err "无法解析 DB_URL (期望 jdbc:postgresql://host:port/dbname): ${DB_URL}"
    exit 1
fi
PGHOST="${BASH_REMATCH[1]}"
PGPORT="${BASH_REMATCH[2]}"
PGDATABASE="${BASH_REMATCH[3]}"
export PGHOST PGPORT PGDATABASE
export PGPASSWORD="${DB_PASSWORD}"

log "目标数据库: ${PGHOST}:${PGPORT}/${PGDATABASE} (user=${DB_USERNAME})"

# ---- 4. 检查 pg_dump 二进制 ----
if ! command -v pg_dump >/dev/null 2>&1; then
    err "pg_dump 未安装"
    err "  请: sudo apt-get install postgresql-client    # Debian/Ubuntu/Kylin"
    err "       sudo yum install postgresql              # RHEL"
    exit 1
fi

# ---- 5. 准备 BACKUP_DIR ----
if [[ ! -d "${BACKUP_DIR}" ]]; then
    log "创建备份目录: ${BACKUP_DIR}"
    install -d -m 0750 -o "${SERVICE_USER}" -g "${SERVICE_GROUP}" "${BACKUP_DIR}"
fi

# ---- 6. 生成 timestamped 文件名 ----
TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUTPUT="${BACKUP_DIR}/postgres-${TIMESTAMP}.dump"

# ---- 7. 备份前健康检查 (pg_isready) ----
if command -v pg_isready >/dev/null 2>&1; then
    if ! pg_isready -h "${PGHOST}" -p "${PGPORT}" -U "${DB_USERNAME}" -q; then
        err "PostgreSQL 不可达: ${PGHOST}:${PGPORT}"
        exit 1
    fi
fi

# ---- 8. 执行 pg_dump ----
log "开始备份 → ${OUTPUT}"
if ! pg_dump \
        --format=custom \
        --no-owner \
        --no-privileges \
        --host="${PGHOST}" \
        --port="${PGPORT}" \
        --username="${DB_USERNAME}" \
        --dbname="${PGDATABASE}" \
        --file="${OUTPUT}"; then
    err "pg_dump 失败"
    rm -f "${OUTPUT}" 2>/dev/null || true
    exit 1
fi

# ---- 9. 设置权限 ----
chmod 0600 "${OUTPUT}"
chown "${SERVICE_USER}:${SERVICE_GROUP}" "${OUTPUT}"

# ---- 10. SHA-256 指纹 ----
SHA="$(sha256sum "${OUTPUT}" | awk '{print $1}')"
log "备份完成"
log "  路径: ${OUTPUT}"
log "  大小: $(stat -c '%s' "${OUTPUT}" 2>/dev/null || stat -f '%z' "${OUTPUT}") bytes"
log "  SHA256: ${SHA}"

# ---- 11. 轮转: 保留最近 RETENTION_COUNT 个 ----
log "轮转备份 (保留最近 ${RETENTION_COUNT} 个)..."
# 仅在 BACKUP_DIR 内删 dump 文件 (白名单: 不动其他路径)
mapfile -t OLD_FILES < <(find "${BACKUP_DIR}" -maxdepth 1 -type f -name 'postgres-*.dump' -printf '%T@ %p\n' 2>/dev/null \
    | sort -rn \
    | tail -n +$((RETENTION_COUNT + 1)) \
    | awk '{print $2}')

DELETED=0
for f in "${OLD_FILES[@]:-}"; do
    if [[ -n "${f}" && "${f}" == "${BACKUP_DIR}/postgres-"*.dump ]]; then
        # 二次校验: realpath 必须仍在 BACKUP_DIR 内
        REAL="$(realpath "${f}" 2>/dev/null || echo "")"
        REAL_BASE="$(realpath "${BACKUP_DIR}" 2>/dev/null || echo "")"
        if [[ -n "${REAL}" && -n "${REAL_BASE}" && "${REAL}" == "${REAL_BASE}/"* ]]; then
            rm -f -- "${f}"
            DELETED=$((DELETED + 1))
            log "  删除旧备份: $(basename "${f}")"
        else
            warn "  跳过可疑路径: ${f}"
        fi
    fi
done

if [[ ${DELETED} -eq 0 ]]; then
    log "  无需清理"
fi

# ---- 12. 清理敏感 env (进程退出前) ----
unset DB_PASSWORD PGPASSWORD

log "✓ 备份流程结束"
exit 0