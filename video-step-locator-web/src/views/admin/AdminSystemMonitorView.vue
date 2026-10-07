<template>
  <div class="admin-monitor">
    <div class="page-actions">
      <button class="btn-primary" @click="loadAll">🔄 刷新</button>
    </div>

    <div v-if="loading" class="loading">加载中...</div>
    <div v-else>
      <!-- 平台统计 -->
      <div class="stats-grid">
        <div class="stat-card">
          <div class="stat-number">{{ stats.totalUsers }}</div>
          <div class="stat-label">用户总数</div>
        </div>
        <div class="stat-card">
          <div class="stat-number">{{ stats.totalVideos }}</div>
          <div class="stat-label">视频总数</div>
        </div>
        <div class="stat-card">
          <div class="stat-number">{{ stats.totalTasks }}</div>
          <div class="stat-label">智能处理任务</div>
        </div>
        <div class="stat-card">
          <div class="stat-number">{{ stats.totalDownloads }}</div>
          <div class="stat-label">下载任务</div>
        </div>
        <div class="stat-card">
          <div class="stat-number">{{ stats.totalRecipes }}</div>
          <div class="stat-label">菜谱数</div>
        </div>
        <div class="stat-card warn">
          <div class="stat-number">{{ activeTasks }}</div>
          <div class="stat-label">活跃任务（排队+运行）</div>
        </div>
      </div>

      <!-- 系统健康 -->
      <div class="health-section">
        <h3>系统健康</h3>
        <div class="health-grid">
          <div class="health-item">
            <span class="health-label">数据库</span>
            <span :class="['health-value', health.database === 'UP' ? 'ok' : 'down']">{{ health.database }}</span>
          </div>
          <div class="health-item">
            <span class="health-label">FunASR 语音引擎</span>
            <span :class="['health-value', health.funasr === 'UP' ? 'ok' : 'down']">{{ health.funasr }}</span>
          </div>
          <div class="health-item">
            <span class="health-label">存储目录</span>
            <span :class="['health-value', health.storage?.writable ? 'ok' : 'down']">
              {{ health.storage?.writable ? '可写' : '不可写' }}
            </span>
          </div>
          <div class="health-item">
            <span class="health-label">活跃任务</span>
            <span class="health-value">{{ health.activeTasks ?? '-' }}</span>
          </div>
          <div class="health-item">
            <span class="health-label">服务器时间</span>
            <span class="health-value">{{ health.serverTime || '-' }}</span>
          </div>
          <div class="health-item wide">
            <span class="health-label">存储路径</span>
            <span class="health-value mono">{{ health.storage?.path || health.storage?.error || '-' }}</span>
          </div>
        </div>
      </div>

      <!-- 存储占用 -->
      <div class="storage-section">
        <h3>存储占用</h3>
        <div class="storage-cards">
          <div class="storage-card">
            <div class="storage-num">{{ formatGb(stats.storage?.usedGb) }}</div>
            <div class="storage-label">已用（视频目录）</div>
          </div>
          <div class="storage-card">
            <div class="storage-num">{{ formatGb(stats.storage?.freeGb) }}</div>
            <div class="storage-label">剩余空间</div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { adminAPI } from '@/api/admin'

const stats = ref({})
const health = ref({})
const loading = ref(false)
let timer = null

const activeTasks = computed(() => (stats.value.queuedTasks || 0) + (stats.value.runningTasks || 0))

onMounted(() => {
  loadAll()
  timer = setInterval(loadAll, 10000)
})
onUnmounted(() => { if (timer) clearInterval(timer) })

async function loadAll() {
  try {
    const [s, h] = await Promise.all([adminAPI.getStats(), adminAPI.getHealth()])
    stats.value = s.data || {}
    health.value = h.data || {}
  } catch (e) {
    // 静默，避免频繁弹窗
  }
}

function formatGb(v) {
  if (v == null || v === 0) return '0 GB'
  return `${v} GB`
}
</script>

<style scoped>
.admin-monitor { padding: 24px; }
.admin-monitor h2 { font-size: 24px; margin-bottom: 24px; }
.page-actions { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; }
.btn-primary { padding: 10px 18px; background: var(--primary, #409eff); color: #fff; border: none; border-radius: 6px; cursor: pointer; }
.loading { padding: 40px; text-align: center; color: var(--text-secondary); }
.stats-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; margin-bottom: 24px; }
@media (min-width: 900px) { .stats-grid { grid-template-columns: repeat(6, 1fr); } }
.stat-card { background: #fff; padding: 20px; border-radius: 8px; box-shadow: 0 1px 4px rgba(0,0,0,0.08); text-align: center; }
.stat-number { font-size: 30px; font-weight: 700; color: var(--primary, #409eff); }
.stat-card.warn .stat-number { color: var(--warning-color, #e6a23c); }
.stat-label { font-size: 13px; color: var(--text-secondary); margin-top: 6px; }
.health-section, .storage-section { background: #fff; border-radius: 8px; box-shadow: 0 1px 4px rgba(0,0,0,0.08); padding: 20px; margin-bottom: 24px; }
.health-section h3, .storage-section h3 { margin: 0 0 16px; font-size: 16px; }
.health-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 16px; }
.health-item { display: flex; flex-direction: column; gap: 6px; }
.health-item.wide { grid-column: 1 / -1; }
.health-label { font-size: 12px; color: var(--text-secondary); }
.health-value { font-size: 14px; font-weight: 500; }
.health-value.ok { color: var(--success-color, #67c23a); }
.health-value.down { color: var(--danger-color, #f56c6c); }
.health-value.mono { font-family: Consolas, monospace; font-size: 12px; word-break: break-all; }
.storage-cards { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 16px; }
.storage-card { background: #fafafa; border-radius: 8px; padding: 16px; text-align: center; }
.storage-num { font-size: 26px; font-weight: 700; color: var(--text-primary); }
.storage-label { font-size: 13px; color: var(--text-secondary); margin-top: 6px; }
</style>
