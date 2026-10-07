<template>
  <div style="min-height:100vh;display:flex;align-items:center;justify-content:center;color:#999">
    跳转中…
  </div>
</template>

<script setup>
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const authStore = useAuthStore()

onMounted(() => {
  if (!authStore.isLoggedIn) {
    router.replace('/login')
  } else {
    router.replace(authStore.homePath)
  }
})
</script>
