<script setup lang="ts">
// App shell — top bar + sidebar + content area.
// Sidebar order is locked by the Phase 2 task card and the demo video script;
// do not reorder these items without updating the spec and the script.
import { useRoute } from 'vue-router';

const route = useRoute();

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
</script>

<template>
  <el-container class="app-shell">
    <el-header class="app-header">
      <span class="app-title">{{ PRODUCT_NAME }}</span>
      <span class="app-codename">KylinOps Guard</span>
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
