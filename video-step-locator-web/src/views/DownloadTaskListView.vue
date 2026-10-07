<template>
  <div class="dl-page">
    <div class="page-actions">
      <router-link to="/app/videos/download" class="btn-ghost">🔗 去下载</router-link>
    </div>

    <div v-if="loading" class="loading">加载中...</div>
    <div v-else-if="tasks.length === 0" class="empty"><p>暂无下载任务</p></div>

    <el-table v-loading="loading" :data="tasks" border stripe style="width: 100%"
              empty-text="暂无下载任务">
      <el-table-column label="标题" min-width="240">
        <template #default="{ row }">
          <span class="dl-title" @click="goVideo(row)">{{ row.title || '(未命名)' }}</span>
          <div v-if="row.status === 'failed' && row.errorMessage" class="dl-error">❌ {{ row.errorMessage }}</div>
        </template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusTagType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="进度" width="140">
        <template #default="{ row }">
          <div class="progress-bar"><div class="progress-fill" :style="{ width: (row.progress || 0) + '%' }"></div></div>
          <span class="progress-text">{{ row.progress || 0 }}%</span>
        </template>
      </el-table-column>
      <el-table-column label="清晰度" width="90">
        <template #default="{ row }">{{ formatText(row.formatRequested) }}</template>
      </el-table-column>
      <el-table-column label="大小" width="100">
        <template #default="{ row }">{{ formatSize(row.fileSize) }}</template>
      </el-table-column>
      <el-table-column label="时间" width="175">
        <template #default="{ row }">{{ formatDate(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="170" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.status === 'completed' && row.videoId"
                     link type="primary" size="small" @click="goVideo(row)">查看</el-button>
          <el-button v-if="row.status === 'failed' || row.status === 'cancelled'"
                     link type="primary" size="small" @click="retry(row)">重试</el-button>
          <el-button v-if="row.status === 'running' || row.status === 'queued'"
                     link type="danger" size="small" @click="cancel(row)">停止</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination v-if="totalPages > 1" background layout="total, sizes, prev, pager, next"
      :total="total" :current-page="currentPage + 1" :page-size="pageSize"
      :page-sizes="[10, 20, 50]"
      @current-change="onPageChange" @size-change="onSizeChange" class="ep-pagination" />
    <div v-else-if="!loading && tasks.length > 0" class="page-info total-only">共 {{ total }} 条下载记录</div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { downloadAPI } from '@/api/download'

const router = useRouter()
const tasks = ref([])
const loading = ref(false)
const currentPage = ref(0)
const pageSize = ref(10)
const total = ref(0)
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))
let pollTimer = null
const POLL_INTERVAL = 5000

const hasActive = () => tasks.value.some(t => t.status === 'running' || t.status === 'queued')

onMounted(async () => { await load(true); syncPolling() })
onUnmounted(() => stopPolling())

// 后台轮询静默刷新，避免抖动；无活跃任务即停止
async function load(initial = false) {
  if (initial) loading.value = true
  try {
    const res = await downloadAPI.list({ page: currentPage.value, size: pageSize.value })
    if (res.code === 200) {
      tasks.value = res.data?.items || []
      total.value = res.data?.total || 0
    }
  } catch {}
  finally { if (initial) loading.value = false }
}

function onPageChange(p) { currentPage.value = p - 1; load() }
function onSizeChange(s) { pageSize.value = s; currentPage.value = 0; load() }
function startPolling() {
  stopPolling()
  pollTimer = setInterval(async () => {
    await load()
    if (!hasActive()) stopPolling()
  }, POLL_INTERVAL)
}
function stopPolling() {
  if (pollTimer) { clearInterval(pollTimer); pollTimer = null }
}
function syncPolling() {
  if (hasActive()) startPolling()
  else stopPolling()
}

async function retry(t) {
  try {
    const res = await downloadAPI.retry(t.id)
    if (res.code !== 200) { alert(res.message || '重试失败'); return }
    t.status = 'queued'
    t.progress = 0
    t.errorMessage = ''
    syncPolling()
  } catch (e) { alert(e?.message || '重试失败') }
}
async function cancel(t) {
  if (!confirm('确定停止该下载任务？')) return
  try {
    const res = await downloadAPI.cancel(t.id)
    if (res.code !== 200) { alert(res.message || '取消失败'); return }
    t.status = 'cancelled'
    syncPolling()
  } catch (e) { alert(e?.message || '取消失败') }
}
function goVideo(t) { if (t.videoId) router.push(`/app/videos/${t.videoId}`) }

