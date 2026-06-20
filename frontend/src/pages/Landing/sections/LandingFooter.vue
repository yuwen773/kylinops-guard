<script setup lang="ts">
/**
 * LandingFooter - 落地页底部 footer。
 *
 * 设计规范 (P1-03):
 *   - 3 列布局:品牌 + 链接 + 平台支持
 *   - 底部 mono 版权行
 *   - 无 logo wall (项目暂无公开客户)
 *   - 不放社交分享链接 (B2B 安全产品不适合营销扩散)
 */
import LandingContainer from '../components/LandingContainer.vue'

const productName = '麒麟安全智能运维 Agent'
const codename = 'KylinOps Guard'
const year = new Date().getFullYear()

const docLinks = [
  { label: '6:30 演示脚本', href: '#scenarios' },
  { label: '风险规则 YML', href: '#capabilities' },
  { label: '工具矩阵', href: '#tools' },
] as const
</script>

<template>
  <footer class="landing-footer" data-testid="landing-footer">
    <LandingContainer>
      <div class="landing-footer__grid">
        <div class="landing-footer__brand">
          <span class="landing-footer__mark" aria-hidden="true">KG</span>
          <div class="landing-footer__brand-text">
            <span class="landing-footer__title">{{ productName }}</span>
            <span class="landing-footer__codename">{{ codename }}</span>
            <span class="landing-footer__version">v0.1 · 2026</span>
          </div>
        </div>

        <nav class="landing-footer__col" aria-label="文档">
          <h4 class="landing-footer__col-title">文档</h4>
          <ul>
            <li v-for="link in docLinks" :key="link.href">
              <a :href="link.href" class="landing-footer__link">{{ link.label }}</a>
            </li>
          </ul>
        </nav>

        <div id="platform" class="landing-footer__col">
          <h4 class="landing-footer__col-title">平台支持</h4>
          <ul class="landing-footer__mono-list">
            <li>Kylin Advanced Server V11</li>
            <li>LoongArch64 · JDK 17</li>
            <li>Spring Boot 3.x + H2 / PostgreSQL</li>
            <li>Vue 3 + Element Plus</li>
          </ul>
        </div>
      </div>

      <div class="landing-footer__bottom">
        <span class="landing-footer__copyright">
          © {{ year }} 麒麟安全智能运维 Agent · KylinOps Guard
        </span>
        <span class="landing-footer__platform">
          适配 Kylin Advanced Server V11 · LoongArch64
        </span>
      </div>
    </LandingContainer>
  </footer>
</template>

<style scoped>
.landing-footer {
  padding: 64px 0 32px;
  border-top: 1px solid var(--kg-color-border);
  background-color: var(--kg-color-surface);
}

.landing-footer__grid {
  display: grid;
  grid-template-columns: 2fr 1fr 1.5fr;
  gap: var(--kg-space-8);
  margin-bottom: var(--kg-space-7);
}

@media (max-width: 768px) {
  .landing-footer__grid {
    grid-template-columns: 1fr;
    gap: var(--kg-space-6);
  }
}

.landing-footer__brand {
  display: flex;
  align-items: flex-start;
  gap: var(--kg-space-3);
}

.landing-footer__mark {
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
  flex-shrink: 0;
}

.landing-footer__brand-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.landing-footer__title {
  font-size: var(--kg-text-base);
  font-weight: 600;
  color: var(--kg-color-text-primary);
}

.landing-footer__codename {
  font-family: var(--kg-font-mono);
  font-size: var(--kg-text-xs);
  color: var(--kg-color-text-secondary);
  letter-spacing: 0.04em;
}

.landing-footer__version {
  font-family: var(--kg-font-mono);
  font-size: 11px;
  color: var(--kg-color-text-mute);
}

.landing-footer__col-title {
  font-family: var(--kg-font-mono);
  font-size: 11px;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: var(--kg-color-text-mute);
  margin: 0 0 var(--kg-space-4);
  font-weight: 500;
}

.landing-footer__col ul {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--kg-space-2);
}

.landing-footer__link {
  font-size: var(--kg-text-sm);
  color: var(--kg-color-text-secondary);
  text-decoration: none;
  transition: color var(--kg-transition-fast);
}

.landing-footer__link:hover {
  color: var(--kg-color-text-primary);
}

.landing-footer__mono-list li {
  font-family: var(--kg-font-mono);
  font-size: var(--kg-text-xs);
  color: var(--kg-color-text-secondary);
  line-height: 1.8;
}

.landing-footer__bottom {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-top: var(--kg-space-5);
  border-top: 1px solid var(--kg-color-border-mute);
  flex-wrap: wrap;
  gap: var(--kg-space-3);
}

.landing-footer__copyright,
.landing-footer__platform {
  font-family: var(--kg-font-mono);
  font-size: 11px;
  color: var(--kg-color-text-mute);
  letter-spacing: 0.02em;
}
</style>
