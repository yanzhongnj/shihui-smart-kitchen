<template>
  <div class="video-list-page">
    <div class="page-actions">
      <button class="btn-primary" @click="showUpload = true">📤 上传视频</button>
    </div>

    <div class="filter-bar">
      <input v-model="keyword" class="filter-input" placeholder="搜索视频标题..." @keyup.enter="applyFilters" />
      <select v-model="statusFilter" class="filter-select" @change="applyFilters">
        <option value="">全部状态</option>
        <option value="uploaded">待处理</option>
        <option value="processing">处理中</option>
        <option value="completed">已完成</option>
        <option value="failed">失败</option>
      </select>
      <select v-model="sortBy" class="filter-select" @change="applyFilters">
        <option value="createdAt">按创建时间</option>
        <option value="title">按标题</option>
        <option value="fileSize">按大小</option>
        <option value="stepCount">按步骤数</option>
      </select>
      <button class="sort-order-btn" @click="toggleOrder" :title="sortOrder === 'asc' ? '当前升序，点击改为降序' : '当前降序，点击改为升序'">{{ sortOrder === 'asc' ? '↑ 升序' : '↓ 降序' }}</button>
      <button v-if="keyword || statusFilter" class="btn-clear" @click="clearFilters">✕ 清除</button>
    </div>

    <div v-if="loading" class="loading">加载中...</div>
    <div v-else-if="!store.videos || store.videos.length === 0" class="empty">
      <p>还没有视频，点击上方按钮上传</p>
    </div>

    <el-table v-loading="loading" :data="store.videos || []" border stripe style="width: 100%"
              @sort-change="onSortChange" empty-text="暂无视频">
      <el-table-column prop="title" label="标题" min-width="260" sortable>
        <template #default="{ row }">
          <span class="col-title" @click="$router.push(`/app/videos/${row.id}`)">{{ row.title }}</span>
          <span v-if="row.fileSize" class="file-size">{{ formatSize(row.fileSize) }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="130">
        <template #default="{ row }">
          <el-tag :type="statusTagType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
          <el-tag v-if="row.status === 'completed'" :type="reviewTagType(row.reviewStatus)" size="small" style="margin-left:6px">{{ reviewText(row.reviewStatus) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="stepCount" label="步骤" width="80" sortable>
        <template #default="{ row }">
          <span v-if="row.status === 'completed' && row.stepCount" class="step-count">{{ row.stepCount }} 步</span>
          <span v-else class="step-count na">-</span>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="时间" width="175" sortable>
        <template #default="{ row }">{{ formatDate(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.status === 'pending' || row.status === 'uploaded' || row.status === 'failed'"
                     link type="primary" size="small" @click="processVideo(row)">处理</el-button>
          <el-button v-if="row.status === 'completed' || row.status === 'uploaded'"
                     link type="primary" size="small" @click="$router.push(`/app/videos/${row.id}`)">查看</el-button>
          <el-button v-if="row.status === 'completed'"
                     link type="warning" size="small" @click="openReview(row)">审核</el-button>
          <el-button link type="danger" size="small" @click="confirmDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination v-if="store.totalPages > 1" background layout="total, sizes, prev, pager, next"
      :total="store.total" :current-page="currentPage + 1" :page-size="pageSize"
      :page-sizes="[10, 20, 50]"
      @current-change="onPageChange" @size-change="onSizeChange" class="ep-pagination" />
    <div v-else-if="!loading && store.videos.length > 0" class="page-total center">共 {{ store.total }} 条</div>

    <UploadModal v-if="showUpload" @close="showUpload = false" @uploaded="loadVideos" />
    <ReviewVideoDialog v-if="reviewTarget" :video="reviewTarget"
                       @close="reviewTarget = null" @saved="onReviewSaved" />
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useVideoStore } from '@/stores/video'
import { taskAPI } from '@/api/task'
import UploadModal from '@/components/video/UploadModal.vue'
import ReviewVideoDialog from '@/components/video/ReviewVideoDialog.vue'

const store = useVideoStore()
const loading = ref(false)
const currentPage = ref(0)
const pageSize = ref(10)
const showUpload = ref(false)
const reviewTarget = ref(null)
const keyword = ref('')
const statusFilter = ref('')
const sortBy = ref('createdAt')
const sortOrder = ref('desc')
let pollTimer = null

const POLL_INTERVAL = 5000

onMounted(async () => { await loadVideos(); syncPolling() })
onUnmounted(() => stopPolling())

// 后台轮询使用静默刷新（不切 loading），避免表格被「加载中…」顶替造成抖动
function startPolling() {
  stopPolling()
  pollTimer = setInterval(async () => {
    await loadVideos(true)
    // 没有处理中的视频则停止轮询
    if (!hasProcessing()) stopPolling()
  }, POLL_INTERVAL)
}
function stopPolling() {
  if (pollTimer) { clearInterval(pollTimer); pollTimer = null }
}
function syncPolling() {
  if (hasProcessing()) startPolling()
  else stopPolling()
}
function hasProcessing() {
  return store.videos?.some(v => v.status === 'processing') || false
}

async function loadVideos(silent = false) {
  if (!silent) loading.value = true
  await store.fetchVideos({
    page: currentPage.value,
    size: pageSize.value,
    keyword: keyword.value || undefined,
    status: statusFilter.value || undefined,
    sortBy: sortBy.value,
    order: sortOrder.value,
  })
  if (!silent) loading.value = false
}
function onPageChange(p) { currentPage.value = p - 1; loadVideos() }
function onSizeChange(s) { pageSize.value = s; currentPage.value = 0; loadVideos() }
function onSortChange({ prop, order }) {
  if (!prop) return
  sortBy.value = prop
  sortOrder.value = order === 'ascending' ? 'asc' : 'desc'
  currentPage.value = 0
  loadVideos()
}
function applyFilters() { currentPage.value = 0; loadVideos() }
function toggleOrder() { sortOrder.value = sortOrder.value === 'asc' ? 'desc' : 'asc'; loadVideos() }
function clearFilters() {
  keyword.value = ''
  statusFilter.value = ''
  sortBy.value = 'createdAt'
  sortOrder.value = 'desc'
  currentPage.value = 0
  loadVideos()
}
async function processVideo(video) { try { await taskAPI.triggerProcess(video.id); video.status = 'processing' } catch (e) { alert(e?.message || '操作失败') } }
function confirmDelete(video) { if (confirm(`确定删除「${video.title}」？`)) store.deleteVideo(video.id) }
function statusText(s) { const map = { pending: '待处理', uploaded: '待处理', processing: '处理中', completed: '已完成', failed: '失败' }; return map[s] || s }
function statusTagType(s) {
  const map = { pending: 'info', uploaded: 'info', processing: 'warning', completed: 'success', failed: 'danger' }
  return map[s] || 'info'
}
function openReview(video) { reviewTarget.value = video }
function onReviewSaved() { reviewTarget.value = null; loadVideos() }
function reviewText(s) { const map = { pending: '待审核', approved: '已通过', rejected: '已驳回' }; return map[s] || (s || '未审核') }
function reviewTagType(s) {
  const map = { pending: 'warning', approved: 'success', rejected: 'danger' }
  return map[s] || 'info'
}
function formatSize(b) { return b ? (b > 1048576 ? (b/1048576).toFixed(1)+'MB' : (b/1024).toFixed(1)+'KB') : '' }
function formatDate(d) { return d ? new Date(d).toLocaleString('zh-CN') : '' }
</script>

<style scoped>
.video-list-page { padding: 24px; }
.page-actions { display: flex; justify-content: space-between; align-items: center; margin-bottom: 24px; }
.page-actions h2 { margin: 0; }
.btn-primary { padding: 10px 20px; background: var(--primary); color: #fff; border: none; border-radius: var(--radius); cursor: pointer; font-size: 14px; }
.loading, .empty { text-align: center; padding: 60px; color: #999; }
.video-table { background: #fff; border-radius: var(--radius); box-shadow: var(--shadow); overflow: hidden; }
.table-header, .table-row { display: grid; grid-template-columns: 2.5fr 1fr 0.8fr 1.5fr 1.2fr; padding: 14px 16px; align-items: center; font-size: 14px; }
.table-header { font-weight: 600; background: #f9f9f9; border-bottom: 1px solid #eee; }
.table-row { border-bottom: 1px solid #f0f0f0; }
.table-row:hover { background: #fafafa; }
.col-title { cursor: pointer; color: #333; font-weight: 500; }
.col-title:hover { color: var(--primary); }
.file-size { color: #999; font-size: 12px; margin-left: 8px; }
.status-badge { padding: 3px 10px; border-radius: 12px; font-size: 12px; display: inline-block; }
.status-badge.pending { background: #e2e3e5; color: #383d41; }
.status-badge.uploaded { background: #cce5ff; color: #004085; }
.status-badge.processing { background: #fff3cd; color: #856404; }
.status-badge.completed { background: #d4edda; color: #155724; }
.status-badge.failed { background: #f8d7da; color: #721c24; }
.step-count { font-size: 13px; color: #27ae60; font-weight: 500; }
.step-count.na { color: #999; }
.col-actions { display: flex; gap: 6px; }
.action-btn { padding: 4px 12px; border: none; border-radius: 4px; cursor: pointer; font-size: 12px; }
.action-btn.process { background: var(--primary); color: #fff; }
.action-btn.view { background: #3498db; color: #fff; }
.action-btn.review { background: #6f42c1; color: #fff; }
.action-btn.delete { background: none; color: #999; font-size: 16px; padding: 4px 8px; }
.action-btn.delete:hover { color: #e74c3c; }
.review-badge { display: inline-block; margin-left: 6px; padding: 2px 8px; border-radius: 10px; font-size: 11px; vertical-align: middle; }
.review-badge.pending { background: #fff3cd; color: #856404; }
.review-badge.approved { background: #d1ecf1; color: #0c5460; }
.review-badge.rejected { background: #f8d7da; color: #721c24; }
.col-date { color: #999; font-size: 12px; }
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
</style>
