#!/bin/bash
# ============================================================
# Legacy H2 → V2 (Flyway baseline) 升级脚本 — 麒麟安全智能运维 Agent
# ============================================================
# 用途:
#   将 Phase 0 (P0) 的 H2 file-mode 数据库 (./data/kylinops.mv.db) 升级到
#   V1__legacy_schema.sql 的 baseline + V2/V3 增量迁移。
#
# 行为 (正常流程):
#   1. 停服: 优先 systemctl stop kylinops-guard, 兜底 pkill
#   2. 备份当前 ./data/ 到 /var/lib/kylinops/backup/h2-YYYYMMDDTHHMMSSZ/data/
#      chmod 0600 + chown kylinops:kylinops
#   3. 计算 SHA256SUMS 指纹
#   4. 用 SchemaFingerprintMain 读 schema hash
#   5. baseline 校验: hash 与 V1 fingerprint 严格匹配才允许启动服务
#      (启动时由 Spring Boot 的 baseline-on-migrate=true + baseline-version=1 自动 baseline)
#   6. 启动服务 + 轮询 /api/health/ready 最长 60s
#   7. 打印回滚命令
#
# 用法:
#   sudo bash deploy/scripts/migrate-legacy-h2.sh
#   sudo bash deploy/scripts/migrate-legacy-h2.sh --help
#   sudo bash deploy/scripts/migrate-legacy-h2.sh --rollback /var/lib/kylinops/backup/h2-20260615T120000Z
#
# 安全:
#   * baseline 仅在 schema fingerprint 与 V1 完全一致时执行 — 不一致则拒绝并提示手动调整
#   * 备份目录只创建/写入 BACKUP_DIR=/var/lib/kylinops/backup/
#   * 数据目录只通过 --rollback 显式回滚, 正常流程不删 data/
# ============================================================

set -euo pipefail

# ---- 默认值 ----
ENV_FILE="${ENV_FILE:-/etc/kylinops/kylinops.env}"
BACKUP_ROOT="${BACKUP_ROOT:-/var/lib/kylinops/backup}"
SERVICE_USER="${SERVICE_USER:-kylinops}"
SERVICE_GROUP="${SERVICE_GROUP:-kylinops}"
SYSTEMD_UNIT="${SYSTEMD_UNIT:-kylinops-guard}"
READY_URL="${READY_URL:-http://localhost:8080/api/health/ready}"
READY_TIMEOUT="${READY_TIMEOUT:-60}"
DATA_DIR="${DATA_DIR:-./data}"

# ---- 颜色 ----
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log() { echo -e "${GREEN}[$(date -u +'%Y-%m-%dT%H:%M:%SZ')]${NC} $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $*" >&2; }
err() { echo -e "${RED}[ERROR]${NC} $*" >&2; }

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
    sed -n '2,30p' "$0"
    exit 0
fi

