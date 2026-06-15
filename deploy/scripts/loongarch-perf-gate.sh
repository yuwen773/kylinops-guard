#!/bin/bash
# ============================================================
# LoongArch §1.2.5 性能门禁 — 麒麟安全智能运维 Agent
# ============================================================
# 覆盖 §1.2.5 性能 Gate 全部 5 项, 与 PRD §12.3 预算对比:
#   * 单 tool 调用 ≤ 3s         (cold + hot)
#   * RiskCheck ≤ 1s             (10 次采样 P95)
#   * 健康巡检 (8 工具 fan-out) ≤ 30s (3 次采样 avg)
#   * 普通 chat ≤ 10s            (3 次采样 avg)
#   * 报告生成 ≤ 5s              (3 次采样 avg)
#
# Usage:
#   BASE_URL=http://localhost:8080 \
#   SMOKE_USERNAME=admin SMOKE_PASSWORD='test-admin-pwd' \
#   bash deploy/scripts/loongarch-perf-gate.sh
#
# 注意:
#   * 必须 backend 已冷启动 (< 30s) 才能测 cold metric
#   * 跑完 cold metric 后等 30s 让 JIT warm, 再跑 hot metric
#   * 报告生成需要先 trigger 一次 chat (生成 auditId), 再用 auditId 调 generate
# ============================================================

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
SMOKE_USERNAME="${SMOKE_USERNAME:-admin}"
SMOKE_PASSWORD="${SMOKE_PASSWORD:-}"
COOKIE_JAR="${COOKIE_JAR:-${TMPDIR:-/tmp}/kylinops-perf-gate-cookies.txt}"
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

