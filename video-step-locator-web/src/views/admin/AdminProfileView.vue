<template>
  <div class="admin-profile">
    <div class="tabs">
      <button v-for="t in tabs" :key="t.key" class="tab"
              :class="{ active: activeTab === t.key }" @click="switchTab(t.key)">
        {{ t.label }}
      </button>
    </div>

    <!-- ===== 寻味画像 ===== -->
    <template v-if="activeTab === 'memory'">
      <div class="filter-bar">
        <input v-model.number="memUserId" class="filter-input narrow" type="number" placeholder="用户ID" @keyup.enter="memoryApply" />
        <input v-model="memKeyword" class="filter-input" placeholder="搜索画像内容..." @keyup.enter="memoryApply" />
        <select v-model="memKey" class="filter-select" @change="memoryApply">
          <option value="">全部键</option>
          <option v-for="k in memoryKeys" :key="k" :value="k">{{ k }}</option>
        </select>
        <select v-model="memType" class="filter-select" @change="memoryApply">
          <option value="">全部类型</option>
          <option value="profile">画像</option>
          <option value="behavior">行为</option>
          <option value="entity">实体</option>
        </select>
        <select v-model="memSource" class="filter-select" @change="memoryApply">
          <option value="">全部来源</option>
          <option v-for="s in sources" :key="s" :value="s">{{ s }}</option>
        </select>
        <label class="filter-check">
          <input v-model="memDeleted" type="checkbox" @change="memoryApply" /> 只看已删除
        </label>
        <button v-if="memoryFilterActive" class="btn-clear" @click="memoryClear">✕ 清除</button>
      </div>
      <el-table v-loading="loading" :data="memoryList" border stripe style="width: 100%" empty-text="暂无画像记录">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column label="用户" min-width="110">
          <template #default="{ row }">{{ row.username || `#${row.userId}` }}</template>
        </el-table-column>
        <el-table-column prop="memKey" label="键" min-width="130">
          <template #default="{ row }"><span class="mono">{{ row.memKey }}</span></template>
        </el-table-column>
        <el-table-column prop="memValue" label="画像内容" min-width="220">
          <template #default="{ row }"><span :title="row.memValue">{{ truncate(row.memValue) }}</span></template>
        </el-table-column>
        <el-table-column label="类型" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.memType" :type="memTypeTag(row.memType)" size="small">{{ row.memType }}</el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="来源" width="100">
          <template #default="{ row }">{{ row.source || '-' }}</template>
        </el-table-column>
        <el-table-column label="置信度" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.confidence != null" :type="confTag(row.confidence)" size="small">{{ row.confidence }}</el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="次数" width="70">
          <template #default="{ row }">{{ row.hitCount ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="软删" width="70">
          <template #default="{ row }">
            <el-tag :type="row.deleted ? 'info' : 'success'" size="small">{{ row.deleted ? '是' : '否' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button link type="danger" size="small" @click="removeMemory(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination v-if="totalPages > 1" background layout="total, sizes, prev, pager, next"
        :total="total" :current-page="currentPage + 1" :page-size="pageSize"
        :page-sizes="[10, 20, 50]"
        @current-change="onMemoryPageChange" @size-change="onSizeChange" class="ep-pagination" />
      <div v-else-if="!loading && memoryList.length > 0" class="page-total center">共 {{ total }} 条</div>
    </template>

    <!-- ===== 用户偏好 ===== -->
    <template v-if="activeTab === 'pref'">
      <div class="filter-bar">
        <input v-model.number="prefUserId" class="filter-input narrow" type="number" placeholder="用户ID" @keyup.enter="prefApply" />
        <input v-model="prefKeyword" class="filter-input" placeholder="搜索口味/忌口/备注..." @keyup.enter="prefApply" />
        <button v-if="prefUserId || prefKeyword" class="btn-clear" @click="prefClear">✕ 清除</button>
      </div>
      <el-table v-loading="loading" :data="prefList" border stripe style="width: 100%" empty-text="暂无用户偏好设置">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column label="用户" min-width="110">
          <template #default="{ row }">{{ row.username || `#${row.userId}` }}</template>
        </el-table-column>
        <el-table-column label="辛辣度" width="90">
          <template #default="{ row }">{{ row.spiceLevel != null ? row.spiceLevel + '/10' : '-' }}</template>
        </el-table-column>
        <el-table-column label="忌口/过敏原" min-width="150">
          <template #default="{ row }"><span :title="row.tabooAllergens">{{ truncate(row.tabooAllergens) }}</span></template>
        </el-table-column>
        <el-table-column label="偏好口味" min-width="150">
          <template #default="{ row }"><span :title="row.preferredTastes">{{ truncate(row.preferredTastes) }}</span></template>
        </el-table-column>
        <el-table-column label="偏好菜系" min-width="150">
          <template #default="{ row }"><span :title="row.preferredCuisines">{{ truncate(row.preferredCuisines) }}</span></template>
        </el-table-column>
        <el-table-column label="热量上限" width="100">
          <template #default="{ row }">{{ row.maxCalories ?? '-' }}</template>
        </el-table-column>
        <el-table-column prop="updatedAt" label="更新时间" width="165">
          <template #default="{ row }">{{ formatDate(row.updatedAt) }}</template>
        </el-table-column>
      </el-table>

      <el-pagination v-if="totalPages > 1" background layout="total, sizes, prev, pager, next"
        :total="total" :current-page="currentPage + 1" :page-size="pageSize"
        :page-sizes="[10, 20, 50]"
        @current-change="onPrefPageChange" @size-change="onSizeChange" class="ep-pagination" />
      <div v-else-if="!loading && prefList.length > 0" class="page-total center">共 {{ total }} 条</div>
    </template>

    <!-- ===== 用量记录 ===== -->
    <template v-if="activeTab === 'usage'">
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
          <option v-for="t in ['parse', 'preview', 'download', 'process']" :key="t" :value="t">{{ typeLabel(t) }}</option>
        </select>
        <button v-if="usageUserId || usageType" class="btn-clear" @click="usageClear">✕ 清除</button>
      </div>
      <el-table v-loading="loading" :data="usageList" border stripe style="width: 100%" empty-text="暂无用量记录">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column label="用户" min-width="110">
          <template #default="{ row }">{{ row.username || '游客' }}</template>
        </el-table-column>
        <el-table-column label="动作" width="90">
          <template #default="{ row }">
            <el-tag :type="actionTag(row.actionType)" size="small">{{ typeLabel(row.actionType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="视频" min-width="220">
          <template #default="{ row }">{{ fmtVideo(row) }}</template>
        </el-table-column>
        <el-table-column label="流量" width="110">
          <template #default="{ row }">{{ formatSize(row.bytes) }}</template>
        </el-table-column>
        <el-table-column prop="createdAt" label="时间" width="165">
          <template #default="{ row }">{{ formatDate(row.createdAt) }}</template>
        </el-table-column>
      </el-table>

      <el-pagination v-if="totalPages > 1" background layout="total, sizes, prev, pager, next"
        :total="total" :current-page="currentPage + 1" :page-size="pageSize"
        :page-sizes="[10, 20, 50]"
        @current-change="onUsagePageChange" @size-change="onSizeChange" class="ep-pagination" />
      <div v-else-if="!loading && usageList.length > 0" class="page-total center">共 {{ total }} 条</div>
    </template>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { adminAPI } from '@/api/admin'

const tabs = [
  { key: 'memory', label: '寻味画像' },
  { key: 'pref', label: '用户偏好' },
  { key: 'usage', label: '用量记录' },
]
const activeTab = ref('memory')
const loading = ref(false)
const currentPage = ref(0)
const total = ref(0)
const totalPages = ref(0)
const pageSize = ref(10)

const memoryKeys = ['taste', 'taboo', 'cuisine', 'skill', 'scene', 'notes',
  'liked_videos', 'disliked_videos', 'liked_dishes', 'disliked_dishes',
  'preferred_ingredients', 'avoided_ingredients']
const sources = ['explicit', 'chat', 'download', 'favorite', 'review', 'vision']

// 寻味画像
const memoryList = ref([])
const memUserId = ref(null)
const memKeyword = ref('')
const memKey = ref('')
const memType = ref('')
const memSource = ref('')
const memDeleted = ref(false)
const memoryFilterActive = computed(() => memUserId.value || memKeyword.value || memKey.value || memType.value || memSource.value || memDeleted.value)

// 用户偏好
const prefList = ref([])
const prefUserId = ref(null)
const prefKeyword = ref('')

// 用量
const usageList = ref([])
const usageSummary = ref({})
const usageUserId = ref(null)
const usageType = ref('')

onMounted(() => { memoryLoad(); loadUsageSummary() })

function switchTab(t) {
  if (activeTab.value === t) return
  activeTab.value = t
  currentPage.value = 0
  total.value = 0
  totalPages.value = 0
  if (t === 'memory') memoryLoad()
  else if (t === 'pref') prefLoad()
  else { usageLoad(); loadUsageSummary() }
}

function applyPage(res) {
  const d = res.data || {}
  total.value = d.total || 0
  totalPages.value = Math.max(1, Math.ceil(total.value / pageSize.value))
}

function onMemoryPageChange(p) { currentPage.value = p - 1; memoryLoad() }
function onPrefPageChange(p) { currentPage.value = p - 1; prefLoad() }
function onUsagePageChange(p) { currentPage.value = p - 1; usageLoad() }
function onSizeChange(s) { pageSize.value = s; currentPage.value = 0; reloadActive() }
function reloadActive() {
  if (activeTab.value === 'memory') memoryLoad()
  else if (activeTab.value === 'pref') prefLoad()
  else { usageLoad(); loadUsageSummary() }
}

async function memoryLoad() {
  loading.value = true
  try {
    const res = await adminAPI.getMemories({
      page: currentPage.value, size: pageSize.value,
      userId: memUserId.value || undefined,
      keyword: memKeyword.value || undefined,
      memKey: memKey.value || undefined,
      memType: memType.value || undefined,
      source: memSource.value || undefined,
      deleted: memDeleted.value ? 1 : undefined,
    })
    applyPage(res)
    memoryList.value = res.data?.items || []
  } catch (e) { alert(e?.message || '加载失败') } finally { loading.value = false }
}
function memoryApply() { currentPage.value = 0; memoryLoad() }
function memoryClear() {
  memUserId.value = null; memKeyword.value = ''; memKey.value = ''; memType.value = ''; memSource.value = ''; memDeleted.value = false
  currentPage.value = 0; memoryLoad()
}

async function prefLoad() {
  loading.value = true
  try {
    const res = await adminAPI.getPreferences({
      page: currentPage.value, size: pageSize.value,
      userId: prefUserId.value || undefined,
      keyword: prefKeyword.value || undefined,
    })
    applyPage(res)
    prefList.value = res.data?.items || []
  } catch (e) { alert(e?.message || '加载失败') } finally { loading.value = false }
}
function prefApply() { currentPage.value = 0; prefLoad() }
function prefClear() { prefUserId.value = null; prefKeyword.value = ''; currentPage.value = 0; prefLoad() }

async function usageLoad() {
  loading.value = true
  try {
    const res = await adminAPI.getUsage({
      page: currentPage.value, size: pageSize.value,
      userId: usageUserId.value || undefined,
      actionType: usageType.value || undefined,
    })
    applyPage(res)
    usageList.value = res.data?.items || []
  } catch (e) { alert(e?.message || '加载失败') } finally { loading.value = false }
}
function usageApply() { currentPage.value = 0; usageLoad() }
function usageClear() { usageUserId.value = null; usageType.value = ''; currentPage.value = 0; usageLoad() }

async function loadUsageSummary() {
  try {
    const res = await adminAPI.getUsageSummary()
    usageSummary.value = res.data || {}
  } catch (e) { /* 静默 */ }
}

async function removeMemory(m) {
  if (!confirm(`确定删除该画像记录（用户「${m.username || m.userId}」- ${m.memKey}）吗？`)) return
  try { await adminAPI.deleteMemory(m.id); memoryLoad() } catch (e) { alert(e?.message || '操作失败') }
}

function typeLabel(t) {
  return { parse: '解析', preview: '预览', download: '下载', process: '处理' }[t] || t
}
function fmtVideo(u) {
  if (!u.videoId) return '-'
  if (u.videoTitle) return `${u.videoTitle}（${u.videoId}）`
  return `视频#${u.videoId}`
}
function confLevel(c) {
  if (c == null) return ''
  if (c >= 80) return 'high'
  if (c >= 50) return 'mid'
  return 'low'
}
function memTypeTag(t) {
  if (t === 'profile') return 'success'
  if (t === 'behavior') return 'info'
  if (t === 'entity') return 'primary'
  return 'info'
}
function confTag(c) {
  if (c >= 80) return 'danger'
  if (c >= 50) return 'warning'
  return 'info'
}
function actionTag(t) {
  if (t === 'parse') return 'success'
  if (t === 'preview') return 'warning'
  if (t === 'download') return 'info'
  if (t === 'process') return 'primary'
  return 'info'
}
function truncate(s) { return s && s.length > 40 ? s.substring(0, 40) + '…' : (s || '-') }
function formatSize(v) {
  if (v == null) return '-'
  const b = Number(v)
  if (b < 1024) return `${b} B`
  if (b < 1024 * 1024) return `${(b / 1024).toFixed(1)} KB`
  if (b < 1024 * 1024 * 1024) return `${(b / 1024 / 1024).toFixed(1)} MB`
  return `${(b / 1024 / 1024 / 1024).toFixed(2)} GB`
}
function formatDate(v) { if (!v) return '-' ; return String(v).replace('T', ' ').substring(0, 16) }
</script>

<style scoped>
.admin-profile { padding: 24px; }
.admin-profile h2 { font-size: 24px; margin-bottom: 24px; }
.tabs { display: flex; gap: 4px; border-bottom: 1px solid #e4e7ed; margin-bottom: 16px; }
.tab { padding: 10px 20px; border: none; background: none; cursor: pointer; font-size: 15px; color: var(--text-regular); border-bottom: 2px solid transparent; }
.tab.active { color: var(--primary, #409eff); border-bottom-color: var(--primary, #409eff); font-weight: 600; }
.filter-bar { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 16px; align-items: center; }
.filter-input { padding: 8px 12px; border: 1px solid var(--border-color); border-radius: 6px; width: 240px; }
.filter-input.narrow { width: 110px; }
.filter-select { padding: 8px 10px; border: 1px solid var(--border-color); border-radius: 6px; }
.filter-check { display: flex; align-items: center; gap: 4px; font-size: 13px; color: var(--text-regular); }
.btn-clear { padding: 8px 12px; border: 1px solid var(--border-color); border-radius: 6px; background: #fff; cursor: pointer; color: var(--text-regular); }
.loading, .empty { padding: 40px; text-align: center; color: var(--text-secondary); }
.table { background: #fff; border-radius: 8px; box-shadow: 0 1px 4px rgba(0,0,0,0.08); overflow: hidden; }
.table-header, .table-row { display: grid; gap: 8px; padding: 12px 16px; align-items: center; }
.table-header { background: #fafafa; font-weight: 600; color: var(--text-regular); font-size: 13px; }
.table-row { border-top: 1px solid #f0f0f0; font-size: 14px; }
.table-row:hover { background: #fafcff; }
.table-header.memory, .table-row.memory { grid-template-columns: 50px 90px 110px 2fr 80px 90px 70px 50px 50px 90px; }
.table-header.pref, .table-row.pref { grid-template-columns: 50px 90px 80px 1.4fr 1.4fr 1.4fr 90px 150px; }
.table-header.usage, .table-row.usage { grid-template-columns: 50px 110px 80px 1.8fr 110px 150px; }
.mono { font-family: Consolas, monospace; }
.mini-badge { padding: 2px 8px; border-radius: 10px; font-size: 12px; }
.mini-badge.on, .mini-badge.profile, .mini-badge.parse { background: #f0f9eb; color: #67c23a; }
.mini-badge.off, .mini-badge.behavior, .mini-badge.download { background: #f4f4f5; color: #909399; }
.mini-badge.entity, .mini-badge.process { background: #ecf5ff; color: #409eff; }
.mini-badge.preview { background: #fdf6ec; color: #e6a23c; }
.conf-badge { padding: 2px 8px; border-radius: 10px; font-size: 12px; }
.conf-badge.high { background: #fef0f0; color: #f56c6c; }
.conf-badge.mid { background: #fdf6ec; color: #e6a23c; }
.conf-badge.low { background: #f4f4f5; color: #909399; }
.action-btn { padding: 4px 8px; border: 1px solid var(--border-color); border-radius: 6px; background: #fff; cursor: pointer; font-size: 12px; }
.action-btn.delete:hover { color: #f56c6c; border-color: #f56c6c; }
.usage-summary { display: flex; flex-wrap: wrap; gap: 12px; margin-bottom: 16px; }
.usage-card { background: #fff; border-radius: 8px; box-shadow: 0 1px 4px rgba(0,0,0,0.08); padding: 14px 20px; text-align: center; min-width: 120px; }
.usage-num { font-size: 22px; font-weight: 700; color: var(--primary, #409eff); }
.usage-num .sub { font-size: 13px; color: var(--text-secondary); }
.usage-label { font-size: 12px; color: var(--text-secondary); margin-top: 4px; }
.pagination { margin-top: 16px; }
.page-total { color: var(--text-secondary); font-size: 13px; }
.page-total.center { text-align: center; margin-top: 16px; }
.ep-pagination { margin-top: 16px; justify-content: center; }
</style>
