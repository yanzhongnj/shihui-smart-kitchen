<template>
  <div class="admin-dashboard">
    <div v-if="loading" class="loading">加载中...</div>
    <div v-else class="stats-grid">
      <div class="stat-card">
        <div class="stat-number">{{ stats.totalUsers ?? 0 }}</div>
        <div class="stat-label">总用户数</div>
      </div>
      <div class="stat-card">
        <div class="stat-number">{{ stats.totalVideos ?? 0 }}</div>
        <div class="stat-label">总视频数</div>
      </div>
      <div class="stat-card">
        <div class="stat-number">{{ stats.totalRecipes ?? 0 }}</div>
        <div class="stat-label">总菜谱数</div>
      </div>
      <div class="stat-card">
        <div class="stat-number">{{ stats.totalTasks ?? 0 }}</div>
        <div class="stat-label">处理任务（排队 {{ stats.queuedTasks ?? 0 }} / 运行 {{ stats.runningTasks ?? 0 }}）</div>
      </div>
      <div class="stat-card">
        <div class="stat-number">{{ stats.totalDownloads ?? 0 }}</div>
        <div class="stat-label">下载任务</div>
      </div>
      <div class="stat-card" v-if="stats.storage">
        <div class="stat-number">{{ formatSize(stats.storage.usedBytes) }}</div>
        <div class="stat-label">存储用量</div>
      </div>
    </div>
    <div class="links">
      <router-link class="action-btn" to="/admin/users">👥 用户管理</router-link>
      <router-link class="action-btn secondary" to="/admin/videos">🎞️ 全局视频</router-link>
      <router-link class="action-btn secondary" to="/admin/content">📚 内容管理</router-link>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { adminAPI } from '@/api/admin'

const stats = ref({})
const loading = ref(true)

onMounted(async () => {
  try {
    const res = await adminAPI.getStats()
    stats.value = res.data || {}
  } catch (e) {
    stats.value = {}
  } finally {
    loading.value = false
  }
})

function formatSize(n) {
  if (!n) return '0 B'
  const u = ['B', 'KB', 'MB', 'GB', 'TB']
  let i = 0
  let v = n
  while (v >= 1024 && i < u.length - 1) { v /= 1024; i++ }
  return `${v.toFixed(1)} ${u[i]}`
}
</script>

<style scoped>
.admin-dashboard { padding: 24px; }
.loading { color: #999; padding: 24px; }
.stats-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; margin-bottom: 32px; }
.stat-card { background: #fff; padding: 24px; border-radius: var(--radius); box-shadow: var(--shadow); text-align: center; }
.stat-number { font-size: 32px; font-weight: 700; color: var(--primary); }
.stat-label { font-size: 14px; color: #666; margin-top: 8px; }
.links { display: flex; gap: 16px; }
.action-btn {
  display: inline-flex; align-items: center; padding: 10px 16px;
  background: var(--primary); color: #fff; border-radius: 8px;
  text-decoration: none; font-size: 14px;
}
.action-btn.secondary { background: #fff; color: var(--primary); border: 1px solid var(--primary); }
@media (max-width: 900px) { .stats-grid { grid-template-columns: repeat(2, 1fr); } }
</style>
