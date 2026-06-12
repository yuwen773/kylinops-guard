import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router';

/**
 * Phase 2 task-card-mandated route set. Do not add ad-hoc routes here —
 * each business page maps 1:1 to a contract from 任务卡 Task 13–17.
 */
const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/chat' },
  {
    path: '/chat',
    name: 'chat',
    component: () => import('@/pages/ChatConsole/index.vue'),
    meta: { title: '对话控制台' },
  },
  {
    path: '/dashboard',
    name: 'dashboard',
    component: () => import('@/pages/Dashboard/index.vue'),
    meta: { title: '系统总览' },
  },
  {
    path: '/tools',
    name: 'tools',
    component: () => import('@/pages/ToolCenter/index.vue'),
    meta: { title: '工具中心' },
  },
  {
    path: '/security',
    name: 'security',
    component: () => import('@/pages/SecurityCenter/index.vue'),
    meta: { title: '安全中心' },
  },
  {
    path: '/audit',
    name: 'audit',
    component: () => import('@/pages/AuditLog/index.vue'),
    meta: { title: '审计日志' },
  },
  {
    path: '/reports',
    name: 'reports',
    component: () => import('@/pages/ReportCenter/index.vue'),
    meta: { title: '报告中心' },
  },
  // Fallback: unknown path lands on ChatConsole. Safety policy stays
  // unchanged — we do NOT redirect to a shell or 404 page that could
  // mislead the demo.
  { path: '/:pathMatch(.*)*', redirect: '/chat' },
];

const router = createRouter({
  history: createWebHistory(),
  routes,
});

export default router;
