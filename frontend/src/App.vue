<script setup lang="ts">
// AppLayout owns the chrome (top bar + sidebar + content area). The Login
// page (`/login`) is the one route that bypasses the chrome — it renders
// edge-to-edge with its own card so the user does not see the navigation
// before authenticating.
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import AppLayout from '@/layouts/AppLayout.vue';

const route = useRoute();
const hideLayout = computed(() => route.path === '/login' || route.meta?.public === true);
</script>

<template>
  <router-view v-if="hideLayout" />
  <AppLayout v-else />
</template>
