<script setup lang="ts">
// SecurityCenter — Phase 2 Task 8 / Task 16.
//
// Responsibilities (locked by 任务卡 §4 + 演示视频脚本 §3.4):
//   * Top section: five L0..L4 cards (level + decision + Chinese description).
//   * Middle section: rules snapshot, grouped by riskLevel, regex shown as
//     read-only technical evidence. No edit, no toggle, no switch.
//   * Bottom section: paged BLOCK audit events. Clicking a row navigates to
//     /audit?auditId=... via router.push so the existing AuditLog page can
//     pick up the auditId from the URL and open its detail drawer.
//   * The three sections load INDEPENDENTLY. A failure in one (transport
//     or business) degrades only that section; the other two keep
//     rendering.
//   * No risk evaluation happens here. The backend verdict is displayed
//     verbatim — we never lower an L3/L4 to L2, never auto-confirm L2.
//
// Safety contract:
//   * Read-only. No POST / PUT / DELETE anywhere in this module.
//   * No raw shell, no command forwarding. The only outbound calls are
//     GET /api/security/risk-levels, GET /api/security/rules, and
//     GET /api/security/events.

import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import {
  getRiskLevels,
  getSecurityEvents,
  getSecurityRules,
  type SecurityEventQuery,
} from '@/api/security';
import { ApiError } from '@/api/client';
import {
  RISK_DECISION_LABELS,
  RISK_LEVEL_LABELS,
  type RiskDecision,
  type RiskLevel,
} from '@/types/safety';
import type {
  RiskLevelView,
  SecurityEventPage,
  SecurityEventView,
  SecurityRuleView,
} from '@/types/security';
import AppSectionHeader from '@/components/common/AppSectionHeader.vue';
import AppLoadingState from '@/components/common/AppLoadingState.vue';
import AppErrorState from '@/components/common/AppErrorState.vue';
import AppEmptyState from '@/components/common/AppEmptyState.vue';
import AppRiskBadge from '@/components/common/AppRiskBadge.vue';

const router = useRouter();

const DEFAULT_SIZE = 20;

// ---------------------------------------------------------------------------
// Section A — L0..L4 catalog
// ---------------------------------------------------------------------------

const levels = ref<RiskLevelView[]>([]);
const levelsLoading = ref(false);
const levelsError = ref<string | null>(null);

const sortedLevels = computed<RiskLevelView[]>(() => {
  // Backend returns them in L0..L4 order, but we sort defensively so the
  // visual order stays stable even if the wire order ever changes.
  const order: RiskLevel[] = ['L0', 'L1', 'L2', 'L3', 'L4'];
  return [...levels.value].sort(
    (a, b) => order.indexOf(a.level) - order.indexOf(b.level),
  );
});

const levelDecisionLabel = (decision: RiskDecision | undefined): string =>
  decision ? `${decision} ${RISK_DECISION_LABELS[decision]}` : '—';

const levelChineseLabel = (level: RiskLevel): string =>
  RISK_LEVEL_LABELS[level] ?? level;

const fetchLevels = async () => {
  levelsLoading.value = true;
  levelsError.value = null;
  try {
    levels.value = await getRiskLevels();
  } catch (err) {
    const e = err as ApiError;
    levelsError.value = e?.message ?? '风险等级目录加载失败';
    levels.value = [];
  } finally {
    levelsLoading.value = false;
  }
};

// ---------------------------------------------------------------------------
// Section B — rules snapshot (grouped, read-only)
// ---------------------------------------------------------------------------

const rules = ref<SecurityRuleView[]>([]);
const rulesLoading = ref(false);
const rulesError = ref<string | null>(null);

const RULE_GROUP_ORDER: RiskLevel[] = ['L4', 'L3', 'L2', 'L1', 'L0'];

interface RuleGroup {
  level: RiskLevel;
  rules: SecurityRuleView[];
}

