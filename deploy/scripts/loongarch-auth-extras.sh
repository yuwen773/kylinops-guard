#!/bin/bash
# ============================================================
# LoongArch §1.2.2 认证扩展 — 麒麟安全智能运维 Agent
# ============================================================
# 覆盖 acceptance-smoke.sh 未触达的 §1.2.2 格子:
#   * 失败锁定 5 次 / 15 分钟 (5x wrong password → 423 LOCKED)
#   * 锁定后正确密码也被拒 (防止暴力破解绕过)
#
# 注意: 锁定会让 admin 在 15 分钟内无法登录. 脚本末尾提供等待/重启建议.
#
# Usage:
#   BASE_URL=http://localhost:8080 \
#   SMOKE_USERNAME=admin SMOKE_PASSWORD='test-admin-pwd' \
#   bash deploy/scripts/loongarch-auth-extras.sh
#
# Exit codes:
#   0 = all applicable cells PASS (N/A cells logged, not failed)
#   1 = any applicable cell FAIL
# ============================================================

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
SMOKE_USERNAME="${SMOKE_USERNAME:-admin}"
SMOKE_PASSWORD="${SMOKE_PASSWORD:-}"
COOKIE_JAR="${COOKIE_JAR:-${TMPDIR:-/tmp}/kylinops-auth-extras-cookies.txt}"
HTTP_TIMEOUT="${HTTP_TIMEOUT:-15}"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

TOTAL=0
PASSED=0
FAILED=0
NA_COUNT=0
declare -a FAILURES=()

log() { echo -e "${GREEN}[$(date -u +'%Y-%m-%dT%H:%M:%SZ')]${NC} $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $*" >&2; }
err() { echo -e "${RED}[ERROR]${NC} $*" >&2; }

# assert <name> <cond_true|false> <detail>
assert() {
    local name="$1" cond="$2" detail="${3:-}"
    TOTAL=$((TOTAL + 1))
    if [[ "${cond}" == "true" || "${cond}" == "0" ]]; then
        PASSED=$((PASSED + 1))
        echo -e "  ${GREEN}✓${NC} ${name}"
    else
        FAILED=$((FAILED + 1))
        FAILURES+=("${name}${detail:+ — ${detail}}")
        echo -e "  ${RED}✗${NC} ${name}${detail:+ — ${detail}}"
    fi
}

# assert_na <name> <reason>
assert_na() {
    local name="$1" reason="$2"
    NA_COUNT=$((NA_COUNT + 1))
    echo -e "  ${YELLOW}⊘${NC} ${name} (N/A: ${reason})"
}

# Pre-flight
if [[ -z "${SMOKE_PASSWORD}" ]]; then
    err "SMOKE_PASSWORD 未设置"; exit 1
fi
if ! command -v curl >/dev/null 2>&1; then err "curl 缺失"; exit 1; fi

cat <<EOF
================================================================
 麒麟安全智能运维 Agent — LoongArch §1.2.2 认证扩展
 BASE_URL: ${BASE_URL}
 用户名: ${SMOKE_USERNAME}
 Cookie jar: ${COOKIE_JAR}
================================================================
EOF

echo
echo -e "${CYAN}=== §1.2.2 Cell: 失败锁定 5 次 / 15 分钟 ===${NC}"

# Use a SEPARATE cookie jar so we don't pollute the main one.
LOCKOUT_COOKIE_JAR="${COOKIE_JAR}.lockout"
rm -f "${LOCKOUT_COOKIE_JAR}"