# json_extract <json_string> <field_name>
# Extract a string or number field from single-line JSON via grep.
json_extract() {
    local json="$1" field="$2"
    local val
    val="$(echo "${json}" | grep -o '"'"${field}"'":"[^"]*"' | head -1 | cut -d'"' -f4)"
    if [[ -n "${val}" ]]; then
        echo "${val}"
        return
    fi
    val="$(echo "${json}" | grep -o '"'"${field}"'":[-0-9.]*' | head -1 | cut -d: -f2)"
    echo "${val}"
}

http_post() {
    local url="$1" data="$2"
    printf '%s\n' "${data}" > /tmp/perf-gate-post-body.json
    HTTP_CODE="$(curl -s -o /tmp/perf-gate-body.json -w '%{http_code}' \
        --max-time "${HTTP_TIMEOUT}" \
        -H 'Content-Type: application/json' \
        -H "X-CSRF-TOKEN: ${CSRF_TOKEN:-}" \
        --cookie-jar "${COOKIE_JAR}" --cookie "${COOKIE_JAR}" \
        -X POST --data @/tmp/perf-gate-post-body.json "${BASE_URL}${url}" 2>/dev/null || echo "000")"
    HTTP_BODY="$(cat /tmp/perf-gate-body.json 2>/dev/null || echo "")"
}

# Pre-flight
if [[ -z "${SMOKE_PASSWORD}" ]]; then
    err "SMOKE_PASSWORD 未设置"; exit 1
fi
if ! command -v curl >/dev/null 2>&1; then err "curl 缺失"; exit 1; fi

cat <<EOF
================================================================
 麒麟安全智能运维 Agent — LoongArch §1.2.5 性能 Gate
 BASE_URL: ${BASE_URL}
 用户名: ${SMOKE_USERNAME}
 Cookie jar: ${COOKIE_JAR}
================================================================
EOF

echo
echo -e "${CYAN}=== Setup: 登录拿 CSRF ===${NC}"

rm -f "${COOKIE_JAR}"
http_post "/api/auth/login" '{"username":"'${SMOKE_USERNAME}'","password":"'${SMOKE_PASSWORD}'"}'
if [[ "${HTTP_CODE}" != "200" ]]; then
    err "Login failed: HTTP ${HTTP_CODE}, body=${HTTP_BODY:0:200}"
    exit 1
fi
CSRF_TOKEN="$(json_extract "${HTTP_BODY}" "csrfToken")"
[[ -z "${CSRF_TOKEN}" || "${CSRF_TOKEN}" == "None" ]] && \
    CSRF_TOKEN="$(grep -E 'CSRF-TOKEN' "${COOKIE_JAR}" 2>/dev/null | awk '{print $7}' | tail -1 || echo "")"
log "登录成功, csrf=${CSRF_TOKEN:0:8}..."

echo
echo -e "${CYAN}=== Cell 1: 单 L0 工具 cold + hot (预算 3000ms) ===${NC}"

# Cold: first call after backend warmup (assume backend is cold-ish at start)
START=$(date +%s%3N)
http_post "/api/chat/send" '{"content":"查看磁盘状态"}' >/dev/null
COLD_ELAPSED=$(( $(date +%s%3N) - START ))
log "  cold: ${COLD_ELAPSED}ms"
assert "cold < 3000ms" \
    "$([[ ${COLD_ELAPSED} -lt 3000 ]] && echo true || echo false)" \
    "got ${COLD_ELAPSED}ms"

# Hot: 3 more calls, take avg
HOT_TOTAL=0
HOT_COUNT=3
for i in $(seq 1 ${HOT_COUNT}); do
    START=$(date +%s%3N)
    http_post "/api/chat/send" '{"content":"查看磁盘状态"}' >/dev/null
    HOT_ELAPSED=$(( $(date +%s%3N) - START ))
    HOT_TOTAL=$((HOT_TOTAL + HOT_ELAPSED))
    log "  hot #${i}: ${HOT_ELAPSED}ms"
done
HOT_AVG=$((HOT_TOTAL / HOT_COUNT))
assert "hot avg < 3000ms" \
    "$([[ ${HOT_AVG} -lt 3000 ]] && echo true || echo false)" \
    "got ${HOT_AVG}ms"

echo
echo -e "${CYAN}=== Cell 2: RiskCheck P95 < 1000ms ===${NC}"

declare -a RC_TIMINGS=()
for i in $(seq 1 10); do
    START=$(date +%s%3N)
    curl -s -o /dev/null --max-time 5 \
        -X POST -H 'Content-Type: application/json' \
        --cookie "${COOKIE_JAR}" \
        -d '{"content":"rm -rf /","targetType":"command"}' \
        "${BASE_URL}/api/security/risk-check" >/dev/null 2>&1 || true
    ELAPSED=$(( $(date +%s%3N) - START ))
    RC_TIMINGS+=("${ELAPSED}")
done

# Compute P95 (sorted, index at 0.95 * N)
SORTED_TIMINGS=($(printf '%s\n' "${RC_TIMINGS[@]}" | sort -n))
P95_INDEX=$((95 * ${#SORTED_TIMINGS[@]} / 100))
RC_P95="${SORTED_TIMINGS[${P95_INDEX}]}"
log "  RiskCheck P95: ${RC_P95}ms (samples: ${SORTED_TIMINGS[*]})"
assert "RiskCheck P95 < 1000ms" \
    "$([[ ${RC_P95} -lt 1000 ]] && echo true || echo false)" \
    "got ${RC_P95}ms"

echo
echo -e "${CYAN}=== Cell 3: 健康巡检 (8 工具 fan-out) < 30000ms ===${NC}"

HEALTH_TOTAL=0
HEALTH_COUNT=3
for i in $(seq 1 ${HEALTH_COUNT}); do
    START=$(date +%s%3N)
    http_post "/api/chat/send" '{"content":"检查系统健康"}' >/dev/null
    ELAPSED=$(( $(date +%s%3N) - START ))
    HEALTH_TOTAL=$((HEALTH_TOTAL + ELAPSED))
    log "  health #${i}: ${ELAPSED}ms"
done
HEALTH_AVG=$((HEALTH_TOTAL / HEALTH_COUNT))
assert "健康巡检 avg < 30000ms" \
    "$([[ ${HEALTH_AVG} -lt 30000 ]] && echo true || echo false)" \
    "got ${HEALTH_AVG}ms"

echo
echo -e "${CYAN}=== Cell 4: 普通 chat < 10000ms ===${NC}"

CHAT_TOTAL=0
CHAT_COUNT=3
for i in $(seq 1 ${CHAT_COUNT}); do
    START=$(date +%s%3N)
    http_post "/api/chat/send" '{"content":"你好"}' >/dev/null
    ELAPSED=$(( $(date +%s%3N) - START ))
    CHAT_TOTAL=$((CHAT_TOTAL + ELAPSED))
    log "  chat #${i}: ${ELAPSED}ms"
done
CHAT_AVG=$((CHAT_TOTAL / CHAT_COUNT))
assert "普通 chat avg < 10000ms" \
    "$([[ ${CHAT_AVG} -lt 10000 ]] && echo true || echo false)" \
    "got ${CHAT_AVG}ms"

echo
echo -e "${CYAN}=== Cell 5: 报告生成 < 5000ms ===${NC}"

# Step 1: get a real auditId from a fresh chat
http_post "/api/chat/send" '{"content":"查看 CPU 状态"}' >/dev/null
AUDIT_ID="$(json_extract "${HTTP_BODY}" "auditId")"
if [[ -z "${AUDIT_ID}" || "${AUDIT_ID}" == "None" ]]; then
    err "无法获取 auditId 用于报告生成; body=${HTTP_BODY:0:300}"
    exit 1
fi
log "  using auditId: ${AUDIT_ID:0:16}..."

REPORT_TOTAL=0
REPORT_COUNT=3
for i in $(seq 1 ${REPORT_COUNT}); do
    START=$(date +%s%3N)
    http_post "/api/reports/generate" "{\"auditId\":\"${AUDIT_ID}\"}" >/dev/null
    ELAPSED=$(( $(date +%s%3N) - START ))
    REPORT_TOTAL=$((REPORT_TOTAL + ELAPSED))
    log "  report #${i}: ${ELAPSED}ms"
done
REPORT_AVG=$((REPORT_TOTAL / REPORT_COUNT))
assert "报告生成 avg < 5000ms" \
    "$([[ ${REPORT_AVG} -lt 5000 ]] && echo true || echo false)" \
    "got ${REPORT_AVG}ms"

echo
echo "================================================================"
echo " 性能 Gate 汇总 (vs PRD §12.3 预算)"
echo "================================================================"
printf " %-35s %-12s %-12s %s\n" "指标" "实测" "预算" "状态"
printf " %-35s %-12s %-12s %s\n" "单 L0 工具 (cold)" "${COLD_ELAPSED}ms" "3000ms" \
    "$([[ ${COLD_ELAPSED} -lt 3000 ]] && echo "✓" || echo "✗")"
printf " %-35s %-12s %-12s %s\n" "单 L0 工具 (hot avg)" "${HOT_AVG}ms" "3000ms" \
    "$([[ ${HOT_AVG} -lt 3000 ]] && echo "✓" || echo "✗")"
printf " %-35s %-12s %-12s %s\n" "RiskCheck P95" "${RC_P95}ms" "1000ms" \
    "$([[ ${RC_P95} -lt 1000 ]] && echo "✓" || echo "✗")"
printf " %-35s %-12s %-12s %s\n" "健康巡检 avg" "${HEALTH_AVG}ms" "30000ms" \
    "$([[ ${HEALTH_AVG} -lt 30000 ]] && echo "✓" || echo "✗")"
printf " %-35s %-12s %-12s %s\n" "普通 chat avg" "${CHAT_AVG}ms" "10000ms" \
    "$([[ ${CHAT_AVG} -lt 10000 ]] && echo "✓" || echo "✗")"
printf " %-35s %-12s %-12s %s\n" "报告生成 avg" "${REPORT_AVG}ms" "5000ms" \
    "$([[ ${REPORT_AVG} -lt 5000 ]] && echo "✓" || echo "✗")"

echo
echo " TOTAL: ${TOTAL}  PASS: ${PASSED}  FAIL: ${FAILED}  N/A: ${NA_COUNT}"
exit $((FAILED > 0 ? 1 : 0))