const groupedRules = computed<RuleGroup[]>(() => {
  const buckets = new Map<RiskLevel, SecurityRuleView[]>();
  for (const rule of rules.value) {
    if (!rule.riskLevel) continue;
    const list = buckets.get(rule.riskLevel) ?? [];
    list.push(rule);
    buckets.set(rule.riskLevel, list);
  }
  return RULE_GROUP_ORDER.filter((lvl) => buckets.has(lvl)).map((lvl) => ({
    level: lvl,
    rules: (buckets.get(lvl) ?? []).slice().sort((a, b) => {
      const pa = a.priority ?? 0;
      const pb = b.priority ?? 0;
      if (pa !== pb) return pb - pa;
      return (a.ruleId ?? '').localeCompare(b.ruleId ?? '');
    }),
  }));
});

const totalRuleCount = computed(() => rules.value.length);

const fetchRules = async () => {
  rulesLoading.value = true;
  rulesError.value = null;
  try {
    rules.value = await getSecurityRules();
  } catch (err) {
    const e = err as ApiError;
    rulesError.value = e?.message ?? '规则加载失败';
    rules.value = [];
  } finally {
    rulesLoading.value = false;
  }
};

// ---------------------------------------------------------------------------
// Section C — recent BLOCK events (paged)
// ---------------------------------------------------------------------------

const events = ref<SecurityEventView[]>([]);
const eventsLoading = ref(false);
const eventsError = ref<string | null>(null);
const eventsPage = ref(0);
const eventsSize = ref(DEFAULT_SIZE);
const eventsTotal = ref(0);

const eventsEmpty = computed(
  () =>
    !eventsLoading.value &&
    !eventsError.value &&
    events.value.length === 0,
);

const buildEventsQuery = (): SecurityEventQuery => ({
  page: eventsPage.value,
  size: eventsSize.value,
});

const fetchEvents = async () => {
  eventsLoading.value = true;
  eventsError.value = null;
  try {
    const result: SecurityEventPage = await getSecurityEvents(buildEventsQuery());
    events.value = result.content ?? [];
    eventsTotal.value = result.totalElements ?? 0;
  } catch (err) {
    const e = err as ApiError;
    eventsError.value = e?.message ?? '拦截事件加载失败';
    events.value = [];
    eventsTotal.value = 0;
  } finally {
    eventsLoading.value = false;
  }
};

const onEventsPageChange = (next: number) => {
  eventsPage.value = Math.max(0, next - 1);
  void fetchEvents();
};

const onEventsSizeChange = (next: number) => {
  eventsSize.value = next;
  eventsPage.value = 0;
  void fetchEvents();
};

// ---------------------------------------------------------------------------
// Row click → /audit?auditId=...
// ---------------------------------------------------------------------------

const onEventRowClick = (auditId: string | undefined) => {
  if (!auditId) return;
  void router.push({ path: '/audit', query: { auditId } });
};

const formatTimestamp = (iso: string | undefined): string => {
  if (!iso) return '—';
  // Backend uses LocalDateTime ISO; we only show the first 19 chars
  // (YYYY-MM-DDTHH:MM:SS), replacing the T with a space for readability.
  return iso.replace('T', ' ').slice(0, 19);
};

// ---------------------------------------------------------------------------
// Initial load — three independent fetches.
// ---------------------------------------------------------------------------

onMounted(async () => {
  // Fire all three in parallel. Each call has its own try/catch and its own
  // loading/error state, so one failure does not block the others.
  await Promise.all([fetchLevels(), fetchRules(), fetchEvents()]);
});
</script>

