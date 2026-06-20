import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router';
import { fetchSession } from '@/api/auth';
import { getSession } from '@/auth/session';

/**
 * Phase 2 task-card-mandated route set. Do not add ad-hoc routes here —
 * each business page maps 1:1 to a contract from 任务卡 Task 13–17.
 *
 * P2-T5 additions:
 *   - `/login` is a public route that renders without `AppLayout`.
 *   - A global `beforeEach` guard hits `GET /api/auth/session` for any
 *     non-public route and bounces to `/login` on 401. The in-memory
 *     session is treated as cached: once populated, the guard does not
 *     re-fetch within the same SPA mount.
 */
const PUBLIC_ROUTES = new Set(['/login']);

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/pages/Login/index.vue'),
    meta: { title: '管理员登录', public: true },
  },
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
  {
    path: '/notification-settings',
    name: 'notification-settings',
    component: () => import('@/pages/NotificationSettings/index.vue'),
    meta: { title: '通知配置' },
  },
  {
    path: '/inspections',
    name: 'inspections',
    component: () => import('@/pages/ScheduledInspection/index.vue'),
    meta: { title: '定时巡检' },
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

/**
 * Guarded navigation: confirms there is a valid session before letting the
 * router land on any protected page.
 *
 * Caching policy:
 *   - If the in-memory session is already populated, allow the navigation
 *     without re-hitting the backend.
 *   - Otherwise call `fetchSession()` which returns `null` on 401 and
 *     repopulates the store on success.
 *   - A second navigation inside the same SPA mount will therefore use
 *     the cached session and avoid an extra round-trip.
 */
router.beforeEach(async (to) => {
  // Public routes (currently only /login) always pass through.
  if (PUBLIC_ROUTES.has(to.path) || to.meta?.public === true) {
    return true;
  }

  // Already have a session? Continue.
  if (getSession() !== null) {
    return true;
  }

  // No cached session — ask the backend.
  try {
    const session = await fetchSession();
    if (session !== null) {
      return true;
    }
  } catch {
    // Network errors land here. Treat as unauthenticated for the guard's
    // purposes — the user can still retry from the login page.
  }
  return { path: '/login' };
});

export default router;
