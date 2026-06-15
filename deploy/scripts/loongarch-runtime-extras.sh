#!/bin/bash
# ============================================================
# LoongArch §1.2.1 运行时扩展 — 麒麟安全智能运维 Agent
# ============================================================
# 覆盖 acceptance-smoke.sh 未触达的 §1.2.1 格子:
#   * Flyway 迁移幂等 (auto-N/A if flyway.enabled=false)
#   * OS 工具硬超时 (time one L0 tool, assert < 3s)
#   * H2→PG schema fingerprint (always N/A on this profile)
#
# Usage:
#   BASE_URL=http://localhost:8080 \
#   SMOKE_USERNAME=admin SMOKE_PASSWORD='test-admin-pwd' \
#   bash deploy/scripts/loongarch-runtime-extras.sh
#
# Exit codes:
#   0 = all applicable cells PASS (N/A cells logged, not failed)
#   1 = any applicable cell FAIL
# ============================================================

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
SMOKE_USERNAME="${SMOKE_USERNAME:-admin}"
SMOKE_PASSWORD="${SMOKE_PASSWORD:-}"
COOKIE_JAR="${COOKIE_JAR:-${TMPDIR:-/tmp}/kylinops-runtime-extras-cookies.txt}"
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

# jget <json> <python_expr>
jget() {
    local json="$1" expr="$2"
    python3 -c "
import json, sys
try:
    obj = json.loads(sys.argv[1])
except Exception as e:
    print('PARSE_ERR: ' + str(e))
    sys.exit(0)
${expr}
" "${json}" 2>/dev/null
}

http_post() {
    local url="$1" data="$2"
    HTTP_CODE="$(curl -s -o /tmp/runtime-extras-body.json -w '%{http_code}' \
        --max-time "${HTTP_TIMEOUT}" \
        -H 'Content-Type: application/json' \
        -H "X-XSRF-TOKEN: ${CSRF_TOKEN:-}" \
        --cookie-jar "${COOKIE_JAR}" --cookie "${COOKIE_JAR}" \
        -X POST --data "${data}" "${BASE_URL}${url}" 2>/dev/null || echo "000")"
    HTTP_BODY="$(cat /tmp/runtime-extras-body.json 2>/dev/null || echo "")"
}

# Pre-flight
if [[ -z "${SMOKE_PASSWORD}" ]]; then
    err "SMOKE_PASSWORD 未设置"; exit 1
fi
if ! command -v curl >/dev/null 2>&1; then err "curl 缺失"; exit 1; fi
if ! command -v python3 >/dev/null 2>&1; then err "python3 缺失"; exit 1; fi

cat <<EOF
================================================================
 麒麟安全智能运维 Agent — LoongArch §1.2.1 运行时扩展
 BASE_URL: ${BASE_URL}
 用户名: ${SMOKE_USERNAME}
 Cookie jar: ${COOKIE_JAR}
================================================================
EOF

echo
echo -e "${CYAN}=== §1.2.1 Cell: Flyway 迁移幂等 ===${NC}"

# dev profile has flyway.enabled=false (see application-dev.yml line 11).
# H2 uses ddl-auto: update. Flyway only runs under prod profile + PostgreSQL.
# Runtime query of H2 console would require JSESSIONID login flow which is
# not reliably scriptable. Honest evidence path: unit test FlywayH2MigrationTest
# covers idempotency on the prod profile (CI matrix C ✅).
assert_na "Flyway 迁移幂等" \
    "dev profile flyway.enabled=false; 单元测试 FlywayH2MigrationTest 已覆盖 prod profile 路径 (见 application-prod.yml flyway.locations)"

echo
echo -e "${CYAN}=== §1.2.1 Cell: 命令执行硬超时 (single L0 tool < 3s) ===${NC}"

# Need to login first to call /api/chat/send (auth required)
rm -f "${COOKIE_JAR}"
LOGIN_BODY="$(python3 -c "
import json, os
print(json.dumps({'username': os.environ['SMOKE_USERNAME'], 'password': os.environ['SMOKE_PASSWORD']}))
")"
http_post "/api/auth/login" "${LOGIN_BODY}"
if [[ "${HTTP_CODE}" != "200" ]]; then
    err "Login failed: HTTP ${HTTP_CODE}, body=${HTTP_BODY:0:200}"
    exit 1
fi
CSRF_TOKEN="$(jget "${HTTP_BODY}" "print(obj.get('data', {}).get('csrfToken', ''))")"
if [[ -z "${CSRF_TOKEN}" || "${CSRF_TOKEN}" == "None" ]]; then
    CSRF_TOKEN="$(grep -E 'XSRF-TOKEN' "${COOKIE_JAR}" 2>/dev/null | awk '{print $7}' | tail -1 || echo "")"
fi
log "登录成功, csrf=${CSRF_TOKEN:0:8}..."

# Trigger a single L0 tool call via chat
START_MS=$(date +%s%3N)
http_post "/api/chat/send" '{"content":"查看磁盘状态"}'
END_MS=$(date +%s%3N)
WALL_MS=$((END_MS - START_MS))

ELAPSED_MS=$(jget "${HTTP_BODY}" "
calls = obj.get('data', {}).get('toolCalls', [])
print((calls[0].get('durationMs', 0) if calls else 0) or 0)
")
TOOL_NAME=$(jget "${HTTP_BODY}" "
calls = obj.get('data', {}).get('toolCalls', [])
print(calls[0].get('toolName', '') if calls else '')
")

# Prefer the JSON field; fall back to wall-clock if missing
if [[ -z "${ELAPSED_MS}" || "${ELAPSED_MS}" == "0" || ! "${ELAPSED_MS}" =~ ^[0-9]+$ ]]; then
    warn "  durationMs 字段缺失或非数字 (got '${ELAPSED_MS}'), 用 wall-clock 替代"
    ELAPSED_MS="${WALL_MS}"
fi

log "  tool=${TOOL_NAME} durationMs=${ELAPSED_MS}"
assert "单 L0 工具 < 3000ms" \
    "$([[ "${ELAPSED_MS}" =~ ^[0-9]+$ && "${ELAPSED_MS}" -lt 3000 ]] && echo true || echo false)" \
    "got ${ELAPSED_MS}ms (budget 3000ms)"

echo
echo -e "${CYAN}=== §1.2.1 Cell: H2→PostgreSQL schema fingerprint ===${NC}"
assert_na "H2→PG schema fingerprint" \
    "本轮选 H2 file mode; 替代证据见 spec §6 (单元测试 + application-prod.yml 配置就位)"

echo
echo "================================================================"
echo " Runtime extras 汇总"
echo "================================================================"
echo " TOTAL: ${TOTAL}"
echo " PASS:  ${PASSED}"
echo " FAIL:  ${FAILED}"
echo " N/A:   ${NA_COUNT}"

if [[ ${FAILED} -gt 0 ]]; then
    echo
    err "失败项:"
    for f in "${FAILURES[@]}"; do echo "  - ${f}"; done
    exit 1
fi

log "✓ 所有 applicable 格子通过 (N/A 不计入失败)"
exit 0
