<template>
  <div class="task-page">
    <div class="filter-bar">
      <input v-model="keyword" class="filter-input" placeholder="搜索视频标题..." @keyup.enter="applyFilters" />
      <select v-model="statusFilter" class="filter-select" @change="applyFilters">
        <option value="">全部状态</option>
        <option value="queued">排队中</option>
        <option value="running">运行中</option>
        <option value="paused">已暂停</option>
        <option value="completed">已完成</option>
        <option value="failed">失败</option>
      </select>
      <select v-model="sortBy" class="filter-select" @change="applyFilters">
        <option value="createdAt">按创建时间</option>
        <option value="title">按视频标题</option>
        <option value="progress">按进度</option>
        <option value="status">按状态</option>
      </select>
      <button class="sort-order-btn" @click="toggleOrder" :title="sortOrder === 'asc' ? '当前升序，点击改为降序' : '当前降序，点击改为升序'">{{ sortOrder === 'asc' ? '↑ 升序' : '↓ 降序' }}</button>
      <button v-if="keyword || statusFilter" class="btn-clear" @click="clearFilters">✕ 清除</button>
    </div>

    <div v-if="store.loading" class="loading">加载中...</div>
    <div v-else-if="!store.tasks || store.tasks.length === 0" class="empty"><p>暂无智能处理任务</p></div>

    <el-table v-loading="store.loading" :data="store.tasks || []" border stripe style="width: 100%"
              @sort-change="onSortChange" empty-text="暂无智能处理任务">
      <el-table-column prop="videoTitle" label="视频" min-width="220" sortable>
        <template #default="{ row }">
          <span class="task-video" @click="$router.push(`/app/videos/${row.videoId}`)">
            {{ row.videoTitle || `视频 #${row.videoId}` }}
          </span>
        </template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="100" sortable>
        <template #default="{ row }">
          <el-tag :type="statusTagType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="progress" label="进度" width="150" sortable>
        <template #default="{ row }">
          <div class="progress-bar"><div class="progress-fill" :style="{ width: row.progress + '%' }"></div></div>
          <span class="progress-text">{{ row.progress }}%</span>
        </template>
      </el-table-column>
      <el-table-column prop="currentPhase" label="阶段" min-width="120">
        <template #default="{ row }">{{ row.currentPhase || '-' }}</template>
      </el-table-column>
      <el-table-column prop="createdAt" label="时间" width="175" sortable>
        <template #default="{ row }">{{ formatDate(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="230" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.status === 'queued' || row.status === 'paused'"
                     link type="primary" size="small" @click="startTask(row.id)">开始</el-button>
          <el-button v-if="row.status === 'running'"
                     link type="warning" size="small" @click="pauseTask(row.id)">暂停</el-button>
          <el-button v-if="row.status === 'running' || row.status === 'queued'"
                     link type="danger" size="small" @click="stopTask(row.id)">停止</el-button>
          <el-button v-if="row.status === 'failed'"
                     link type="primary" size="small" @click="retryTask(row.id)">重试</el-button>
          <el-button link type="info" size="small" @click="viewLogs(row)">日志</el-button>
          <el-button link type="danger" size="small" @click="deleteTask(row.id)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination v-if="store.totalPages > 1" background layout="total, sizes, prev, pager, next"
      :total="store.total" :current-page="currentPage + 1" :page-size="pageSize"
      :page-sizes="[10, 20, 50]"
      @current-change="onPageChange" @size-change="onSizeChange" class="ep-pagination" />
    <div v-else-if="!store.loading && store.tasks.length > 0" class="page-total center">共 {{ store.total }} 条</div>

    <!-- 日志弹窗 (可拖拽) -->
    <div v-if="showLogs" class="modal-overlay" @click.self="showLogs = false">
      <div class="modal-content" :style="modalStyle" @mousedown="onModalMouseDown">
        <div class="modal-header drag-handle">
          <h3>任务日志 #{{ logTask?.id }}</h3>
          <button class="close-btn" @click="showLogs = false">&times;</button>
        </div>
        <div class="log-list" @mousedown.stop>
          <div v-if="logs.length === 0" class="empty-log">暂无日志</div>
          <div v-for="log in logs" :key="log.id" :class="['log-item', log.level.toLowerCase()]">
            <span class="log-level">{{ log.level }}</span>
            <span class="log-msg">{{ log.message }}</span>
            <span class="log-time">{{ formatDate(log.createdAt) }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useTaskStore } from '@/stores/task'
import { taskAPI } from '@/api/task'

const store = useTaskStore()
const showLogs = ref(false)
const logTask = ref(null)
const logs = ref([])
const currentPage = ref(0)
const pageSize = ref(10)
const keyword = ref('')
const statusFilter = ref('')
const sortBy = ref('createdAt')
const sortOrder = ref('desc')
let pollTimer = null

// Draggable modal
const modalPos = ref({ x: 0, y: 0 })
const dragging = ref(false)
const dragStart = ref({ x: 0, y: 0 })
const modalInitial = ref({ x: 0, y: 0 })

