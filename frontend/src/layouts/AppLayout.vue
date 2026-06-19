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
  { path: '/notification-settings', label: '通知配置' },
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
      <span class="app-title">{{ PRODUCT_NAME }}</span>
      <span class="app-codename">KylinOps Guard</span>
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
}

.app-header {
  display: flex;
  align-items: center;
  gap: 1rem;
  background: #1f2d3d;
  color: #fff;
  padding: 0 1.5rem;
}

.app-title {
  font-size: 1.1rem;
  font-weight: 600;
}

.app-codename {
  color: #8a99b3;
  font-size: 0.85rem;
}

.app-header-spacer {
  flex: 1;
}

.app-user {
  color: #d4dae3;
  font-size: 0.85rem;
}

.app-logout {
  /* Element Plus default button styling — overriding only spacing so it
     fits the dark header. */
  margin-left: 0.5rem;
}

.app-body {
  min-height: calc(100vh - 60px);
}

.app-aside {
  background: #ffffff;
  border-right: 1px solid #e6e8eb;
}

.app-menu {
  border-right: none;
}

.app-main {
  background: #f5f7fa;
  padding: 1.5rem;
}

.is-active-item {
  /* Element Plus applies its own active style; this hook is here so unit
     tests can target an explicit class without depending on internal
     theme selectors. */
}
</style>
