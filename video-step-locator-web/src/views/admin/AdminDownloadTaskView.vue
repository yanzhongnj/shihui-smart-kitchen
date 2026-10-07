<template>
  <div class="admin-downloads">
    <div class="filter-bar">
      <input v-model="keyword" class="filter-input" placeholder="搜索视频标题..." @keyup.enter="applyFilters" />
      <select v-model="statusFilter" class="filter-select" @change="applyFilters">
        <option value="">全部状态</option>
        <option value="queued">排队中</option>
        <option value="running">下载中</option>
        <option value="completed">已完成</option>
        <option value="failed">失败</option>
        <option value="cancelled">已取消</option>
      </select>
      <select v-model="sortBy" class="filter-select" @change="applyFilters">
        <option value="createdAt">按创建时间</option>
        <option value="title">按标题</option>
        <option value="fileSize">按大小</option>
      </select>
      <button class="sort-order-btn" @click="toggleOrder">{{ sortOrder === 'asc' ? '↑ 升序' : '↓ 降序' }}</button>
      <button v-if="keyword || statusFilter" class="btn-clear" @click="clearFilters">✕ 清除</button>
    </div>

    <el-table v-loading="loading" :data="tasks" border stripe style="width: 100%"
              @sort-change="onSortChange" empty-text="暂无下载任务">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="title" label="标题" min-width="220" sortable>
        <template #default="{ row }">
          {{ row.title || '-' }}
          <el-tag v-if="row.errorMessage" type="danger" size="small" style="margin-left:6px" :title="row.errorMessage">⚠</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="用户名称" min-width="120">
        <template #default="{ row }">{{ row.username || (row.userId ? `#${row.userId}` : '游客') }}</template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 'completed' ? 'success' : row.status === 'failed' ? 'danger' : row.status === 'running' ? 'primary' : row.status === 'queued' ? 'warning' : 'info'" size="small">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="进度" width="80">
        <template #default="{ row }">{{ row.progress ?? 0 }}%</template>
      </el-table-column>
      <el-table-column prop="fileSize" label="大小" width="110" sortable>
        <template #default="{ row }">{{ formatSize(row.fileSize) }}</template>
      </el-table-column>
      <el-table-column prop="createdAt" label="时间" width="165" sortable>
        <template #default="{ row }">{{ formatDate(row.createdAt) }}</template>
      </el-table-column>
    </el-table>

    <el-pagination v-if="totalPages > 1" background layout="total, sizes, prev, pager, next"
      :total="total" :current-page="currentPage + 1" :page-size="pageSize"
      :page-sizes="[10, 20, 50]"
      @current-change="onPageChange" @size-change="onSizeChange" class="ep-pagination" />
    <div v-else-if="!loading && tasks.length > 0" class="page-total center">共 {{ total }} 条</div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { adminAPI } from '@/api/admin'

const tasks = ref([])
const loading = ref(false)
const currentPage = ref(0)
const total = ref(0)
const totalPages = ref(0)
const pageSize = ref(10)

const keyword = ref('')
const statusFilter = ref('')
const sortBy = ref('createdAt')
const sortOrder = ref('desc')

let pollTimer = null
onMounted(() => { loadTasks(); startPolling() })
onUnmounted(stopPolling)

function startPolling() {
  stopPolling()
  pollTimer = setInterval(() => loadTasks(true), 5000)
}
function stopPolling() { if (pollTimer) { clearInterval(pollTimer); pollTimer = null } }

async function loadTasks(silent = false) {
  if (!silent) loading.value = true
  try {
    const res = await adminAPI.getDownloads({
      page: currentPage.value,
      size: pageSize.value,
      status: statusFilter.value || undefined,
      keyword: keyword.value || undefined,
      sortBy: sortBy.value,
      order: sortOrder.value,
    })
    const d = res.data || {}
    tasks.value = d.items || []
    total.value = d.total || 0
    totalPages.value = Math.max(1, Math.ceil(total.value / pageSize.value))
    const active = tasks.value.some(t => t.status === 'running' || t.status === 'queued')
    if (!active) stopPolling()
  } catch (e) {
    if (!silent) alert(e?.message || '加载下载任务失败')
  } finally {
    loading.value = false
  }
}