const modalStyle = computed(() => ({
  transform: `translate(calc(-50% + ${modalPos.value.x}px), calc(-50% + ${modalPos.value.y}px))`,
  cursor: dragging.value ? 'grabbing' : 'default',
}))

const POLL_INTERVAL = 5000

onMounted(async () => {
  await store.fetchTasks(currentParams())  // 首次加载显示 loading
  syncPolling()
})
onUnmounted(() => stopPolling())

function currentParams() {
  return {
    page: currentPage.value,
    size: pageSize.value,
    keyword: keyword.value || undefined,
    status: statusFilter.value || undefined,
    sortBy: sortBy.value,
    order: sortOrder.value,
  }
}
function applyFilters() { currentPage.value = 0; store.fetchTasks(currentParams()) }
function toggleOrder() { sortOrder.value = sortOrder.value === 'asc' ? 'desc' : 'asc'; store.fetchTasks(currentParams()) }
function clearFilters() {
  keyword.value = ''; statusFilter.value = ''; sortBy.value = 'createdAt'; sortOrder.value = 'desc'
  currentPage.value = 0
  store.fetchTasks(currentParams())
}
function onPageChange(p) { currentPage.value = p - 1; store.fetchTasks(currentParams()) }
function onSizeChange(s) { pageSize.value = s; currentPage.value = 0; store.fetchTasks(currentParams()) }
function onSortChange({ prop, order }) {
  if (!prop) return
  // 列 prop 与后端排序字段映射（videoTitle -> title）
  const SORT_MAP = { videoTitle: 'title' }
  sortBy.value = SORT_MAP[prop] || prop
  sortOrder.value = order === 'ascending' ? 'asc' : 'desc'
  currentPage.value = 0
  store.fetchTasks(currentParams())
}


// 后台轮询使用静默刷新（不切 loading），避免表格被「加载中…」顶替造成抖动
function startPolling() {
  stopPolling()
  pollTimer = setInterval(async () => {
    await store.fetchTasks(currentParams(), true)
    // 没有活跃任务（运行中/排队中）即停止轮询；暂停/停止的任务不参与刷新
    if (!store.hasActive) stopPolling()
  }, POLL_INTERVAL)
}
function stopPolling() {
  if (pollTimer) { clearInterval(pollTimer); pollTimer = null }
}
// 根据当前是否有活跃任务决定是否轮询
function syncPolling() {
  if (store.hasActive) startPolling()
  else stopPolling()
}

function onModalMouseDown(e) {
  dragging.value = true
  dragStart.value = { x: e.clientX, y: e.clientY }
  modalInitial.value = { x: modalPos.value.x, y: modalPos.value.y }
  document.addEventListener('mousemove', onModalMouseMove)
  document.addEventListener('mouseup', onModalMouseUp)
}
function onModalMouseMove(e) {
  if (!dragging.value) return
  modalPos.value = {
    x: modalInitial.value.x + (e.clientX - dragStart.value.x),
    y: modalInitial.value.y + (e.clientY - dragStart.value.y),
  }
}
function onModalMouseUp() {
  dragging.value = false
  document.removeEventListener('mousemove', onModalMouseMove)
  document.removeEventListener('mouseup', onModalMouseUp)
}

function statusText(s) {
  const map = { queued: '排队中', running: '运行中', paused: '已暂停', completed: '已完成', failed: '失败' }
  return map[s] || s
}
function statusTagType(s) {
  const map = { queued: 'info', running: 'warning', paused: 'primary', completed: 'success', failed: 'danger' }
  return map[s] || 'info'
}
function formatDate(d) { return d ? new Date(d).toLocaleString('zh-CN') : '' }

async function startTask(id) { await store.startTask(id); syncPolling() }
async function pauseTask(id) { await store.pauseTask(id); syncPolling() }
async function stopTask(id) {
  if (confirm('确定停止该智能处理任务？')) {
    try { await store.cancelTask(id); syncPolling() } catch (e) { alert(e?.message || '操作失败') }
  }
}
async function deleteTask(id) { if (confirm('确定删除此任务？')) await store.deleteTask(id) }
async function retryTask(id) { try { await taskAPI.retry(id); await store.fetchTasks(currentParams(), true); syncPolling() } catch (e) { alert(e?.message || '操作失败') } }

async function viewLogs(task) {
  logTask.value = task
  try { const res = await taskAPI.getLogs(task.id); logs.value = res.data || [] } catch { logs.value = [] }
  // 居中显示
  modalPos.value = { x: 0, y: 0 }
  showLogs.value = true
}
</script>

