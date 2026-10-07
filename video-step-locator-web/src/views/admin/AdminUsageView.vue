<template>
  <div class="usage-view">
    <div class="usage-summary">
      <div class="usage-card">
        <div class="usage-num">{{ usageSummary.totalRecords ?? 0 }}</div>
        <div class="usage-label">用量记录总数</div>
      </div>
      <div class="usage-card">
        <div class="usage-num">{{ formatSize(usageSummary.totalBytes) }}</div>
        <div class="usage-label">累计流量</div>
      </div>
      <div v-for="(cnt, type) in (usageSummary.byType || {})" :key="type" class="usage-card">
        <div class="usage-num">{{ cnt }} <span class="sub">条</span></div>
        <div class="usage-label">{{ typeLabel(type) }}（{{ formatSize((usageSummary.bytesByType || {})[type]) }}）</div>
      </div>
    </div>

    <div class="filter-bar">
      <input v-model.number="usageUserId" class="filter-input narrow" type="number" placeholder="用户ID" @keyup.enter="usageApply" />
      <select v-model="usageType" class="filter-select" @change="usageApply">
        <option value="">全部动作</option>
        <option v-for="t in ['parse', 'preview', 'download', 'process', 'videogen', 'tts']" :key="t" :value="t">{{ typeLabel(t) }}</option>
      </select>
      <button v-if="usageUserId || usageType" class="btn-clear" @click="usageClear">✕ 清除</button>
    </div>

    <el-table v-loading="loading" :data="usageList" border stripe style="width: 100%" empty-text="暂无用量记录">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column label="用户" min-width="110">
        <template #default="{ row }">{{ row.username || '游客' }}</template>
      </el-table-column>
      <el-table-column label="动作" width="100">
        <template #default="{ row }">
          <el-tag :type="actionTag(row.actionType)" size="small">{{ typeLabel(row.actionType) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="视频/引用" min-width="200">
        <template #default="{ row }">{{ fmtVideo(row) }}</template>
      </el-table-column>
      <el-table-column label="数量" width="120">
        <template #default="{ row }">{{ fmtAmount(row.actionType, row.bytes) }}</template>
      </el-table-column>
      <el-table-column prop="createdAt" label="时间" width="165">
        <template #default="{ row }">{{ formatDate(row.createdAt) }}</template>
      </el-table-column>
    </el-table>

    <el-pagination v-if="totalPages > 1" background layout="total, sizes, prev, pager, next"
      :total="total" :current-page="currentPage + 1" :page-size="pageSize"
      :page-sizes="[10, 20, 50]"
      @current-change="p => { currentPage = p - 1; usageLoad() }" @size-change="onSizeChange" class="ep-pagination" />
    <div v-else-if="!loading && usageList.length > 0" class="page-total center">共 {{ total }} 条</div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { adminAPI } from '@/api/admin'

const usageList = ref([])
const usageSummary = ref({})
const loading = ref(false)
const currentPage = ref(0)
const total = ref(0)
const totalPages = ref(0)
const pageSize = ref(10)
const usageUserId = ref(null)
const usageType = ref('')

onMounted(() => { usageLoad(); loadUsageSummary() })

async function usageLoad() {
  loading.value = true
  try {
    const res = await adminAPI.getUsage({
      page: currentPage.value, size: pageSize.value,
      userId: usageUserId.value || undefined,
      actionType: usageType.value || undefined,
    })
    total.value = res.data?.total || 0
    totalPages.value = Math.max(1, Math.ceil(total.value / pageSize.value))
    usageList.value = res.data?.items || []
  } catch (e) { alert(e?.message || '加载失败') } finally { loading.value = false }
}
function usageApply() { currentPage.value = 0; usageLoad() }
function usageClear() { usageUserId.value = null; usageType.value = ''; currentPage.value = 0; usageLoad() }
function onSizeChange(s) { pageSize.value = s; currentPage.value = 0; usageLoad() }

async function loadUsageSummary() {
  try { usageSummary.value = (await adminAPI.getUsageSummary()).data || {} }
  catch (e) { /* 静默 */ }
}

function typeLabel(t) {
  return { parse: '解析', preview: '预览', download: '下载', process: '处理', videogen: '视频生成', tts: '语音合成' }[t] || t
}
function actionTag(t) {
  if (t === 'videogen' || t === 'tts') return 'warning'
  if (t === 'download') return 'primary'
  return 'info'
}
function fmtVideo(u) {
  if (!u.videoId) return '-'
  if (u.videoTitle) return `${u.videoTitle}（${u.videoId}）`
  return `#${u.videoId}`
}
function fmtAmount(type, bytes) {
  if (type === 'videogen') return `${bytes ?? 0} 秒`
  if (type === 'tts') return `${bytes ?? 0} 字符`
  return formatSize(bytes)
}
function formatSize(b) {
  if (b == null) return '-'
  if (b < 1024) return `${b} B`
  if (b < 1024 * 1024) return `${(b / 1024).toFixed(1)} KB`
  if (b < 1024 * 1024 * 1024) return `${(b / 1024 / 1024).toFixed(1)} MB`
  return `${(b / 1024 / 1024 / 1024).toFixed(2)} GB`
}
function formatDate(v) { return v ? String(v).replace('T', ' ').slice(0, 19) : '-' }
</script>

<style scoped>
.usage-summary { display: flex; flex-wrap: wrap; gap: 12px; margin-bottom: 16px; }
.usage-card { background: #fff; border-radius: 8px; box-shadow: 0 1px 4px rgba(0,0,0,0.08); padding: 14px 20px; text-align: center; min-width: 120px; }
.usage-num { font-size: 22px; font-weight: 700; color: var(--primary, #409eff); }
.usage-num .sub { font-size: 13px; color: var(--text-secondary); }
.usage-label { font-size: 12px; color: var(--text-secondary); margin-top: 4px; }
.filter-bar { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.filter-input { border: 1px solid #dcdfe6; border-radius: 6px; padding: 6px 10px; font-size: 13px; }
.filter-input.narrow { width: 110px; }
.filter-select { border: 1px solid #dcdfe6; border-radius: 6px; padding: 6px 8px; font-size: 13px; background: #fff; }
.btn-clear { border: 1px solid #dcdfe6; background: #fff; border-radius: 6px; padding: 6px 12px; cursor: pointer; }
.btn-clear:hover { color: #f56c6c; border-color: #f56c6c; }
</style>
