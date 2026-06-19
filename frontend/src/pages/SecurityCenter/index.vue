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

const levelCardClass = (lv: RiskLevel): string =>
  `security-level-card security-level-card--${lv.toLowerCase()}`;

const eventCardClass = (ev: { riskLevel?: string }): string =>
  `kg-event-card kg-event-card--${(ev.riskLevel ?? 'l4').toLowerCase()}`;

const DECISION_ACTION_LABEL: Readonly<Record<RiskDecision, string>> = {
  ALLOW: '允许执行',
  CONFIRM: '确认后执行',
  BLOCK: '阻断执行',
};

const DECISION_SYSTEM_ACTION: Readonly<Record<RiskDecision, string>> = {
  ALLOW: '直接返回',
  CONFIRM: '待用户确认',
  BLOCK: '写入审计日志',
};

const decisionLabel = (decision: RiskDecision | undefined): string =>
  decision ? DECISION_ACTION_LABEL[decision] : '—';

const decisionSystemAction = (decision: RiskDecision | undefined): string =>
  decision ? DECISION_SYSTEM_ACTION[decision] : '—';

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

      <div class="kg-safety-summary animate-in animate-delay-1" data-testid="security-summary">
        <div class="kg-safety-tile kg-safety-tile--levels">
          <span class="kg-safety-tile__label">风险等级策略</span>
          <span class="kg-safety-tile__value">{{ sortedLevels.length }} 级</span>
          <span class="kg-safety-tile__sub">
            <span class="kg-safety-tile__dot" style="background:var(--kg-color-risk-l0)"></span>
            <span class="kg-safety-tile__dot" style="background:var(--kg-color-risk-l1)"></span>
            <span class="kg-safety-tile__dot" style="background:var(--kg-color-risk-l2)"></span>
            <span class="kg-safety-tile__dot" style="background:var(--kg-color-risk-l3)"></span>
            <span class="kg-safety-tile__dot" style="background:var(--kg-color-risk-l4)"></span>
            L0–L4
          </span>
        </div>
        <div class="kg-safety-tile kg-safety-tile--events">
          <span class="kg-safety-tile__label">阻断事件</span>
          <span class="kg-safety-tile__value">{{ eventsTotal }}</span>
          <span class="kg-safety-tile__sub">历史拦截记录</span>
        </div>
        <div class="kg-safety-tile kg-safety-tile--inject">
          <span class="kg-safety-tile__label">注入检测</span>
          <span class="kg-safety-tile__value">独立旁路</span>
          <span class="kg-safety-tile__sub">语义识别 · L4 阻断</span>
        </div>
        <div class="kg-safety-tile kg-safety-tile--audit">
          <span class="kg-safety-tile__label">可审计追踪</span>
          <span class="kg-safety-tile__value">{{ totalRuleCount }} 条</span>
          <span class="kg-safety-tile__sub">已加载规则</span>
        </div>
      </div>

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
        class="security-levels-grid animate-in animate-delay-2"
        data-testid="security-levels-grid"
      >
        <el-card
          v-for="lv in sortedLevels"
          :key="lv.level"
          :class="levelCardClass(lv.level)"
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
            决策：{{ decisionLabel(lv.decision) }}
          </p>
          <p v-if="lv.decision" class="security-level-system-action">
            系统动作：{{ decisionSystemAction(lv.decision) }}
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

      <div class="kg-inject-card kg-card animate-in animate-delay-2" data-testid="security-inject-card">
        <div class="kg-card__header">
          <span class="kg-inject-icon">🛡</span>
          <p class="kg-card__title">Prompt 注入检测</p>
        </div>
        <div class="kg-card__body">
          <p style="margin: 0; font-size: var(--kg-text-sm); color: var(--kg-color-text-secondary); line-height: var(--kg-line-base);">
            独立于 L0–L4 矩阵的旁路检测层，对所有用户输入执行语义识别，命中规则后立即升级为
            <strong style="color: var(--kg-color-risk-inject);">L4 阻断</strong>
            并写入审计日志。
          </p>
        </div>
      </div>
    </section>

    <!-- Section B: rules snapshot, grouped by level -->
    <section
      class="security-rules-section animate-in animate-delay-3"
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
                  {{ decisionLabel(rule.riskDecision) }}
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
      class="security-events-section animate-in animate-delay-3"
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
        <div class="kg-event-list" data-testid="security-events-list">
          <div
            v-for="ev in events"
            :key="ev.auditId"
            :class="eventCardClass(ev)"
            :data-testid="`security-event-${ev.auditId}`"
            @click="onEventRowClick(ev.auditId)"
          >
            <div class="kg-event-card__row">
              <span class="kg-event-card__id">{{ ev.auditId }}</span>
              <span class="security-event-level">{{ ev.riskLevel }}</span>
              <span class="security-event-decision">{{ decisionLabel(ev.decision) }}</span>
              <span class="kg-event-card__meta">{{ formatTimestamp(ev.createdAt) }}</span>
            </div>
            <p
              v-if="ev.matchedRules && ev.matchedRules.length > 0"
              class="kg-event-card__reason"
            >
              命中规则：{{ ev.matchedRules.join('、') }}
            </p>
            <p v-if="ev.reason" class="kg-event-card__reason">
              原因：{{ ev.reason }}
            </p>
          </div>
        </div>

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
/* ==========================================================================
 * SecurityCenter — SOC Dashboard visual design
 *
 * Design intent: authoritative, polished, with risk-level colors as strong
 * visual anchors. The G1 green-tinted light theme provides the base; this
 * page adds a "security operations center" feel through risk-colored accent
 * bars, timeline-like event cards, and refined typographic hierarchy.
 * ========================================================================== */