function applyFilters() { currentPage.value = 0; loadTasks() }
function clearFilters() { keyword.value = ''; statusFilter.value = ''; currentPage.value = 0; loadTasks() }
function toggleOrder() { sortOrder.value = sortOrder.value === 'asc' ? 'desc' : 'asc'; loadTasks() }
function onPageChange(p) { currentPage.value = p - 1; loadTasks() }
function onSizeChange(s) { pageSize.value = s; currentPage.value = 0; loadTasks() }
function onSortChange({ prop, order }) {
  if (!prop) return
  sortBy.value = prop
  sortOrder.value = order === 'ascending' ? 'asc' : 'desc'
  currentPage.value = 0
  loadTasks()
}

function statusText(s) {
  return { queued: '排队中', running: '下载中', completed: '已完成', failed: '失败', cancelled: '已取消' }[s] || s
}
function formatSize(v) {
  if (v == null) return '-'
  const b = Number(v)
  if (b < 1024) return `${b} B`
  if (b < 1024 * 1024) return `${(b / 1024).toFixed(1)} KB`
  if (b < 1024 * 1024 * 1024) return `${(b / 1024 / 1024).toFixed(1)} MB`
  return `${(b / 1024 / 1024 / 1024).toFixed(2)} GB`
}
function formatDate(v) {
  if (!v) return '-'
  return String(v).replace('T', ' ').substring(0, 16)
}
</script>

<style scoped>
.admin-downloads { padding: 24px; }
.admin-downloads h2 { font-size: 24px; margin-bottom: 24px; }
.filter-bar { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 16px; align-items: center; }
.filter-input { padding: 8px 12px; border: 1px solid var(--border-color); border-radius: 6px; width: 240px; }
.filter-select { padding: 8px 10px; border: 1px solid var(--border-color); border-radius: 6px; }
.sort-order-btn { padding: 8px 12px; border: 1px solid var(--border-color); border-radius: 6px; background: #fff; cursor: pointer; }
.btn-clear { padding: 8px 12px; border: 1px solid var(--border-color); border-radius: 6px; background: #fff; cursor: pointer; color: var(--text-regular); }
.loading, .empty { padding: 40px; text-align: center; color: var(--text-secondary); }
.task-table { background: #fff; border-radius: 8px; box-shadow: 0 1px 4px rgba(0,0,0,0.08); overflow: hidden; }
.table-header, .table-row { display: grid; grid-template-columns: 60px 2fr 130px 90px 80px 110px 150px; gap: 8px; padding: 12px 16px; align-items: center; }
.table-header { background: #fafafa; font-weight: 600; color: var(--text-regular); font-size: 13px; }
.table-row { border-top: 1px solid #f0f0f0; font-size: 14px; }
.table-row:hover { background: #fafcff; }
.err-tip { color: #f56c6c; cursor: help; margin-left: 4px; }
.status-badge { padding: 2px 10px; border-radius: 12px; font-size: 12px; }
.status-badge.queued { background: #fdf6ec; color: #e6a23c; }
.status-badge.running { background: #ecf5ff; color: #409eff; }
.status-badge.completed { background: #f0f9eb; color: #67c23a; }
.status-badge.failed { background: #fef0f0; color: #f56c6c; }
.status-badge.cancelled { background: #f4f4f5; color: #909399; }
.pagination { display: flex; align-items: center; gap: 8px; margin-top: 16px; justify-content: center; }
.page-btn { padding: 6px 12px; border: 1px solid var(--border-color); border-radius: 6px; background: #fff; cursor: pointer; }
.page-btn.active { background: var(--primary, #409eff); color: #fff; border-color: var(--primary, #409eff); }
.page-btn:disabled { opacity: 0.4; cursor: not-allowed; }
.page-nums { display: inline-flex; gap: 4px; }
.page-ellipsis { padding: 6px 2px; color: var(--text-secondary); user-select: none; }
.page-total { color: var(--text-secondary); font-size: 13px; }
.page-total.center { text-align: center; margin-top: 16px; }
.ep-pagination { margin-top: 16px; justify-content: center; }
</style>
