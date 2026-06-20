<script setup lang="ts">
/**
 * LandingHero - landing page 顶部 hero 区。
 *
 * 设计规范 (P1-03):
 *   - Asymmetric split:左文 (5/9) + 右 ConsolePreview (4/9)
 *   - Hero stack 最多 4 text 元素:
 *       1. eyebrow (LandingEyebrow)
 *       2. H1 (≤ 2 行)
 *       3. subtext (≤ 22 字)
 *       4. 双 CTA (主 + 次)
 *   - 无 trust micro-strip / pricing teaser / bullet list
 *   - 移动端 (< 1024px) 切单列,文案在上,ConsolePreview 在下
 *
 * 字体 (clamp 适配):
 *   - H1: clamp(36px, 5vw, 56px)
 *   - subtext: 17px / line-height 1.6
 *
 * Motion (克制):
 *   - 入场 fade-up 320ms cubic-bezier(0.16, 1, 0.3, 1)
 *   - reduced-motion 下退化
 */
import { useRouter } from 'vue-router'
import { ArrowRight, VideoPlay } from '@element-plus/icons-vue'
import LandingEyebrow from '../components/LandingEyebrow.vue'
import LandingContainer from '../components/LandingContainer.vue'
import ConsolePreview from '../components/ConsolePreview.vue'

const router = useRouter()

function goLogin(): void {
  void router.push('/login')
}

function goScenarios(): void {
  const target = document.getElementById('scenarios')
  if (target) {
    target.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }
}
</script>

<template>
  <section id="top" class="landing-hero" data-testid="landing-hero">
    <LandingContainer>
      <div class="landing-hero__grid">
        <div class="landing-hero__copy">
          <LandingEyebrow text="Kylin Ops Guard · v0.1" />
          <h1 class="landing-hero__h1">
            让运维 Agent<br />在风险护栏内执行系统操作
          </h1>
          <p class="landing-hero__sub">
            自然语言指令 · MCP 工具规划 · 风险预检 · 安全执行 · 全量审计
          </p>
          <div class="landing-hero__cta">
            <el-button
              type="primary"
              size="large"
              round
              class="landing-hero__cta-primary"
              data-testid="hero-cta-primary"
              @click="goLogin"
            >
              进入控制台
              <el-icon class="el-icon--right"><ArrowRight /></el-icon>
            </el-button>
            <el-button
              size="large"
              round
              class="landing-hero__cta-secondary"
              data-testid="hero-cta-secondary"
              @click="goScenarios"
            >
              <el-icon class="el-icon--left"><VideoPlay /></el-icon>
              查看 6:30 演示脚本
            </el-button>
          </div>
        </div>

        <div class="landing-hero__visual">
          <ConsolePreview />
        </div>
      </div>
    </LandingContainer>
  </section>
</template>

<style scoped>
.landing-hero {
  padding: 96px 0 96px;
  background: radial-gradient(
      ellipse 1200px 600px at 70% 0%,
      var(--kg-color-primary-soft) 0%,
      transparent 60%
    ),
    var(--kg-color-bg);
}

@media (max-width: 1024px) {
  .landing-hero {
    padding: 72px 0 80px;
  }
}

.landing-hero__grid {
  display: grid;
  grid-template-columns: 1.25fr 1fr;
  gap: 64px;
  align-items: center;
}

@media (max-width: 1024px) {
  .landing-hero__grid {
    grid-template-columns: 1fr;
    gap: 48px;
  }
}

.landing-hero__copy {
  animation: landing-hero-fade-up 320ms cubic-bezier(0.16, 1, 0.3, 1) both;
}

.landing-hero__h1 {
  font-size: clamp(30px, 4vw, 44px);
  font-weight: 600;
  line-height: 1.2;
  letter-spacing: -0.01em;
  margin: var(--kg-space-4) 0 var(--kg-space-5);
  color: var(--kg-color-text-primary);
  max-width: 22ch;
}

.landing-hero__sub {
  font-size: 17px;
  line-height: 1.6;
  color: var(--kg-color-text-secondary);
  max-width: 38ch;
  margin: 0 0 var(--kg-space-7);
}

.landing-hero__cta {
  display: flex;
  gap: var(--kg-space-3);
  flex-wrap: wrap;
}

@media (max-width: 768px) {
  .landing-hero__cta {
    flex-direction: column;
    align-items: stretch;
  }
  .landing-hero__cta :deep(.el-button) {
    width: 100%;
  }
}

@keyframes landing-hero-fade-up {
  from {
    opacity: 0;
    transform: translateY(24px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@media (prefers-reduced-motion: reduce) {
  .landing-hero__copy {
    animation: none;
  }
}
</style>
