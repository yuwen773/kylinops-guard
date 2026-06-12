<script setup lang="ts">
// AppLayout owns the chrome (top bar + sidebar + content area). The router
// renders the active page into AppLayout's <router-view />.
import AppLayout from '@/layouts/AppLayout.vue';
</script>

<template>
  <AppLayout />
</template>