<style scoped>
.task-page { padding: 24px; }
.task-page h2 { margin-bottom: 24px; }
.loading, .empty { text-align: center; padding: 60px; color: #999; }
.task-table { background: #fff; border-radius: var(--radius); box-shadow: var(--shadow); overflow: hidden; }
.task-row {
  display: grid; grid-template-columns: 1.5fr 0.8fr 1fr 1fr 1fr 1.2fr;
  padding: 12px 16px; align-items: center; border-bottom: 1px solid #eee; font-size: 14px;
}
.task-row.header { font-weight: 600; background: #f9f9f9; }
.task-row:last-child { border-bottom: none; }
.task-video { cursor: pointer; color: var(--primary); }
.task-status { padding: 3px 10px; border-radius: 12px; font-size: 12px; text-align: center; width: fit-content; }
.task-status.running { background: #fff3cd; color: #856404; }
.task-status.completed { background: #d4edda; color: #155724; }
.task-status.failed { background: #f8d7da; color: #721c24; }
.task-status.queued { background: #e2e3e5; color: #383d41; }
.task-status.paused { background: #cce5ff; color: #004085; }
.progress-bar { width: 80px; height: 6px; background: #eee; border-radius: 3px; display: inline-block; vertical-align: middle; margin-right: 8px; }
.progress-fill { height: 100%; background: var(--primary); border-radius: 3px; transition: width 0.3s; }
.progress-text { font-size: 12px; color: #666; }
.task-phase { color: #666; font-size: 13px; }
.task-controls { display: flex; gap: 4px; }
.ctrl-btn { background: none; border: 1px solid #ddd; border-radius: 4px; cursor: pointer; padding: 2px 8px; font-size: 14px; }
.ctrl-btn.play { color: #27ae60; } .ctrl-btn.pause { color: #f39c12; }
.ctrl-btn.stop { color: #e74c3c; }
.ctrl-btn.retry { color: #3498db; } .ctrl-btn.info { color: #666; }
.ctrl-btn.delete { color: #999; }
.ctrl-btn:hover { background: #f5f5f5; }
.task-time { color: #999; font-size: 12px; }

/* 筛选/分页 */
.filter-bar { display: flex; gap: 10px; align-items: center; margin-bottom: 16px; flex-wrap: wrap; }
.filter-input { flex: 0 1 260px; padding: 8px 12px; border: 1px solid #ddd; border-radius: 6px; font-size: 13px; outline: none; }
.filter-input:focus { border-color: var(--primary); }
.filter-select { padding: 8px 10px; border: 1px solid #ddd; border-radius: 6px; font-size: 13px; background: #fff; cursor: pointer; }
.sort-order-btn { padding: 8px 12px; border: 1px solid #ddd; border-radius: 6px; background: #fff; cursor: pointer; font-size: 13px; }
.sort-order-btn:hover { border-color: var(--primary); color: var(--primary); }
.btn-clear { padding: 8px 12px; border: 1px solid #ddd; border-radius: 6px; background: #fff; cursor: pointer; font-size: 13px; color: #e74c3c; }
.btn-clear:hover { border-color: #e74c3c; }
.pagination { display: flex; justify-content: center; align-items: center; gap: 8px; margin-top: 24px; flex-wrap: wrap; }
.ep-pagination { margin-top: 16px; justify-content: center; }
.page-btn { padding: 7px 12px; border: 1px solid #ddd; background: #fff; border-radius: 6px; cursor: pointer; font-size: 13px; }
.page-btn:hover:not(:disabled) { border-color: var(--primary); color: var(--primary); }
.page-btn:disabled { opacity: 0.4; cursor: not-allowed; }
.page-btn.active { background: var(--primary); color: #fff; border-color: var(--primary); }
.page-nums { display: inline-flex; gap: 4px; }
.page-ellipsis { padding: 6px 2px; color: var(--text-secondary); user-select: none; }
.page-total { font-size: 12px; color: #999; margin-left: 6px; }
.page-total.center { display: block; text-align: center; margin-top: 16px; margin-left: 0; }

/* Log Modal */
.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.5); display: flex; align-items: center; justify-content: center; z-index: 1000; }
.modal-content { background: #fff; border-radius: 12px; padding: 24px; width: 600px; max-width: 90vw; max-height: 80vh; display: flex; flex-direction: column; position: absolute; top: 50%; left: 50%; user-select: none; box-shadow: 0 8px 32px rgba(0,0,0,0.2); }
.drag-handle { cursor: grab; user-select: none; }
.drag-handle:active { cursor: grabbing; }
.modal-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.modal-header h3 { margin: 0; }
.close-btn { background: none; border: none; font-size: 28px; cursor: pointer; color: #999; }
.log-list { flex: 1; overflow-y: auto; max-height: 60vh; }
.empty-log { text-align: center; color: #999; padding: 40px; }
.log-item { display: grid; grid-template-columns: 50px 1fr 140px; gap: 8px; padding: 8px 12px; border-bottom: 1px solid #f0f0f0; font-size: 13px; }
.log-item.info .log-level { color: #3498db; }
.log-item.warn .log-level { color: #f39c12; }
.log-item.error .log-level { color: #e74c3c; }
.log-level { font-weight: 600; font-size: 12px; }
.log-msg { color: #333; }
.log-time { color: #999; font-size: 12px; text-align: right; }
</style>
