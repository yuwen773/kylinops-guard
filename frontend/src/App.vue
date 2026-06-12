<script setup lang="ts">
// Minimal baseline shell — top banner only.
// Real six-page layout (ChatConsole / Dashboard / ToolCenter /
// SecurityCenter / AuditLog / ReportCenter) is introduced by Task 02 / 13–17.
</script>

<template>
  <el-container class="app-shell">
    <el-header class="app-header">
      <span class="app-title">麒麟安全智能运维 Agent</span>
    </el-header>
    <el-main class="app-main">
      <el-card class="baseline-card" shadow="never">
        <template #header>
          <span>前端工程基线已就绪</span>
        </template>
        <p>KylinOps Guard 前端工程初始化完成。</p>
        <p class="muted">业务页面将在后续 Phase 2 任务中实现。</p>
      </el-card>
    </el-main>
  </el-container>
</template>

<style scoped>
.app-shell {
  min-height: 100vh;
}
.app-header {
  display: flex;
  align-items: center;
  background: #1f2d3d;
  color: #fff;
}
.app-title {
  font-size: 1.1rem;
  font-weight: 600;
}
.app-main {
  background: #f5f7fa;
}
.baseline-card {
  max-width: 640px;
  margin: 0 auto;
}
.muted {
  color: #909399;
}
</style>
