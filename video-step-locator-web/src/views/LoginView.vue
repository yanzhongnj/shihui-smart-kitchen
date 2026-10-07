<template>
  <div class="auth-page">
    <div class="auth-card">
      <h1 class="auth-title">🍳 拾味管理平台</h1>
      <p class="auth-subtitle">登录您的账号</p>
      <form @submit.prevent="handleLogin" class="auth-form">
        <div class="form-group">
          <label>用户名</label>
          <input v-model="username" type="text" placeholder="请输入用户名" required />
        </div>
        <div class="form-group">
          <label>密码</label>
          <input v-model="password" type="password" placeholder="请输入密码" required />
        </div>
        <p v-if="error" class="error-msg">{{ error }}</p>
        <button type="submit" class="btn-primary" :disabled="loading">
          {{ loading ? '登录中...' : '登录' }}
        </button>
      </form>
      <p class="auth-link">
        没有账号？<router-link to="/register">立即注册</router-link>
      </p>
      <div class="auth-download">
        <AppDownloadQr />
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import AppDownloadQr from '@/components/AppDownloadQr.vue'

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()

const username = ref('')
const password = ref('')
const error = ref('')
const loading = ref(false)

async function handleLogin() {
  error.value = ''
  loading.value = true
  try {
    await authStore.login(username.value, password.value)
    const redirect = route.query.redirect ? String(route.query.redirect) : ''
    if (redirect && redirect.startsWith('/')) {
      // 管理端目标但非管理员 → 回落角色主页
      if (redirect.startsWith('/admin') && !authStore.isAdmin) {
        router.replace(authStore.homePath)
      } else {
        router.replace(redirect)
      }
    } else {
      router.replace(authStore.homePath)
    }
  } catch (e) {
    error.value = e?.message || '登录失败，请检查用户名和密码'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.auth-page {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  padding: 24px 16px;
  background: linear-gradient(135deg, #667eea 0%, #e74c3c 100%);
}
.auth-card {
  background: #fff;
  padding: 32px 28px;
  border-radius: 16px;
  box-shadow: 0 20px 60px rgba(0,0,0,0.15);
  width: 100%;
  max-width: 400px;
}
.auth-title { text-align: center; font-size: 24px; margin-bottom: 8px; }
.auth-subtitle { text-align: center; color: #666; margin-bottom: 24px; }
.form-group { margin-bottom: 16px; }
.form-group label { display: block; margin-bottom: 6px; font-weight: 500; font-size: 14px; }
.form-group input {
  width: 100%;
  padding: 10px 14px;
  border: 1px solid #ddd;
  border-radius: 8px;
  font-size: 14px;
  outline: none;
  transition: border-color 0.2s;
}
.form-group input:focus { border-color: var(--primary); }
.error-msg { color: #e74c3c; font-size: 13px; margin-bottom: 12px; }
.btn-primary {
  width: 100%;
  padding: 12px;
  background: var(--primary);
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 16px;
  cursor: pointer;
  transition: background 0.2s;
}
.btn-primary:hover:not(:disabled) { background: var(--primary-hover); }
.btn-primary:disabled { opacity: 0.6; cursor: not-allowed; }
.auth-link { text-align: center; margin-top: 16px; font-size: 14px; color: #666; }
.auth-link a { color: var(--primary); text-decoration: none; }
.auth-download { margin-top: 18px; padding-top: 16px; border-top: 1px dashed #eee; }
</style>
