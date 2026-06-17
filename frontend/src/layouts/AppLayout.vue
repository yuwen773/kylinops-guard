<script setup lang="ts">
// App shell — top bar + sidebar + content area.
// Sidebar order is locked by the Phase 2 task card and the demo video script;
// do not reorder these items without updating the spec and the script.
import { computed, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { logout as logoutApi } from '@/api/auth';
import { getSession } from '@/auth/session';

const route = useRoute();
const router = useRouter();

// Top-bar product name. Mandated by the v0.1 PRD — changing this string
// is a spec deviation.
const PRODUCT_NAME = '麒麟安全智能运维 Agent';

// Sidebar items. Path maps 1:1 to the router in @/router/index.ts.
const navItems = [
  { path: '/chat', label: '对话控制台' },
  { path: '/dashboard', label: '系统总览' },
  { path: '/tools', label: '工具中心' },
  { path: '/security', label: '安全中心' },
  { path: '/audit', label: '审计日志' },
  { path: '/reports', label: '报告中心' },
] as const;

const activeIndex = (path: string) => navItems.findIndex((i) => i.path === path);

// Active user — read from the in-memory session. Empty string when the
// layout is mounted in test without a session (allowed because the
// layout itself is route-guarded and never reached unauthenticated in
// production).
const username = computed<string>(() => getSession()?.username ?? '');

const isLoggingOut = ref(false);
async function handleLogout(): Promise<void> {
  if (isLoggingOut.value) return;
  isLoggingOut.value = true;
  try {
    await logoutApi();
  } finally {
    isLoggingOut.value = false;
    await router.replace('/login');
  }
}
</script>

<template>
  <el-container class="app-shell">
    <el-header class="app-header">
      <span class="app-brand">
        <span class="app-brand__mark" aria-hidden="true">KG</span>
        <span class="app-brand__text">
          <span class="app-title">{{ PRODUCT_NAME }}</span>
          <span class="app-codename">KylinOps Guard</span>
        </span>
      </span>
      <span class="app-subtitle">安全智能运维 Agent</span>
      <span class="app-header-spacer" />
      <span v-if="username" class="app-user" data-testid="app-user">{{ username }}</span>
      <el-button
        size="small"
        type="default"
        plain
        :loading="isLoggingOut"
        class="app-logout"
        data-testid="app-logout"
        @click="handleLogout"
      >
        登出
      </el-button>
    </el-header>
    <el-container class="app-body">
      <el-aside class="app-aside" width="220px">
        <el-menu
          class="app-menu"
          :default-active="route.path"
          router
        >
          <el-menu-item
            v-for="(item, index) in navItems"
            :key="item.path"
            :index="item.path"
            :class="{ 'is-active-item': activeIndex(item.path) === index }"
          >
            <span>{{ item.label }}</span>
          </el-menu-item>
        </el-menu>
      </el-aside>
      <el-main class="app-main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.app-shell {
  min-height: 100vh;
  background-color: var(--kg-color-bg);
}

.app-header {
  display: flex;
  align-items: center;
  gap: var(--kg-space-4);
  background: var(--kg-color-surface);
  color: var(--kg-color-text-primary);
  padding: 0 var(--kg-space-6);
  border-bottom: 1px solid var(--kg-color-border);
  height: 56px;
  z-index: var(--kg-z-header);
}

.app-brand {
  display: inline-flex;
  align-items: center;
  gap: var(--kg-space-2);
  min-width: 0;
}

.app-brand__mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: var(--kg-radius-sm);
  background: var(--kg-color-primary-soft);
  color: var(--kg-color-primary-hover);
  font-family: var(--kg-font-mono);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.05em;
  flex-shrink: 0;
}

.app-title {
  font-size: var(--kg-text-md);
  font-weight: 600;
  color: var(--kg-color-text-primary);
  white-space: nowrap;
}

.app-codename {
  color: var(--kg-color-text-mute);
  font-size: var(--kg-text-xs);
  font-family: var(--kg-font-mono);
  letter-spacing: 0.04em;
  white-space: nowrap;
}

.app-subtitle {
  color: var(--kg-color-text-mute);
  font-size: var(--kg-text-xs);
  margin-left: var(--kg-space-3);
  padding-left: var(--kg-space-3);
  border-left: 1px solid var(--kg-color-border);
  white-space: nowrap;
  display: none;
}

@media (min-width: 1100px) {
  .app-subtitle { display: inline; }
}

.app-header-spacer {
  flex: 1;
}

.app-user {
  color: var(--kg-color-text-secondary);
  font-size: var(--kg-text-sm);
}

.app-logout {
  margin-left: var(--kg-space-2);
}

.app-body {
  min-height: calc(100vh - 56px);
}

.app-aside {
  background: var(--kg-color-surface);
  border-right: 1px solid var(--kg-color-border);
  width: 220px;
}

.app-menu {
  border-right: none;
}

.app-main {
  background: var(--kg-color-bg);
  padding: var(--kg-space-6);
  min-height: calc(100vh - 56px);
}

.is-active-item {
  /* Element Plus applies its own active style; this hook is here so unit
     tests can target an explicit class without depending on internal
     theme selectors. */
}
</style>