<template>
  <div class="security-page" data-testid="security-page">
    <!-- Section A: L0..L4 catalog -->
    <section
      class="security-levels-section"
      data-testid="security-levels-section"
    >
      <AppSectionHeader
        level="section"
        title="风险等级目录"
        subtitle="L0–L4 的等级与默认决策；前端仅展示后端结论，不参与决策"
      />

      <div
        v-if="levelsLoading"
        data-testid="security-levels-loading"
      >
        <AppLoadingState title="正在加载风险等级…" />
      </div>

      <div
        v-else-if="levelsError"
        data-testid="security-levels-error"
      >
        <AppErrorState
          variant="transient"
          :title="`加载失败：${levelsError}`"
        />
      </div>

      <div
        v-else
        class="security-levels-grid"
        data-testid="security-levels-grid"
      >
        <el-card
          v-for="lv in sortedLevels"
          :key="lv.level"
          class="security-level-card"
          shadow="never"
          :data-testid="`security-level-${lv.level}`"
        >
          <template #header>
            <div class="security-level-card-header">
              <AppRiskBadge
                :level="lv.level"
                compact
                :show-label="false"
                variant="soft"
              />
              <span class="security-level-label">
                {{ levelChineseLabel(lv.level) }}
              </span>
            </div>
          </template>
          <p class="security-level-decision">
            决策：{{ levelDecisionLabel(lv.decision) }}
          </p>
          <p v-if="lv.description" class="security-level-description">
            {{ lv.description }}
          </p>
          <p
            v-if="lv.examples && lv.examples.length > 0"
            class="security-level-examples"
          >
            典型场景：{{ lv.examples.join('、') }}
          </p>
        </el-card>
      </div>
    </section>

    <!-- Section B: rules snapshot, grouped by level -->
    <section
      class="security-rules-section"
      data-testid="security-rules-section"
    >
      <AppSectionHeader
        level="section"
        title="风险规则快照"
        :subtitle="`当前加载的规则不可变快照（合计 ${totalRuleCount} 条），仅供运维审计与排错查看`"
      />

      <div
        v-if="rulesLoading"
        data-testid="security-rules-loading"
      >
        <AppLoadingState title="正在加载规则…" />
      </div>

      <div
        v-else-if="rulesError"
        data-testid="security-rules-error"
      >
        <AppErrorState
          variant="transient"
          :title="`加载失败：${rulesError}`"
        />
      </div>

      <div
        v-else-if="groupedRules.length === 0"
        data-testid="security-rules-empty"
      >
        <AppEmptyState
          variant="default"
          title="暂无可用规则"
          description="后端尚未注册任何风险规则；新规则会通过 YAML 文件加载。"
        />
      </div>

      <div
        v-else
        class="security-rules-groups"
        data-testid="security-rules-groups"
      >
        <el-card
          v-for="group in groupedRules"
          :key="group.level"
          class="security-rule-group"
          shadow="never"
          :data-testid="`security-rule-group-${group.level}`"
        >
          <template #header>
            <div class="security-rule-group-header">
              <span class="security-rule-group-level">{{ group.level }}</span>
              <span class="security-rule-group-label">
                {{ levelChineseLabel(group.level) }}
              </span>
              <span class="security-rule-group-count">
                {{ group.rules.length }} 条
              </span>
            </div>
          </template>
          <ul class="security-rule-list">
            <li
              v-for="rule in group.rules"
              :key="rule.ruleId"
              class="security-rule-item"
              :class="{ 'security-rule-item-disabled': rule.enabled === false }"
              :data-testid="`security-rule-${rule.ruleId}`"
            >
              <div class="security-rule-row">
                <code class="security-rule-id">{{ rule.ruleId }}</code>
                <span
                  v-if="rule.riskDecision"
                  class="security-rule-decision"
                >
                  {{ rule.riskDecision }} {{ levelDecisionLabel(rule.riskDecision) }}
                </span>
                <span
                  v-if="rule.enabled === false"
                  class="security-rule-disabled-tag"
                >已停用</span>
              </div>
              <p
                v-if="rule.description"
                class="security-rule-description"
              >
                {{ rule.description }}
              </p>
              <p
                v-if="rule.reason"
                class="security-rule-reason"
              >
                命中原因：{{ rule.reason }}
              </p>
              <p
                v-if="rule.safeSuggestion"
                class="security-rule-suggestion"
              >
                安全建议：{{ rule.safeSuggestion }}
              </p>
              <p
                v-if="rule.regex"
                class="security-rule-regex"
                :data-testid="`security-rule-regex-${rule.ruleId}`"
              >
                <span class="security-rule-regex-label">正则：</span>
                <code class="security-rule-regex-value">{{ rule.regex }}</code>
              </p>
            </li>
          </ul>
        </el-card>
      </div>
    </section>

    <!-- Section C: recent BLOCK events -->
    <section
      class="security-events-section"
      data-testid="security-events-section"
    >
      <AppSectionHeader
        level="section"
        title="最近阻断事件"
        subtitle="按时间倒序展示审计侧已落库的 BLOCK 事件；点击任一行可跳转至审计详情"
      />

      <div
        v-if="eventsLoading"
        data-testid="security-events-loading"
      >
        <AppLoadingState title="正在加载阻断事件…" />
      </div>

      <div
        v-else-if="eventsError"
        data-testid="security-events-error"
      >
        <AppErrorState
          variant="transient"
          :title="`加载失败：${eventsError}`"
        />
      </div>

      <div
        v-else-if="eventsEmpty"
        data-testid="security-events-empty"
      >
        <AppEmptyState
          variant="no-event"
          title="暂无拦截事件"
          description="当前时间段内未发现安全阻断事件。"
        />
      </div>

      <template v-else>
        <el-table
          class="security-events-table"
          :data="events"
          :row-style="{ cursor: 'pointer' }"
          data-testid="security-events-table"
          @row-click="(row: SecurityEventView) => onEventRowClick(row.auditId)"
        >
          <el-table-column label="审计 ID" min-width="200">
            <template #default="{ row }">
              <code
                class="security-event-auditid"
                :data-testid="`security-event-${row.auditId}`"
              >{{ row.auditId }}</code>
            </template>
          </el-table-column>

          <el-table-column label="风险" width="120">
            <template #default="{ row }">
              <span class="security-event-level">
                {{ row.riskLevel }}
              </span>
            </template>
          </el-table-column>

          <el-table-column label="决策" width="100">
            <template #default="{ row }">
              <span class="security-event-decision">
                {{ row.decision }} {{ levelDecisionLabel(row.decision) }}
              </span>
            </template>
          </el-table-column>

          <el-table-column label="匹配规则" min-width="200">
            <template #default="{ row }">
              <span class="security-event-rules">
                {{
                  row.matchedRules && row.matchedRules.length > 0
                    ? row.matchedRules.join('、')
                    : '—'
                }}
              </span>
            </template>
          </el-table-column>

          <el-table-column label="阻断原因" min-width="220">
            <template #default="{ row }">
              <span class="security-event-reason">
                {{ row.reason || '—' }}
              </span>
            </template>
          </el-table-column>

          <el-table-column label="时间" width="180">
            <template #default="{ row }">
              <span class="security-event-time">
                {{ formatTimestamp(row.createdAt) }}
              </span>
            </template>
          </el-table-column>
        </el-table>

        <el-pagination
          v-if="eventsTotal > 0"
          class="security-events-pagination"
          :current-page="eventsPage + 1"
          :page-size="eventsSize"
          :page-sizes="[10, 20, 50]"
          :total="eventsTotal"
          layout="total, sizes, prev, pager, next, jumper"
          data-testid="security-events-pagination"
          @current-change="onEventsPageChange"
          @size-change="onEventsSizeChange"
        />
      </template>
    </section>
  </div>
