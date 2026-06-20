<script setup lang="ts">
/**
 * FinalCta - 落地页底部 CTA banner。
 *
 * 设计规范 (P1-03):
 *   - Full-width glass banner:backdrop-filter blur(8px) + 1px 边线
 *   - 单一主意图 "进入控制台" (全站唯一 primary)
 *   - secondary "查看 6:30 演示脚本"
 *   - prefers-reduced-transparency fallback:不 blur,用纯色
 */
import { useRouter } from 'vue-router'
import { ArrowRight, VideoPlay } from '@element-plus/icons-vue'
import LandingContainer from '../components/LandingContainer.vue'

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
  <section id="cta" class="cta" data-testid="landing-cta">
    <LandingContainer>
      <div class="cta__card">
        <div class="cta__content">
          <h2 class="cta__title">现在就开始把运维交给带护栏的 Agent</h2>
          <p class="cta__sub">
            6:30 演示脚本涵盖健康检查 / 磁盘诊断 / 服务重启 / 危险命令拦截 4 个场景,均可一键复现。
          </p>
        </div>
        <div class="cta__actions">
          <el-button
            type="primary"
            size="large"
            round
            data-testid="cta-primary"
            @click="goLogin"
          >
            进入控制台
            <el-icon class="el-icon--right"><ArrowRight /></el-icon>
          </el-button>
          <el-button
            size="large"
            round
            data-testid="cta-secondary"
            @click="goScenarios"
          >
            <el-icon class="el-icon--left"><VideoPlay /></el-icon>
            查看演示脚本
          </el-button>
        </div>
      </div>
    </LandingContainer>
  </section>
</template>

<style scoped>
.cta {
  padding: 96px 0;
}

@media (max-width: 768px) {
  .cta {
    padding: 64px 0;
  }
}

.cta__card {
  position: relative;
  display: grid;
  grid-template-columns: 1.4fr 1fr;
  gap: var(--kg-space-7);
  align-items: center;
  padding: var(--kg-space-8);
  border: 1px solid var(--kg-color-border-strong);
  border-radius: var(--kg-radius-lg);
  background-color: var(--kg-glass-bg);
  backdrop-filter: blur(8px) saturate(140%);
  -webkit-backdrop-filter: blur(8px) saturate(140%);
  box-shadow: var(--kg-shadow-elevated);
}

@media (max-width: 768px) {
  .cta__card {
    grid-template-columns: 1fr;
    padding: var(--kg-space-6);
    gap: var(--kg-space-5);
  }
}

@media (prefers-reduced-transparency: reduce) {
  .cta__card {
    background-color: var(--kg-color-surface);
    backdrop-filter: none;
    -webkit-backdrop-filter: none;
  }
}

.cta__title {
  font-size: clamp(24px, 3vw, 32px);
  font-weight: 600;
  line-height: 1.2;
  letter-spacing: -0.01em;
  margin: 0 0 var(--kg-space-3);
  color: var(--kg-color-text-primary);
}

.cta__sub {
  font-size: var(--kg-text-md);
  line-height: 1.6;
  color: var(--kg-color-text-secondary);
  margin: 0;
  max-width: 50ch;
}

.cta__actions {
  display: flex;
  gap: var(--kg-space-3);
  flex-wrap: wrap;
  justify-content: flex-end;
}

@media (max-width: 768px) {
  .cta__actions {
    justify-content: stretch;
    flex-direction: column;
  }
  .cta__actions :deep(.el-button) {
    width: 100%;
  }
}
</style>
