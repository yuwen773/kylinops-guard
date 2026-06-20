<script setup lang="ts">
/**
 * Landing - 公开落地页主入口。
 *
 * 路由:`/landing` (PUBLIC_ROUTES,meta.public=true)
 * 渲染:绕过 AppLayout,纯 marketing chrome
 *
 * 章节装配顺序 (9 段):
 *   1. LandingNav       (sticky top, 与 AppLayout chrome 不同)
 *   2. LandingHero      (asymmetric split)
 *   3. TrustStrip       (4 metrics)
 *   4. CapabilitiesBento (6 cells)
 *   5. ClosedLoopFlow   (10 steps)
 *   6. DemoScenarios    (4 cards)
 *   7. ToolMatrix       (10 rows)
 *   8. FinalCta         (glass banner)
 *   9. LandingFooter    (3 cols + mono bottom)
 *
 * 文档头说明:
 *   - 不调用任何 API
 *   - 不写 Vuex/Pinia
 *   - 全部数据来自组件内部 fixture
 *   - 单一主 CTA "进入控制台" 直跳 /login
 */
import LandingNav from './sections/LandingNav.vue'
import LandingHero from './sections/LandingHero.vue'
import TrustStrip from './sections/TrustStrip.vue'
import CapabilitiesBento from './sections/CapabilitiesBento.vue'
import ClosedLoopFlow from './sections/ClosedLoopFlow.vue'
import DemoScenarios from './sections/DemoScenarios.vue'
import ToolMatrix from './sections/ToolMatrix.vue'
import FinalCta from './sections/FinalCta.vue'
import LandingFooter from './sections/LandingFooter.vue'
</script>

<template>
  <div class="landing" data-testid="landing-page">
    <LandingNav />
    <main class="landing__main">
      <LandingHero />
      <TrustStrip />
      <CapabilitiesBento />
      <ClosedLoopFlow />
      <DemoScenarios />
      <ToolMatrix />
      <FinalCta />
    </main>
    <LandingFooter />
  </div>
</template>

<style scoped>
.landing {
  min-height: 100dvh;
  background-color: var(--kg-color-bg);
  color: var(--kg-color-text-primary);
}

.landing__main {
  display: flex;
  flex-direction: column;
}
</style>