# ---- 参数解析 ----
ROLLBACK_TARGET=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --rollback)
            [[ $# -ge 2 ]] || { err "--rollback 需要参数"; exit 2; }
            ROLLBACK_TARGET="$2"
            shift 2
            ;;
        *)
            err "未知参数: $1"
            exit 2
            ;;
    esac
done

# ============================================================
# 回滚分支
# ============================================================
if [[ -n "${ROLLBACK_TARGET}" ]]; then
    log "=== 回滚分支 ==="
    ROLLBACK_REAL="$(realpath "${ROLLBACK_TARGET}" 2>/dev/null || echo "")"
    BACKUP_ROOT_REAL="$(realpath "${BACKUP_ROOT}" 2>/dev/null || echo "")"

    if [[ -z "${ROLLBACK_REAL}" || -z "${BACKUP_ROOT_REAL}" ]]; then
        err "无法解析回滚路径或 BACKUP_ROOT"
        exit 1
    fi
    if [[ "${ROLLBACK_REAL}" != "${BACKUP_ROOT_REAL}/h2-"* ]]; then
        err "回滚路径必须在 ${BACKUP_ROOT}/h2-* 之下: ${ROLLBACK_REAL}"
        exit 1
    fi
    if [[ ! -d "${ROLLBACK_REAL}/data" ]]; then
        err "回滚目录缺少 data/: ${ROLLBACK_REAL}/data"
        exit 1
    fi

    log "停服..."
    systemctl stop "${SYSTEMD_UNIT}" 2>/dev/null || pkill -f kylin-ops-guard 2>/dev/null || true
    sleep 2

    log "删除当前 data/ ..."
    if [[ -d "${DATA_DIR}" ]]; then
        find "${DATA_DIR}" -mindepth 1 -delete 2>/dev/null || rm -rf "${DATA_DIR}"
    fi
    install -d -m 0750 -o "${SERVICE_USER}" -g "${SERVICE_GROUP}" "${DATA_DIR}"

    log "从 ${ROLLBACK_REAL}/data 恢复..."
    cp -a "${ROLLBACK_REAL}/data/." "${DATA_DIR}/"
    chmod -R 0600 "${DATA_DIR}" 2>/dev/null || true
    chown -R "${SERVICE_USER}:${SERVICE_GROUP}" "${DATA_DIR}"

    log "启动服务..."
    systemctl start "${SYSTEMD_UNIT}" 2>/dev/null \
        || { warn "systemctl start 失败; 请手工启动"; exit 0; }

    log "✓ 回滚完成"
    exit 0
fi

# ============================================================
# 正常升级流程
# ============================================================
log "=== Legacy H2 升级流程 ==="

# ---- 1. 检查运行环境 ----
if ! command -v java >/dev/null 2>&1; then
    err "java 未安装"
    exit 1
fi
if ! command -v mvn >/dev/null 2>&1; then
    err "mvn 未安装 (SchemaFingerprintMain 通过 mvn classpath 调用)"
    exit 1
fi

# ---- 2. 检查当前 data/ 是否存在 ----
if [[ ! -d "${DATA_DIR}" ]]; then
    err "data/ 目录不存在: ${DATA_DIR}"
    err "  P0 H2 file-mode 期望路径; 不存在则无需 baseline"
    exit 1
fi

# 找 .mv.db 文件
MV_DB="$(find "${DATA_DIR}" -maxdepth 1 -name 'kylinops.mv.db' -print -quit || true)"
if [[ -z "${MV_DB}" ]]; then
    err "未找到 ${DATA_DIR}/kylinops.mv.db"
    exit 1
fi
log "目标 H2 文件: ${MV_DB}"

# ---- 3. 停服 ----
log "[1/6] 停服..."
if command -v systemctl >/dev/null 2>&1; then
    if systemctl is-active --quiet "${SYSTEMD_UNIT}" 2>/dev/null; then
        systemctl stop "${SYSTEMD_UNIT}"
        log "  systemctl stop ${SYSTEMD_UNIT} ✓"
    else
        log "  systemd unit 未运行, 跳过"
    fi
fi
# 兜底: 杀进程
if pgrep -f "kylin-ops-guard" >/dev/null 2>&1; then
    warn "  仍有 kylin-ops-guard 进程, pkill -f"
    pkill -f kylin-ops-guard 2>/dev/null || true
    sleep 2
fi

# ---- 4. 备份 data/ ----
TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
BACKUP_DIR="${BACKUP_ROOT}/h2-${TIMESTAMP}"
log "[2/6] 备份到 ${BACKUP_DIR}/data ..."
install -d -m 0750 -o "${SERVICE_USER}" -g "${SERVICE_GROUP}" "${BACKUP_DIR}"
cp -a "${DATA_DIR}" "${BACKUP_DIR}/data"
chmod -R 0600 "${BACKUP_DIR}/data" 2>/dev/null || true
chown -R "${SERVICE_USER}:${SERVICE_GROUP}" "${BACKUP_DIR}"

# ---- 5. SHA-256 指纹 ----
log "[3/6] 计算 SHA-256 指纹..."
(cd "${BACKUP_DIR}/data" && find . -type f -name '*.db' -exec sha256sum {} \;) > "${BACKUP_DIR}/SHA256SUMS"
log "  → ${BACKUP_DIR}/SHA256SUMS"

# ---- 6. Schema fingerprint 校验 ----
log "[4/6] 读取 schema fingerprint..."

# 通过 mvn exec:java 跑 SchemaFingerprintMain
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)/backend"

if [[ ! -d "${BACKEND_DIR}" ]]; then
    err "backend 目录不存在: ${BACKEND_DIR}"
    err "  SchemaFingerprintMain 编译路径无法定位"
    exit 1
fi

# 用 cd + 临时变量包住 pushd, 避免破坏 cwd
CURRENT_FP=""
if (
    cd "${BACKEND_DIR}"
    mvn -B -q compile >/dev/null 2>&1
    if [[ ! -d "target/classes/com/kylinops/migration" ]]; then
        echo "BUILD_FAIL" >&2
        exit 1
    fi
    # 拼 classpath (H2 + Flyway + Jackson + Spring JDBC 在 backend dependencies)
    CP="$(mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout 2>/dev/null | tail -n 1)"
    CURRENT_FP="$(java -cp "target/classes:${CP}" com.kylinops.migration.SchemaFingerprintMain "${MV_DB}" 2>/dev/null || echo "")"
); then
    :
else
    err "SchemaFingerprintMain 执行失败 (mvn compile 失败?)"
    err "  请在 ${BACKEND_DIR} 下手动: mvn -B compile"
    exit 1
fi

if [[ -z "${CURRENT_FP}" || ! "${CURRENT_FP}" =~ ^[a-f0-9]{64}$ ]]; then
    err "无法读取 schema fingerprint (输出: '${CURRENT_FP}')"
    exit 1
fi
log "  当前 schema hash: ${CURRENT_FP}"

# V1__legacy_schema.sql 冻结的 8 张 legacy 表 SHA-256 hash
V1_EXPECTED="caa58c629302b8e85ea78238b23d71fdd4f4278616bdd87023f33a90a2c990f2"