function statusText(s) {
  const map = { queued: '排队中', running: '下载中', completed: '已完成', failed: '失败', cancelled: '已取消' }
  return map[s] || s
}
function statusTagType(s) {
  const map = { queued: 'info', running: 'warning', completed: 'success', failed: 'danger', cancelled: 'info' }
  return map[s] || 'info'
}
function formatText(k) {
  const map = { best: '最佳', '1080p': '1080P', '720p': '720P', '480p': '480P' }
  return map[k] || k || '-'
}
function formatSize(b) {
  return b ? (b > 1048576 ? (b / 1048576).toFixed(1) + 'MB' : (b / 1024).toFixed(1) + 'KB') : '-'
}
function formatDate(d) { return d ? new Date(d).toLocaleString('zh-CN') : '-' }
</script>

<style scoped>
.dl-page { padding: 24px; }
.page-actions { display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px; }
.page-actions h2 { margin: 0; }
.btn-ghost {
  padding: 8px 16px; border: 1px solid #ddd; background: #fff;
  border-radius: 6px; font-size: 13px; cursor: pointer; color: var(--primary);
  text-decoration: none;
}
.btn-ghost:hover { border-color: var(--primary); }
.loading, .empty { text-align: center; padding: 60px; color: #999; }
.dl-table { background: #fff; border-radius: var(--radius); box-shadow: var(--shadow); overflow: hidden; }
.dl-row {
  display: grid; grid-template-columns: 2fr 0.8fr 1fr 0.7fr 0.8fr 1.2fr 1fr;
  padding: 12px 16px; align-items: center; border-bottom: 1px solid #f0f0f0; font-size: 13px; gap: 8px;
}
.dl-row.header { font-weight: 600; background: #f9f9f9; }
.dl-title { cursor: pointer; color: var(--primary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.status-badge { padding: 2px 10px; border-radius: 12px; font-size: 12px; text-align: center; width: fit-content; }
.status-badge.queued { background: #e2e3e5; color: #383d41; }
.status-badge.running { background: #fff3cd; color: #856404; }
.status-badge.completed { background: #d4edda; color: #155724; }
.status-badge.failed { background: #f8d7da; color: #721c24; }
.status-badge.cancelled { background: #e2e3e5; color: #555; }
.progress-bar { width: 70px; height: 6px; background: #eee; border-radius: 3px; display: inline-block; vertical-align: middle; margin-right: 6px; }
.progress-fill { height: 100%; background: var(--primary); border-radius: 3px; transition: width 0.3s; }
.progress-text { font-size: 12px; color: #666; }
.dl-time { color: #999; font-size: 12px; }
.dl-controls { display: flex; gap: 6px; }
.ctrl { padding: 3px 10px; border: 1px solid #ddd; border-radius: 4px; cursor: pointer; font-size: 12px; background: #fff; }
.ctrl.view { color: #3498db; }
.ctrl.retry { color: #27ae60; }
.ctrl.cancel { color: #e74c3c; }
.ctrl:hover { background: #f5f5f5; }
.dl-error { margin-top: 4px; color: #e74c3c; font-size: 12px; background: #fff8f8; word-break: break-all; }
.pagination { display: flex; align-items: center; justify-content: center; gap: 12px; margin-top: 20px; }
.ep-pagination { margin-top: 16px; justify-content: center; }
.page-btn { padding: 8px 16px; border: 1px solid #ddd; background: #fff; border-radius: 6px; cursor: pointer; font-size: 13px; }
.page-btn:hover:not(:disabled) { border-color: var(--primary); color: var(--primary); }
.page-btn:disabled { opacity: 0.4; cursor: not-allowed; }
.page-info { font-size: 13px; color: #555; }
.page-total { font-size: 12px; color: #999; }
.total-only { display: block; text-align: center; margin-top: 16px; color: #999; font-size: 13px; }
</style>
