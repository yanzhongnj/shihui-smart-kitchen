<template>
  <div class="dashboard">
    <div class="stats-grid">
      <div class="stat-card">
        <div class="stat-number">{{ stats.total }}</div>
        <div class="stat-label">总视频数</div>
      </div>
      <div class="stat-card">
        <div class="stat-number">{{ stats.completed }}</div>
        <div class="stat-label">已完成</div>
      </div>
      <div class="stat-card">
        <div class="stat-number">{{ stats.processing }}</div>
        <div class="stat-label">处理中</div>
      </div>
      <div class="stat-card">
        <div class="stat-number">{{ stats.pending }}</div>
        <div class="stat-label">待处理</div>
      </div>
    </div>

    <div class="quick-actions">
      <router-link to="/app/videos/upload" class="action-btn">
        📤 上传新视频
      </router-link>
      <router-link to="/app/videos" class="action-btn secondary">
        🎬 查看视频列表
      </router-link>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { videoAPI } from '@/api/video'

const authStore = useAuthStore()
const stats = ref({ total: 0, completed: 0, processing: 0, pending: 0 })

onMounted(async () => {
  try {
    // 统计走专用接口，避免分页(返回 {items,total})导致数据不准
    const res = await videoAPI.getStats()
    const d = res.data || {}
    stats.value.total = d.total || 0
    stats.value.completed = d.completed || 0
    stats.value.processing = d.processing || 0
    stats.value.pending = d.pending || 0
  } catch {}
})
</script>

<style scoped>
.dashboard { padding: 24px; }
.dashboard h2 { font-size: 24px; margin-bottom: 24px; }
.stats-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 16px; margin-bottom: 32px; }
.stat-card {
  background: #fff;
  padding: 24px;
  border-radius: var(--radius);
  box-shadow: var(--shadow);
  text-align: center;
}
.stat-number { font-size: 36px; font-weight: 700; color: var(--primary); }
.stat-label { font-size: 14px; color: #666; margin-top: 8px; }
.quick-actions { display: flex; gap: 16px; }
.action-btn {
  display: inline-flex;
  align-items: center;
  padding: 16px 24px;
  background: var(--primary);
  color: #fff;
  border-radius: var(--radius);
  text-decoration: none;
  font-size: 16px;
  gap: 8px;
}
.action-btn.secondary { background: #3498db; }
.action-btn:hover { opacity: 0.9; }
</style>
