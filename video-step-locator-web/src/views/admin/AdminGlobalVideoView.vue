<template>
  <div class="admin-videos">
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
      <button class="sort-order-btn" @click="toggleOrder">{{ sortOrder === 'asc' ? '↑ 升序' : '↓ 降序' }}</button>
      <button v-if="keyword || statusFilter" class="btn-clear" @click="clearFilters">✕ 清除</button>
    </div>

    <div v-if="loading" class="loading">加载中...</div>
    <div v-else-if="videos.length === 0" class="empty"><p>暂无视频</p></div>

    <el-table v-loading="loading" :data="videos" border stripe style="width: 100%"
              @sort-change="onSortChange" empty-text="暂无视频">
      <el-table-column prop="title" label="标题" min-width="260" sortable>
        <template #default="{ row }">
          {{ row.title }}
          <el-tag :type="row.reviewStatus === 'approved' ? 'success' : row.reviewStatus === 'rejected' ? 'danger' : 'info'" size="small" style="margin-left:6px">{{ reviewText(row.reviewStatus) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="username" label="所属用户" min-width="120">
        <template #default="{ row }">{{ row.username || '-' }}</template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="95">
        <template #default="{ row }">
          <el-tag :type="row.status === 'completed' ? 'success' : row.status === 'failed' ? 'danger' : row.status === 'processing' ? 'warning' : 'info'" size="small">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="visibility" label="可见性" width="90">
        <template #default="{ row }">
          <el-tag :type="row.visibility === 'public' ? 'success' : 'info'" size="small">{{ visText(row.visibility) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="stepCount" label="步骤" width="70" sortable>
        <template #default="{ row }">{{ row.stepCount || '-' }}</template>
      </el-table-column>
      <el-table-column prop="createdAt" label="时间" width="165" sortable>
        <template #default="{ row }">{{ formatDate(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="330" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="$router.push(`/app/videos/${row.id}`)">查看</el-button>
          <el-button v-if="row.reviewStatus !== 'approved'" link type="success" size="small" @click="approve(row)">通过</el-button>
          <el-button link type="warning" size="small" @click="reject(row)">驳回</el-button>
          <el-button v-if="row.reviewStatus === 'approved' && row.visibility !== 'public'" link type="primary" size="small" @click="publish(row)">发布</el-button>
          <el-button v-if="row.visibility === 'public'" link type="warning" size="small" @click="unpublish(row)">下架</el-button>
          <el-button link type="danger" size="small" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination v-if="totalPages > 1" background layout="total, sizes, prev, pager, next"
      :total="total" :current-page="currentPage + 1" :page-size="pageSize"
      :page-sizes="[10, 20, 50]"
      @current-change="onPageChange" @size-change="onSizeChange" class="ep-pagination" />
    <div v-else-if="!loading && videos.length > 0" class="page-total center">共 {{ total }} 条</div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessageBox } from 'element-plus'
import { adminAPI } from '@/api/admin'
import { videoAPI } from '@/api/video'

const videos = ref([])
const loading = ref(false)
const currentPage = ref(0)
const total = ref(0)
const totalPages = ref(0)
const pageSize = ref(10)

const keyword = ref('')
const statusFilter = ref('')
const sortBy = ref('createdAt')
const sortOrder = ref('desc')

onMounted(loadVideos)

async function loadVideos() {
  loading.value = true
  try {
    const res = await adminAPI.getVideos({
      page: currentPage.value,
      size: pageSize.value,
      status: statusFilter.value || undefined,
      keyword: keyword.value || undefined,
      sortBy: sortBy.value,
      order: sortOrder.value,
    })
    const d = res.data || {}
    videos.value = d.items || []
    total.value = d.total || 0
    totalPages.value = Math.max(1, Math.ceil(total.value / pageSize.value))
  } catch (e) {
    alert(e?.message || '加载视频失败')
  } finally {
    loading.value = false
  }
}

function applyFilters() { currentPage.value = 0; loadVideos() }
function clearFilters() { keyword.value = ''; statusFilter.value = ''; currentPage.value = 0; loadVideos() }
function toggleOrder() { sortOrder.value = sortOrder.value === 'asc' ? 'desc' : 'asc'; loadVideos() }
function onPageChange(p) { currentPage.value = p - 1; loadVideos() }
function onSizeChange(s) { pageSize.value = s; currentPage.value = 0; loadVideos() }
function onSortChange({ prop, order }) {
  if (!prop) return
  sortBy.value = prop
  sortOrder.value = order === 'ascending' ? 'asc' : 'desc'
  currentPage.value = 0
  loadVideos()
}

async function approve(v) {
  if (!confirm(`确定通过视频「${v.title}」的审核吗？`)) return
  try { await videoAPI.review(v.id, true, ''); alert('已通过'); loadVideos() }
  catch (e) { alert(e?.message || '操作失败') }
}

async function reject(v) {
  let comment
  try {
    const r = await ElMessageBox.prompt('请输入驳回意见：', '驳回视频', {
      confirmButtonText: '确认驳回',
      cancelButtonText: '取消',
      inputPlaceholder: '驳回意见',
    })
    comment = (r.value || '').trim()
  } catch (e) { return } // 取消
  if (!comment) { alert('驳回意见不能为空'); return }
  try { await videoAPI.review(v.id, false, comment); alert('已驳回'); loadVideos() }
  catch (e) { alert(e?.message || '操作失败') }
}

async function publish(v) {
  if (!confirm(`确定将「${v.title}」发布到体验区吗？`)) return
  try { await videoAPI.publish(v.id); alert('已发布'); loadVideos() }
  catch (e) { alert(e?.message || '操作失败') }
}

async function unpublish(v) {
  if (!confirm(`确定将「${v.title}」下架吗？`)) return
  try { await videoAPI.unpublish(v.id); alert('已下架'); loadVideos() }
  catch (e) { alert(e?.message || '操作失败') }
}

async function remove(v) {
  if (!confirm(`确定删除视频「${v.title}」吗？（同时删除本地文件）`)) return
  try { await videoAPI.delete(v.id); loadVideos() }
  catch (e) { alert(e?.message || '操作失败') }
}

function statusText(s) {
  return { uploaded: '待处理', pending: '待处理', processing: '处理中', completed: '已完成', failed: '失败' }[s] || s
}
function reviewText(s) {
  return { pending: '待审核', approved: '已通过', rejected: '已驳回' }[s] || '待审核'
}
function visText(v) {
  return { public: '公开', shared: '共享', private: '私密' }[v] || v
}
function formatDate(v) {
  if (!v) return '-'
  return String(v).replace('T', ' ').substring(0, 16)
}
</script>

<style scoped>
.admin-videos { padding: 24px; }
.admin-videos h2 { font-size: 24px; margin-bottom: 24px; }
.filter-bar { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 16px; align-items: center; }
.filter-input { padding: 8px 12px; border: 1px solid var(--border-color); border-radius: 6px; width: 240px; }
.filter-select { padding: 8px 10px; border: 1px solid var(--border-color); border-radius: 6px; }
.sort-order-btn { padding: 8px 12px; border: 1px solid var(--border-color); border-radius: 6px; background: #fff; cursor: pointer; }
.btn-clear { padding: 8px 12px; border: 1px solid var(--border-color); border-radius: 6px; background: #fff; cursor: pointer; color: var(--text-regular); }
.loading, .empty { padding: 40px; text-align: center; color: var(--text-secondary); }
.video-table { background: #fff; border-radius: 8px; box-shadow: 0 1px 4px rgba(0,0,0,0.08); overflow: hidden; }
.table-header, .table-row { display: grid; grid-template-columns: 2fr 1fr 90px 90px 60px 140px 260px; gap: 8px; padding: 12px 16px; align-items: center; }
.table-header { background: #fafafa; font-weight: 600; color: var(--text-regular); font-size: 13px; }
.table-row { border-top: 1px solid #f0f0f0; font-size: 14px; }
.table-row:hover { background: #fafcff; }
.review-badge { margin-left: 6px; padding: 2px 8px; border-radius: 10px; font-size: 11px; }
.review-badge.pending { background: #fdf6ec; color: #e6a23c; }
.review-badge.approved { background: #ecf5ff; color: #409eff; }
.review-badge.rejected { background: #fef0f0; color: #f56c6c; }
.status-badge { padding: 2px 10px; border-radius: 12px; font-size: 12px; }
.status-badge.uploaded, .status-badge.pending { background: #fdf6ec; color: #e6a23c; }
.status-badge.processing { background: #ecf5ff; color: #409eff; }
.status-badge.completed { background: #f0f9eb; color: #67c23a; }
.status-badge.failed { background: #fef0f0; color: #f56c6c; }
.vis-badge { padding: 2px 10px; border-radius: 12px; font-size: 12px; background: #f4f4f5; color: #909399; }
.vis-badge.public { background: #f0f9eb; color: #67c23a; }
.action-btn { padding: 4px 8px; border: 1px solid var(--border-color); border-radius: 6px; background: #fff; cursor: pointer; font-size: 12px; }
.action-btn.view:hover { color: #409eff; border-color: #409eff; }
.action-btn.review:hover { color: #67c23a; border-color: #67c23a; }
.action-btn.reject:hover { color: #e6a23c; border-color: #e6a23c; }
.action-btn.publish:hover { color: #409eff; border-color: #409eff; }
.action-btn.unpublish:hover { color: #e6a23c; border-color: #e6a23c; }
.action-btn.delete:hover { color: #f56c6c; border-color: #f56c6c; }
.pagination { display: flex; align-items: center; gap: 8px; margin-top: 16px; justify-content: center; }
.page-btn { padding: 6px 12px; border: 1px solid var(--border-color); border-radius: 6px; background: #fff; cursor: pointer; }
.page-btn.active { background: var(--primary, #409eff); color: #fff; border-color: var(--primary, #409eff); }
.page-btn:disabled { opacity: 0.4; cursor: not-allowed; }
.page-nums { display: inline-flex; gap: 4px; }
.page-ellipsis { padding: 6px 2px; color: var(--text-secondary); user-select: none; }
.page-total { color: var(--text-secondary); font-size: 13px; }
.page-total.center { text-align: center; margin-top: 16px; }
</style>