/* --------------------------------------------------------------------------
 * Page layout
 * -------------------------------------------------------------------------- */

.security-page {
  display: flex;
  flex-direction: column;
  gap: var(--kg-space-7);
  max-width: 1280px;
  margin: 0 auto;
  width: 100%;
  animation: security-fadeIn 400ms ease-out;
}

/* --------------------------------------------------------------------------
 * Entrance animations — staggered section reveals
 * -------------------------------------------------------------------------- */

.animate-in {
  opacity: 0;
  transform: translateY(12px);
  animation: security-slideUp 450ms cubic-bezier(0.21, 0.9, 0.35, 1) forwards;
}

.animate-delay-1 { animation-delay: 60ms; }
.animate-delay-2 { animation-delay: 140ms; }
.animate-delay-3 { animation-delay: 240ms; }

@keyframes security-fadeIn {
  from { opacity: 0; }
  to { opacity: 1; }
}

@keyframes security-slideUp {
  from {
    opacity: 0;
    transform: translateY(12px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

/* --------------------------------------------------------------------------
 * Section A — L0..L4 risk level catalog
 * -------------------------------------------------------------------------- */

.security-levels-section {
  display: flex;
  flex-direction: column;
  gap: var(--kg-space-4);
}

.security-levels-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(230px, 1fr));
  gap: var(--kg-space-3);
}

/* Level cards — each card gets a risk-colored top accent bar */
.security-level-card {
  height: 100%;
  border-top: 3px solid var(--kg-color-border-mute) !important;
  border-radius: var(--kg-radius-md) !important;
  transition: transform var(--kg-transition-base),
    box-shadow var(--kg-transition-base);
}

.security-level-card:hover {
  transform: translateY(-2px);
  box-shadow: var(--kg-shadow-card);
}

.security-level-card--l0 { border-top-color: var(--kg-color-risk-l0) !important; }
.security-level-card--l1 { border-top-color: var(--kg-color-risk-l1) !important; }
.security-level-card--l2 { border-top-color: var(--kg-color-risk-l2) !important; }
.security-level-card--l3 { border-top-color: var(--kg-color-risk-l3) !important; }
.security-level-card--l4 { border-top-color: var(--kg-color-risk-l4) !important; }

.security-level-card-header {
  display: flex;
  align-items: center;
  gap: var(--kg-space-2);
}

.security-level-label {
  font-weight: 600;
  color: var(--kg-color-text-primary);
  font-size: var(--kg-text-md);
}

.security-level-decision {
  margin: 0 0 var(--kg-space-1) 0;
  font-size: var(--kg-text-sm);
  color: var(--kg-color-text-secondary);
}

.security-level-system-action {
  margin: 0 0 var(--kg-space-2) 0;
  font-size: var(--kg-text-xs);
  color: var(--kg-color-text-mute);
  font-family: var(--kg-font-mono);
}

.security-level-description {
  margin: 0 0 var(--kg-space-1) 0;
  color: var(--kg-color-text-primary);
  font-size: var(--kg-text-md);
  line-height: var(--kg-line-base);
}

.security-level-examples {
  margin: 0;
  font-size: var(--kg-text-sm);
  color: var(--kg-color-text-mute);
  word-break: break-word;
}

/* --------------------------------------------------------------------------
 * Section B — Rules snapshot (grouped, read-only, code-like display)
 * -------------------------------------------------------------------------- */

.security-rules-section {
  display: flex;
  flex-direction: column;
  gap: var(--kg-space-2);
}

.security-rule-group {
  margin-bottom: 0;
  border-radius: var(--kg-radius-md);
  overflow: hidden;
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
  font-size: var(--kg-text-sm);
  letter-spacing: 0.04em;
  font-family: var(--kg-font-mono);
}

.security-rule-group[data-testid*="L4"] .security-rule-group-level {
  background: var(--kg-color-risk-l4-soft);
  color: var(--kg-color-risk-l4);
}
.security-rule-group[data-testid*="L3"] .security-rule-group-level {
  background: var(--kg-color-risk-l3-soft);
  color: var(--kg-color-risk-l3);
}
.security-rule-group[data-testid*="L2"] .security-rule-group-level {
  background: var(--kg-color-risk-l2-soft);
  color: var(--kg-color-risk-l2);
}
.security-rule-group[data-testid*="L1"] .security-rule-group-level {
  background: var(--kg-color-risk-l1-soft);
  color: var(--kg-color-risk-l1);
}
.security-rule-group[data-testid*="L0"] .security-rule-group-level {
  background: var(--kg-color-risk-l0-soft);
  color: var(--kg-color-risk-l0);
}

.security-rule-group-label {
  font-weight: 600;
  color: var(--kg-color-text-primary);
}

.security-rule-group-count {
  font-size: var(--kg-text-xs);
  color: var(--kg-color-text-mute);
  margin-left: auto;
  font-family: var(--kg-font-mono);
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
  border-radius: var(--kg-radius-sm);
  transition: border-color var(--kg-transition-fast),
    background var(--kg-transition-fast);
}

.security-rule-item:hover {
  border-color: var(--kg-color-border);
  background: var(--kg-color-surface);
}

.security-rule-item-disabled {
  opacity: 0.6;
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
  font-weight: 600;
}

.security-rule-decision {
  font-size: var(--kg-text-sm);
  font-weight: 600;
}

.security-rule-group[data-testid*="L4"] .security-rule-decision,
.security-rule-group[data-testid*="L3"] .security-rule-decision {
  color: var(--kg-color-danger);
}
.security-rule-group[data-testid*="L2"] .security-rule-decision {
  color: var(--kg-color-warning);
}
.security-rule-group[data-testid*="L1"] .security-rule-decision {
  color: var(--kg-color-success);
}
.security-rule-group[data-testid*="L0"] .security-rule-decision {
  color: var(--kg-color-risk-l0);
}

.security-rule-disabled-tag {
  font-size: var(--kg-text-xs);
  color: var(--kg-color-text-mute);
  border: 1px solid var(--kg-color-border);
  padding: 1px var(--kg-space-2);
  border-radius: var(--kg-radius-sm);
  background: var(--kg-color-surface-code);
}

.security-rule-description {
  margin: var(--kg-space-1) 0 0 0;
  color: var(--kg-color-text-primary);
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
  font-size: var(--kg-text-xs);
  color: var(--kg-color-text-primary);
  background: var(--kg-color-surface-code);
  padding: 2px var(--kg-space-2);
  border: 1px solid var(--kg-color-border-mute);
  border-radius: var(--kg-radius-sm);
  word-break: break-all;
  line-height: 1.6;
}

/* --------------------------------------------------------------------------
 * Section C — BLOCK events timeline
 * -------------------------------------------------------------------------- */

.security-events-section {
  display: flex;
  flex-direction: column;
  gap: var(--kg-space-2);
}

.security-event-level {
  font-weight: 700;
  font-family: var(--kg-font-mono);
  font-size: var(--kg-text-sm);
  padding: 1px 6px;
  border-radius: var(--kg-radius-sm);
  background: var(--kg-color-danger-soft);
  color: var(--kg-color-danger);
  letter-spacing: 0.04em;
}

.security-event-decision {
  font-size: var(--kg-text-sm);
  color: var(--kg-color-danger);
  font-weight: 600;
}

.security-events-pagination {
  margin-top: var(--kg-space-3);
  justify-content: flex-end;
}
</style>