</template>

<style scoped>
.security-page {
  display: flex;
  flex-direction: column;
  gap: var(--kg-space-6);
  max-width: 1280px;
  margin: 0 auto;
  width: 100%;
}

/* L0..L4 catalog */
.security-levels-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: var(--kg-space-3);
}

.security-level-card {
  height: 100%;
}

.security-level-card-header {
  display: flex;
  align-items: center;
  gap: var(--kg-space-2);
}

.security-level-label {
  font-weight: 600;
  color: var(--kg-color-text-primary);
}

.security-level-decision {
  margin: var(--kg-space-1) 0;
  font-size: var(--kg-text-sm);
  color: var(--kg-color-text-secondary);
}

.security-level-description {
  margin: var(--kg-space-1) 0;
  color: var(--kg-color-text-secondary);
  font-size: var(--kg-text-md);
  line-height: var(--kg-line-base);
}

.security-level-examples {
  margin: var(--kg-space-1) 0 0 0;
  font-size: var(--kg-text-sm);
  color: var(--kg-color-text-mute);
  word-break: break-word;
}

/* Rules */
.security-rule-group {
  margin-bottom: var(--kg-space-3);
}

.security-rule-group-header {
  display: flex;
  align-items: center;
  gap: var(--kg-space-2);
}

.security-rule-group-level {
  font-weight: 700;
  padding: 2px var(--kg-space-2);
  border-radius: var(--kg-radius-sm);
  background: var(--kg-color-danger-soft);
  color: var(--kg-color-danger);
  font-size: var(--kg-text-md);
}

