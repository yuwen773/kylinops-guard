<script setup lang="ts">
// App shell — top bar + sidebar + content area.
// Sidebar order is locked by the Phase 2 task card and the demo video script;
// do not reorder these items without updating the spec and the script.
import { computed, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { logout as logoutApi } from '@/api/auth';
import { getSession } from '@/auth/session';
import {
  ChatDotSquare,
  Monitor,
  Tools,
  WarningFilled,
  Document,
  DataAnalysis,
  Sunny,
  Moon,
} from '@element-plus/icons-vue';
import { useTheme } from '@/composables/useTheme';

const route = useRoute();
const router = useRouter();

// Top-bar product name. Mandated by the v0.1 PRD — changing this string
// is a spec deviation.
const PRODUCT_NAME = '麒麟安全智能运维 Agent';

// Sidebar items with icons. Path maps 1:1 to the router in @/router/index.ts.
const navItems = [
  { path: '/chat', label: '对话控制台', icon: ChatDotSquare },
  { path: '/dashboard', label: '系统总览', icon: Monitor },
  { path: '/tools', label: '工具中心', icon: Tools },
  { path: '/security', label: '安全中心', icon: WarningFilled },
  { path: '/audit', label: '审计日志', icon: Document },
  { path: '/reports', label: '报告中心', icon: DataAnalysis },
] as const;

const activeIndex = (path: string) => navItems.findIndex((i) => i.path === path);

// Active user — read from the in-memory session. Empty string when the
// layout is mounted in test without a session (allowed because the
// layout itself is route-guarded and never reached unauthenticated in
// production).
const username = computed<string>(() => getSession()?.username ?? '');

// Theme toggle — sun icon when dark (action: switch to light)
const { theme, toggleTheme } = useTheme();
const themeIcon = computed(() => (theme.value === 'dark' ? Sunny : Moon));
const themeAriaLabel = computed(() =>
  theme.value === 'dark' ? '切换到亮色主题' : '切换到暗色主题',
);

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
      <span class="app-header-spacer" />
      <span v-if="username" class="app-user" data-testid="app-user">{{ username }}</span>
      <el-button
        size="small"
        circle
        :icon="themeIcon"
        class="app-theme-toggle"
        data-testid="app-theme-toggle"
        :aria-label="themeAriaLabel"
        @click="toggleTheme"
      />
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
            <el-icon class="app-menu-icon">
              <component :is="item.icon" />
            </el-icon>
            <span>{{ item.label }}</span>
          </el-menu-item>
        </el-menu>
      </el-aside>
      <el-main class="app-main">
        <router-view v-slot="{ Component }">
          <transition name="kg-page" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
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
  width: 30px;
  height: 30px;
  border-radius: var(--kg-radius-sm);
  background: linear-gradient(135deg, var(--kg-color-primary-soft), var(--kg-color-accent-soft));
  color: var(--kg-color-accent-hover);
  font-family: var(--kg-font-mono);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.05em;
  flex-shrink: 0;
  position: relative;
}

.app-brand__mark::after {
  content: '';
  position: absolute;
  inset: -2px;
  border-radius: calc(var(--kg-radius-sm) + 2px);
  background: linear-gradient(135deg, rgba(59, 130, 246, 0.15), rgba(6, 182, 212, 0.15));
  z-index: -1;
  filter: blur(4px);
}

.app-brand__text {
  display: inline-flex;
  flex-direction: column;
  gap: 1px;
}

.app-title {
  font-size: var(--kg-text-md);
  font-weight: 600;
  color: var(--kg-color-text-primary);
  white-space: nowrap;
  line-height: 1.3;
}

.app-codename {
  color: var(--kg-color-text-mute);
  font-size: var(--kg-text-xs);
  font-family: var(--kg-font-mono);
  letter-spacing: 0.04em;
  white-space: nowrap;
  display: block;
  line-height: 1.2;
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

.app-theme-toggle {
  margin-left: var(--kg-space-2);
  color: var(--kg-color-text-secondary);
}

.app-theme-toggle:hover {
  color: var(--kg-color-text-primary);
  background-color: var(--kg-color-surface-soft);
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
  padding-top: var(--kg-space-2);
}

.app-menu .el-menu-item {
  display: flex;
  align-items: center;
  gap: var(--kg-space-3);
  height: 44px;
  line-height: 44px;
  padding: 0 var(--kg-space-5);
  margin: 2px var(--kg-space-2);
  border-radius: var(--kg-radius-sm);
  transition: background var(--kg-transition-fast), color var(--kg-transition-fast);
}

.app-menu .el-menu-item:hover {
  background: var(--kg-color-surface-soft);
  color: var(--kg-color-text-primary);
}

.app-menu .el-menu-item.is-active {
  color: var(--kg-color-primary-hover);
  background: var(--kg-color-primary-soft);
  border-left: none;
  position: relative;
}

.app-menu .el-menu-item.is-active::before {
  content: '';
  position: absolute;
  left: 0;
  top: 50%;
  transform: translateY(-50%);
  width: 3px;
  height: 20px;
  border-radius: 0 2px 2px 0;
  background: var(--kg-color-primary);
}

.app-menu-icon {
  font-size: 18px;
  color: var(--kg-color-text-mute);
  transition: color var(--kg-transition-fast);
}

.el-menu-item:hover .app-menu-icon,
.el-menu-item.is-active .app-menu-icon {
  color: var(--kg-color-primary-hover);
}

.app-main {
  background: var(--kg-color-bg);
  padding: var(--kg-space-6);
  min-height: calc(100vh - 56px);
}

/* Page transition */
.kg-page-enter-active {
  animation: kg-fadeIn 0.25s ease-out, kg-slideUp 0.3s ease-out;
}

.kg-page-leave-active {
  animation: kg-fadeIn 0.15s ease-in reverse;
}

.is-active-item {
  /* element-plus applies its own active style; this hook is here so unit
     tests can target an explicit class without depending on internal
     theme selectors. */
}
</style>