log "  V1 期望 hash: ${V1_EXPECTED}"
log "  当前 hash:    ${CURRENT_FP}"

# 严格比对: hash 不一致 → exit 1, 禁止 baseline
if [[ "${CURRENT_FP}" != "${V1_EXPECTED}" ]]; then
    err "============================================================"
    err " schema fingerprint 不匹配 — 拒绝 baseline"
    err "============================================================"
    err "  当前 H2 数据库的 KYLIN_* 表集合 SHA-256:"
    err "    ${CURRENT_FP}"
    err "  V1__legacy_schema.sql 冻结的 8 张表期望 SHA-256:"
    err "    ${V1_EXPECTED}"
    err ""
    err "  可能原因:"
    err "    1. 当前 H2 数据库表结构已被修改（缺少或新增 KYLIN_ 表）"
    err "    2. V1__legacy_schema.sql 已变更但未更新本脚本期望值"
    err "    3. 数据库文件损坏"
    err ""
    err "  修复建议:"
    err "    1. 备份当前 data/ 后尝试回滚:"
    err "       bash ${0} --rollback ${BACKUP_DIR}"
    err "    2. 如需手动调整 SQL 后重新尝试迁移"
    err ""
    err "  安全说明:"
    err "    V1 baseline 要求 schema fingerprint 严格匹配——"
    err "    防止 Flyway 在不知情的 schema 变更上自动 baseline"
    err "    导致数据不一致。Hash 完全一致时才放行。"
    err "============================================================"
    exit 1
fi

log "[5/6] ✓ V1 schema fingerprint 匹配 — 允许 baseline"

V1_EXPECTED_FILE="${BACKUP_DIR}/V1_FINGERPRINT"
cat > "${V1_EXPECTED_FILE}" <<EOF
# Legacy H2 schema fingerprint — baseline 时严格校验通过
# 校验时间: ${TIMESTAMP}
# 当前 hash: ${CURRENT_FP}
# V1 期望:  ${V1_EXPECTED}
# 比对结果: MATCH — 允许 Flyway baseline
#
# 此文件由 migrate-legacy-h2.sh 在升级流程中自动生成,
# 供后续审计与 P4-T3 target matrix 验证使用。
EOF
log "  fingerprint 校验记录: ${V1_EXPECTED_FILE}"

# ---- 7. 启动服务 ----
log "[6/6] 启动服务..."

if command -v systemctl >/dev/null 2>&1; then
    if systemctl start "${SYSTEMD_UNIT}" 2>/dev/null; then
        log "  systemctl start ${SYSTEMD_UNIT} ✓"
    else
        warn "  systemctl start 失败; 请手工启动"
        log "✓ 备份完成, 等待运维手动启动服务"
        log "  启动命令: sudo systemctl start ${SYSTEMD_UNIT}"
        log "  回滚命令: sudo bash ${SCRIPT_DIR}/migrate-legacy-h2.sh --rollback ${BACKUP_DIR}"
        exit 0
    fi
else
    log "  无 systemctl; 请手工启动后端"
    log "  启动命令: bash deploy/scripts/start-backend.sh"
    log "  回滚命令: bash ${SCRIPT_DIR}/migrate-legacy-h2.sh --rollback ${BACKUP_DIR}"
    exit 0
fi

# ---- 8. 轮询 ready ----
log "轮询 ${READY_URL} (超时 ${READY_TIMEOUT}s)..."
READY=false
for i in $(seq 1 "${READY_TIMEOUT}"); do
    HTTP_CODE="$(curl -s -o /tmp/migrate-ready.json -w '%{http_code}' "${READY_URL}" 2>/dev/null || echo "000")"
    if [[ "${HTTP_CODE}" == "200" ]]; then
        READY=true
        log "  ✓ 服务就绪 (${i}s)"
        break
    fi
    sleep 1
done

if [[ "${READY}" != "true" ]]; then
    err "服务在 ${READY_TIMEOUT}s 内未就绪 (last HTTP=${HTTP_CODE})"
    err "  请查看 /var/log/kylinops/backend.log"
    err "  如需回滚: sudo bash ${SCRIPT_DIR}/migrate-legacy-h2.sh --rollback ${BACKUP_DIR}"
    exit 1
fi

# ---- 9. 成功 ----
cat <<EOF

================================================================
 ✓ Legacy H2 升级完成
================================================================
 备份目录: ${BACKUP_DIR}
 指纹文件: ${BACKUP_DIR}/SHA256SUMS
 当前 hash: ${CURRENT_FP}

 回滚命令:
   sudo bash ${SCRIPT_DIR}/migrate-legacy-h2.sh --rollback ${BACKUP_DIR}

 注意: Spring Boot 启动日志应包含:
   "Successfully baselined schema with version: 1"
   "Migrating schema to version 2 - execution audit schema"
   "Migrating schema to version 3 - llm call audit"
================================================================
EOF
exit 0