.security-rule-group-label {
  font-weight: 600;
  color: var(--kg-color-text-primary);
}

.security-rule-group-count {
  font-size: var(--kg-text-sm);
  color: var(--kg-color-text-mute);
}

.security-rule-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--kg-space-2);
}

.security-rule-item {
  padding: var(--kg-space-2) var(--kg-space-3);
  background: var(--kg-color-surface-soft);
  border: 1px solid var(--kg-color-border-mute);
  border-left: 3px solid var(--kg-color-info);
  border-radius: var(--kg-radius-sm);
}

.security-rule-item-disabled {
  border-left-color: var(--kg-color-border-strong);
  opacity: 0.7;
}

.security-rule-row {
  display: flex;
  align-items: center;
  gap: var(--kg-space-2);
  flex-wrap: wrap;
}

.security-rule-id {
  font-family: var(--kg-font-mono);
  font-size: var(--kg-text-sm);
  color: var(--kg-color-text-primary);
}

.security-rule-decision {
  font-size: var(--kg-text-sm);
  color: var(--kg-color-danger);
  font-weight: 600;
}

.security-rule-disabled-tag {
  font-size: var(--kg-text-xs);
  color: var(--kg-color-text-mute);
  border: 1px solid var(--kg-color-border);
  padding: 1px var(--kg-space-2);
  border-radius: var(--kg-radius-sm);
}

.security-rule-description {
  margin: var(--kg-space-1) 0 0 0;
  color: var(--kg-color-text-secondary);
  font-size: var(--kg-text-md);
  line-height: var(--kg-line-base);
}

.security-rule-reason,
.security-rule-suggestion {
  margin: 2px 0 0 0;
  color: var(--kg-color-text-secondary);
  font-size: var(--kg-text-sm);
  line-height: var(--kg-line-base);
}

.security-rule-regex {
  margin: var(--kg-space-1) 0 0 0;
  display: flex;
  gap: var(--kg-space-1);
  align-items: baseline;
  flex-wrap: wrap;
}

.security-rule-regex-label {
  color: var(--kg-color-text-mute);
  font-size: var(--kg-text-sm);
}

.security-rule-regex-value {
  font-family: var(--kg-font-mono);
  font-size: var(--kg-text-sm);
  color: var(--kg-color-text-primary);
  background: var(--kg-color-surface);
  padding: 1px var(--kg-space-2);
  border: 1px solid var(--kg-color-border);
  border-radius: var(--kg-radius-sm);
  word-break: break-all;
}

/* Events */
.security-events-table {
  cursor: pointer;
}

.security-event-auditid {
  font-family: var(--kg-font-mono);
  font-size: var(--kg-text-sm);
  color: var(--kg-color-text-primary);
  word-break: break-all;
}

.security-event-level {
  font-weight: 600;
  color: var(--kg-color-danger);
}

.security-event-decision,
.security-event-rules,
.security-event-reason {
  color: var(--kg-color-text-secondary);
  font-size: var(--kg-text-sm);
  word-break: break-word;
}

.security-event-time {
  color: var(--kg-color-text-secondary);
  font-size: var(--kg-text-sm);
  font-family: var(--kg-font-mono);
}

.security-events-pagination {
  margin-top: var(--kg-space-3);
  justify-content: flex-end;
}
</style>
