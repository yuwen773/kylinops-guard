<script setup lang="ts">
/**
 * LandingNav - landing page 顶部导航。
 *
 * 与 AppLayout 顶部 nav 区分:
 *   - landing nav 是公开页 chrome,无侧边栏,导航链接是产品锚点而非业务路由
 *   - 主 CTA "进入控制台" 直跳 /login,而不是 chat
 *   - 滚动后增加 backdrop-filter blur (≤ 8px) + 1px 底边线,不上 shadow
 *
 * 设计规范 (P1-03):
 *   - 高度 64px
 *   - sticky top-0 z-header
 *   - nav 链接 4 个: 能力 / 工具 / 场景 / 平台 (锚点跳转)
 */
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()
const scrolled = ref(false)
const sentinelRef = ref<HTMLElement | null>(null)
let scrollObserver: IntersectionObserver | null = null

const navLinks = [
  { href: '#capabilities', label: '能力' },
  { href: '#tools', label: '工具' },
  { href: '#scenarios', label: '场景' },
  { href: '#platform', label: '平台' },
] as const

onMounted(() => {
  // design-taste-frontend §5.D: avoid raw `window.addEventListener('scroll')`
  // (mobile jank). Use IntersectionObserver on a 1px sentinel anchored at the
  // top of the document; when it scrolls out of view, the nav is "scrolled".
  if (typeof IntersectionObserver === 'undefined' || !sentinelRef.value) {
    return
  }
  scrollObserver = new IntersectionObserver(
    (entries) => {
      const entry = entries[0]
      if (entry) {
        // Sentinel is at document y=0; while it is in viewport the user has
        // not scrolled. Once it leaves the viewport top, switch to the
        // blurred state.
        scrolled.value = !entry.isIntersecting
      }
    },
    { threshold: 0, rootMargin: '0px 0px 0px 0px' },
  )
  scrollObserver.observe(sentinelRef.value)
})

onBeforeUnmount(() => {
  scrollObserver?.disconnect()
  scrollObserver = null
})

function goLogin(): void {
  void router.push('/login')
}

function scrollToSection(e: MouseEvent, href: string): void {
  e.preventDefault()
  const id = href.replace('#', '')
  const target = document.getElementById(id)
  if (target) {
    target.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }
}
</script>

<template>
  <div
    ref="sentinelRef"
    class="landing-nav__sentinel"
    aria-hidden="true"
    data-testid="landing-nav-sentinel"
  />
  <header class="landing-nav" :class="{ 'is-scrolled': scrolled }" data-testid="landing-nav">
    <div class="landing-nav__inner">
      <a class="landing-nav__brand" href="#top" aria-label="返回顶部">
        <span class="landing-nav__mark" aria-hidden="true">KG</span>
        <span class="landing-nav__text">
          <span class="landing-nav__title">麒麟安全智能运维 Agent</span>
          <span class="landing-nav__codename">KylinOps Guard</span>
        </span>
      </a>

      <nav class="landing-nav__links" aria-label="主导航">
        <a
          v-for="link in navLinks"
          :key="link.href"
          :href="link.href"
          class="landing-nav__link"
          @click="scrollToSection($event, link.href)"
        >
          {{ link.label }}
        </a>
      </nav>

      <el-button
        type="primary"
        round
        class="landing-nav__cta"
        data-testid="landing-nav-cta"
        @click="goLogin"
      >
        进入控制台
      </el-button>
    </div>
  </header>
</template>

<style scoped>
.landing-nav__sentinel {
  /* 1px sentinel anchored at document y=0. IntersectionObserver watches
     whether this point is still inside the viewport. When the user scrolls,
     it leaves the viewport, which is how LandingNav decides to switch into
     the blurred state without listening to the scroll event (design-taste-
     frontend §5.D: avoid scroll listeners for mobile jank). */
  position: absolute;
  top: 0;
  left: 0;
  width: 1px;
  height: 1px;
  pointer-events: none;
  visibility: hidden;
}

.landing-nav {
  position: sticky;
  top: 0;
  z-index: var(--kg-z-header);
  height: 64px;
  background-color: transparent;
  border-bottom: 1px solid transparent;
  transition: background-color var(--kg-transition-base), border-color var(--kg-transition-base),
    backdrop-filter var(--kg-transition-base);
}

.landing-nav.is-scrolled {
  background-color: var(--kg-glass-bg);
  border-bottom-color: var(--kg-color-border);
  backdrop-filter: blur(8px) saturate(140%);
  -webkit-backdrop-filter: blur(8px) saturate(140%);
}

@media (prefers-reduced-transparency: reduce) {
  .landing-nav.is-scrolled {
    background-color: var(--kg-color-surface);
    backdrop-filter: none;
    -webkit-backdrop-filter: none;
  }
}

.landing-nav__inner {
  max-width: 1200px;
  height: 100%;
  margin-inline: auto;
  padding-inline: var(--kg-space-6);
  display: flex;
  align-items: center;
  gap: var(--kg-space-7);
}

@media (max-width: 768px) {
  .landing-nav__inner {
    padding-inline: var(--kg-space-5);
    gap: var(--kg-space-4);
  }
}

.landing-nav__brand {
  display: inline-flex;
  align-items: center;
  gap: var(--kg-space-3);
  text-decoration: none;
  color: inherit;
  flex-shrink: 0;
}

.landing-nav__mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: var(--kg-radius-sm);
  background: linear-gradient(135deg, var(--kg-color-primary-soft), var(--kg-color-accent-soft));
  color: var(--kg-color-accent-hover);
  font-family: var(--kg-font-mono);
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0.05em;
}

.landing-nav__text {
  display: inline-flex;
  flex-direction: column;
  gap: 1px;
}

.landing-nav__title {
  font-size: var(--kg-text-md);
  font-weight: 600;
  color: var(--kg-color-text-primary);
  line-height: 1.2;
}

.landing-nav__codename {
  font-family: var(--kg-font-mono);
  font-size: 11px;
  color: var(--kg-color-text-mute);
  letter-spacing: 0.04em;
  line-height: 1.2;
}

.landing-nav__links {
  display: flex;
  align-items: center;
  gap: var(--kg-space-6);
  margin-left: auto;
}

@media (max-width: 768px) {
  .landing-nav__links {
    display: none;
  }
}

.landing-nav__link {
  color: var(--kg-color-text-secondary);
  font-size: var(--kg-text-sm);
  text-decoration: none;
  padding: 6px 0;
  border-bottom: 1px solid transparent;
  transition: color var(--kg-transition-fast), border-color var(--kg-transition-fast);
}

.landing-nav__link:hover {
  color: var(--kg-color-text-primary);
  border-bottom-color: var(--kg-color-accent);
}

.landing-nav__cta {
  flex-shrink: 0;
}
</style>
