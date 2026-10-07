<template>
  <router-view />
</template>

<script setup>
import { onMounted } from 'vue'
import { useAuthStore } from '@/stores/auth'

const authStore = useAuthStore()

onMounted(() => {
  authStore.restoreSession()
})
</script>

<style>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

:root {
  --primary: #e74c3c;
  --primary-hover: #c0392b;
  --sidebar-width: 220px;
  --header-height: 56px;
  --bg-color: #f5f5f5;
  --card-bg: #ffffff;
  --radius: 8px;
  --shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
}

body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
  background: var(--bg-color);
  color: #333;
}

#app-root {
  display: flex;
  min-height: 100vh;
  width: 100%;
  max-width: 100%;
  min-width: 0;
  overflow-x: hidden;
}

.main-area {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

/* 正常页面布局：内容随内容自然增长，页面可滚动，不受视口大小限制 */
.page-content {
  flex: 1;
  min-width: 0;
}
</style>