LOCKED_AT_LEAST_ONCE="false"
LAST_HTTP=""
LAST_BODY=""
for i in 1 2 3 4 5; do
    HTTP_CODE="$(curl -s -o /tmp/auth-extras-body.json -w '%{http_code}' \
        --max-time "${HTTP_TIMEOUT}" \
        -H 'Content-Type: application/json' \
        --cookie-jar "${LOCKOUT_COOKIE_JAR}" --cookie "${LOCKOUT_COOKIE_JAR}" \
        -X POST --data '{"username":"'${SMOKE_USERNAME}'","password":"wrong-password-'${i}'"}' \
        "${BASE_URL}/api/auth/login" 2>/dev/null || echo "000")"
    HTTP_BODY="$(cat /tmp/auth-extras-body.json 2>/dev/null || echo "")"
    LAST_HTTP="${HTTP_CODE}"
    LAST_BODY="${HTTP_BODY}"
    log "  attempt ${i}/5: HTTP ${HTTP_CODE}"
    if [[ "${HTTP_CODE}" == "423" ]]; then
        LOCKED_AT_LEAST_ONCE="true"
        log "  ✓ 锁定触发 (423 LOCKED)"
        break
    fi
done

assert "失败锁定在 5 次内触发 (HTTP 423)" "${LOCKED_AT_LEAST_ONCE}" \
    "last_http=${LAST_HTTP}, body=${LAST_BODY:0:200}"

# Verify response body mentions lockout (case-insensitive grep)
LOCKOUT_MENTIONED="false"
if echo "${LAST_BODY}" | grep -qiE '(lock|锁定|fail|too.many)'; then
    LOCKOUT_MENTIONED="true"
fi
assert "锁定响应 body 含 lock/fail 关键字" "${LOCKOUT_MENTIONED}" "body=${LAST_BODY:0:300}"

echo
echo -e "${CYAN}=== §1.2.2 Cell: 锁定后正确密码也被拒 ===${NC}"

# 6th attempt with CORRECT password should still be locked
HTTP_CODE="$(curl -s -o /tmp/auth-extras-body.json -w '%{http_code}' \
    --max-time "${HTTP_TIMEOUT}" \
    -H 'Content-Type: application/json' \
    --cookie-jar "${LOCKOUT_COOKIE_JAR}" --cookie "${LOCKOUT_COOKIE_JAR}" \
    -X POST --data '{"username":"'${SMOKE_USERNAME}'","password":"'${SMOKE_PASSWORD}'"}' \
    "${BASE_URL}/api/auth/login" 2>/dev/null || echo "000")"
HTTP_BODY="$(cat /tmp/auth-extras-body.json 2>/dev/null || echo "")"
log "  6th attempt with CORRECT pwd: HTTP ${HTTP_CODE}"
assert "锁定后正确密码也被拒 (423)" \
    "$([[ "${HTTP_CODE}" == "423" ]] && echo true || echo false)" \
    "got ${HTTP_CODE}, body=${HTTP_BODY:0:200}"

echo
echo -e "${CYAN}=== §1.2.2 Cell: Idle 30m / Absolute 8h 过期 ===${NC}"
assert_na "Idle 30m / Absolute 8h 过期" \
    "需等待 30 分钟/8 小时真机不现实; 单元测试 SessionExpiryTest 已覆盖 (backend 499/499 baseline)"

echo
echo -e "${CYAN}=== §1.2.2 Cell: 执行前审计闭锁 (audit-fail → no execute) ===${NC}"
assert_na "执行前审计闭锁" \
    "需要破坏审计子系统才能触发; 单元测试 AuditWriteFailureTest 已覆盖"

echo
echo "================================================================"
echo " Auth extras 汇总"
echo "================================================================"
echo " TOTAL: ${TOTAL}"
echo " PASS:  ${PASSED}"
echo " FAIL:  ${FAILED}"
echo " N/A:   ${NA_COUNT}"

if [[ ${FAILED} -gt 0 ]]; then
    echo
    err "失败项:"
    for f in "${FAILURES[@]}"; do echo "  - ${f}"; done
fi

echo
warn "⚠ admin 账号现已锁定 (~15 分钟), 后续命令需要等"
warn "  快速恢复: 重启后端进程 (锁定状态在内存中, 重启丢失)"
warn "  或等 15 分钟自然过期"

exit $((FAILED > 0 ? 1 : 0))
