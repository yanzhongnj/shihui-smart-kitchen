<template>
  <div class="admin-content">
    <div class="tabs">
      <button v-for="t in tabs" :key="t.key" class="tab"
              :class="{ active: activeTab === t.key }" @click="switchTab(t.key)">
        {{ t.label }}
      </button>
    </div>

    <!-- ===== 分享链接 ===== -->
    <template v-if="activeTab === 'share'">
      <div class="filter-bar">
        <input v-model="shareKeyword" class="filter-input" placeholder="搜索分享码 / 视频ID..." @keyup.enter="shareApply" />
        <button v-if="shareKeyword" class="btn-clear" @click="shareClear">✕ 清除</button>
      </div>
      <el-table v-loading="loading" :data="shareList" border stripe style="width: 100%" empty-text="暂无分享链接">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="code" label="分享码" min-width="140">
          <template #default="{ row }"><span class="mono">{{ row.code }}</span></template>
        </el-table-column>
        <el-table-column label="视频" min-width="180">
          <template #default="{ row }">
            <span class="link" @click="openVideo(row.videoId)">{{ row.videoTitle || `视频#${row.videoId}` }}</span>
          </template>
        </el-table-column>
        <el-table-column label="创建者" min-width="120">
          <template #default="{ row }">{{ row.creator || `#${row.createdBy}` }}</template>
        </el-table-column>
        <el-table-column label="过期" width="120">
          <template #default="{ row }">
            <el-tag :type="row.expired ? 'info' : 'success'" size="small">{{ row.expired ? '已过期' : formatDate(row.expiresAt) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="165">
          <template #default="{ row }">{{ formatDate(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openVideo(row.videoId)">查看</el-button>
            <el-button link type="danger" size="small" @click="removeShare(row)">撤销</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination v-if="totalPages > 1" background layout="total, sizes, prev, pager, next"
        :total="total" :current-page="currentPage + 1" :page-size="pageSize"
        :page-sizes="[10, 20, 50]"
        @current-change="onSharePageChange" @size-change="onSizeChange" class="ep-pagination" />
      <div v-else-if="!loading && shareList.length > 0" class="page-total center">共 {{ total }} 条</div>
    </template>

    <!-- ===== 评价 ===== -->
    <template v-if="activeTab === 'review'">
      <div class="filter-bar">
        <input v-model="reviewKeyword" class="filter-input" placeholder="搜索评论内容..." @keyup.enter="reviewApply" />
        <select v-model="reviewRating" class="filter-select" @change="reviewApply">
          <option value="">全部评分</option>
          <option v-for="i in 5" :key="i" :value="i">{{ i }} 星</option>
        </select>
        <button v-if="reviewKeyword || reviewRating" class="btn-clear" @click="reviewClear">✕ 清除</button>
      </div>
      <el-table v-loading="loading" :data="reviewList" border stripe style="width: 100%" empty-text="暂无评价">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column label="用户" min-width="110">
          <template #default="{ row }">{{ row.username || `#${row.userId}` }}</template>
        </el-table-column>
        <el-table-column label="视频" min-width="170">
          <template #default="{ row }">
            <span class="link" @click="openVideo(row.videoId)">{{ row.videoTitle || `视频#${row.videoId}` }}</span>
          </template>
        </el-table-column>
        <el-table-column label="评分" width="100">
          <template #default="{ row }">
            <span class="stars">{"★".repeat(row.rating || 0)}<span class="dim">{"★".repeat(5 - (row.rating || 0))}</span></span>
          </template>
        </el-table-column>
        <el-table-column label="口味" width="90">
          <template #default="{ row }">{{ row.tasteLabel || '-' }}</template>
        </el-table-column>
        <el-table-column label="评论" min-width="180">
          <template #default="{ row }"><span :title="row.comment">{{ truncate(row.comment) }}</span></template>
        </el-table-column>
        <el-table-column prop="createdAt" label="时间" width="165">
          <template #default="{ row }">{{ formatDate(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openVideo(row.videoId)">查看</el-button>
            <el-button link type="danger" size="small" @click="removeReview(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination v-if="totalPages > 1" background layout="total, sizes, prev, pager, next"
        :total="total" :current-page="currentPage + 1" :page-size="pageSize"
        :page-sizes="[10, 20, 50]"
        @current-change="onReviewPageChange" @size-change="onSizeChange" class="ep-pagination" />
      <div v-else-if="!loading && reviewList.length > 0" class="page-total center">共 {{ total }} 条</div>
    </template>

    <!-- ===== 菜谱清单（管理视角；与主菜单「菜谱」面向用户浏览不同） ===== -->
    <template v-if="activeTab === 'recipe'">
      <div class="tab-hint">全量菜谱清单（含私有/未发布视频），用于内容治理；面向用户的菜谱浏览见侧边栏「菜谱」（卡片 + 个性化排序 + 口味兼容徽标）。</div>
      <div class="filter-bar">
        <input v-model="recipeKeyword" class="filter-input" placeholder="搜索菜名 / 主料..." @keyup.enter="recipeApply" />
        <input v-model="recipeCuisine" class="filter-input narrow" placeholder="菜系" @keyup.enter="recipeApply" />
        <input v-model="recipeTaste" class="filter-input narrow" placeholder="口味" @keyup.enter="recipeApply" />
        <select v-model="recipeSource" class="filter-input narrow" @change="recipeApply">
          <option value="">来源(全部)</option>
          <option value="VIDEO_LINK">视频</option>
          <option value="ARTICLE_LINK">图文</option>
          <option value="USER">用户</option>
          <option value="AI_CREATE">AI创作</option>
        </select>
        <select v-model="recipeType" class="filter-input narrow" @change="recipeApply">
          <option value="">类型(全部)</option>
          <option value="HOME">家常</option>
          <option value="INDUSTRIAL">工业</option>
        </select>
        <button v-if="recipeKeyword || recipeCuisine || recipeTaste || recipeSource || recipeType" class="btn-clear" @click="recipeClear">✕ 清除</button>
      </div>
      <el-table v-loading="loading" :data="recipeList" border stripe style="width: 100%" empty-text="暂无菜谱">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="title" label="菜名" min-width="150">
          <template #default="{ row }"><span :title="row.title">{{ row.title || '-' }}</span></template>
        </el-table-column>
        <el-table-column label="关联视频" min-width="160">
          <template #default="{ row }">
            <span v-if="row.videoId" class="link" @click="openVideo(row.videoId)">{{ row.videoTitle || `视频#${row.videoId}` }}</span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="来源" width="90">
          <template #default="{ row }">{{ sourceLabel(row.source) }}</template>
        </el-table-column>
        <el-table-column label="类型" width="80">
          <template #default="{ row }">
            <el-tag v-if="row.recipeType === 'INDUSTRIAL'" type="success" size="small">工业</el-tag>
            <el-tag v-else-if="row.recipeType === 'HOME'" type="info" size="small">家常</el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="评分" width="90">
          <template #default="{ row }">{{ row.precisionScore != null ? Math.round(row.precisionScore * 100) + '/100' : '-' }}</template>
        </el-table-column>
        <el-table-column label="菜系" width="90">
          <template #default="{ row }">{{ row.cuisine || '-' }}</template>
        </el-table-column>
        <el-table-column label="口味" min-width="140">
          <template #default="{ row }">{{ row.tasteTags || '-' }}</template>
        </el-table-column>
        <el-table-column label="热量" width="90">
          <template #default="{ row }">{{ row.calories != null ? row.calories + ' kcal' : '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openRecipe(row.id)">菜谱详情</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination v-if="totalPages > 1" background layout="total, sizes, prev, pager, next"
        :total="total" :current-page="currentPage + 1" :page-size="pageSize"
        :page-sizes="[10, 20, 50]"
        @current-change="onRecipePageChange" @size-change="onSizeChange" class="ep-pagination" />
      <div v-else-if="!loading && recipeList.length > 0" class="page-total center">共 {{ total }} 条</div>
    </template>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { adminAPI } from '@/api/admin'

const router = useRouter()
const tabs = [
  { key: 'share', label: '分享链接' },
  { key: 'review', label: '评价' },
  { key: 'recipe', label: '菜谱清单' },
]
const activeTab = ref('share')
const loading = ref(false)
const currentPage = ref(0)
const total = ref(0)
const totalPages = ref(0)
const pageSize = ref(10)

// 分享链接
const shareList = ref([])
const shareKeyword = ref('')
// 评价
const reviewList = ref([])
const reviewKeyword = ref('')
const reviewRating = ref('')
// 菜谱
const recipeList = ref([])
const recipeKeyword = ref('')
const recipeCuisine = ref('')
const recipeTaste = ref('')
const recipeSource = ref('')
const recipeType = ref('')

onMounted(() => shareLoad())

function switchTab(t) {
  if (activeTab.value === t) return
  activeTab.value = t
  currentPage.value = 0
  total.value = 0
  totalPages.value = 0
  if (t === 'share') shareLoad()
  else if (t === 'review') reviewLoad()
  else recipeLoad()
}

async function shareLoad() {
  loading.value = true
  try {
    const res = await adminAPI.getShareLinks({ page: currentPage.value, size: pageSize.value, keyword: shareKeyword.value || undefined })
    applyPage(res)
    shareList.value = res.data?.items || []
  } catch (e) { alert(e?.message || '加载失败') } finally { loading.value = false }
}
function shareApply() { currentPage.value = 0; shareLoad() }
function shareClear() { shareKeyword.value = ''; currentPage.value = 0; shareLoad() }

async function reviewLoad() {
  loading.value = true
  try {
    const res = await adminAPI.getReviews({
      page: currentPage.value, size: pageSize.value,
      keyword: reviewKeyword.value || undefined,
      rating: reviewRating.value ? Number(reviewRating.value) : undefined,
    })
    applyPage(res)
    reviewList.value = res.data?.items || []
  } catch (e) { alert(e?.message || '加载失败') } finally { loading.value = false }
}
function reviewApply() { currentPage.value = 0; reviewLoad() }
function reviewClear() { reviewKeyword.value = ''; reviewRating.value = ''; currentPage.value = 0; reviewLoad() }

async function recipeLoad() {
  loading.value = true
  try {
    const res = await adminAPI.getRecipes({
      page: currentPage.value, size: pageSize.value,
      keyword: recipeKeyword.value || undefined,
      cuisine: recipeCuisine.value || undefined,
      taste: recipeTaste.value || undefined,
      source: recipeSource.value || undefined,
      type: recipeType.value || undefined,
    })
    applyPage(res)
    recipeList.value = res.data?.items || []
  } catch (e) { alert(e?.message || '加载失败') } finally { loading.value = false }
}
function recipeApply() { currentPage.value = 0; recipeLoad() }
function recipeClear() { recipeKeyword.value = ''; recipeCuisine.value = ''; recipeTaste.value = ''; recipeSource.value = ''; recipeType.value = ''; currentPage.value = 0; recipeLoad() }
function sourceLabel(s) {
  return { VIDEO_LINK: '视频', ARTICLE_LINK: '图文', USER: '用户', AI_CREATE: 'AI创作' }[s] || s || '-'
}

function applyPage(res) {
  const d = res.data || {}
  total.value = d.total || 0
  totalPages.value = Math.max(1, Math.ceil(total.value / pageSize.value))
}

function onSharePageChange(p) { currentPage.value = p - 1; shareLoad() }
function onReviewPageChange(p) { currentPage.value = p - 1; reviewLoad() }
function onRecipePageChange(p) { currentPage.value = p - 1; recipeLoad() }
function onSizeChange(s) { pageSize.value = s; currentPage.value = 0; reloadActive() }
function reloadActive() {
  if (activeTab.value === 'share') shareLoad()
  else if (activeTab.value === 'review') reviewLoad()
  else recipeLoad()
}

async function removeShare(l) {
  if (!confirm(`确定撤销分享码 ${l.code} 吗？`)) return
  try { await adminAPI.deleteShareLink(l.id); shareLoad() } catch (e) { alert(e?.message || '操作失败') }
}
async function removeReview(r) {
  if (!confirm('确定删除该评价吗？')) return
  try { await adminAPI.deleteReview(r.id); reviewLoad() } catch (e) { alert(e?.message || '操作失败') }
}

function openVideo(id) {
  if (id) router.push(`/app/videos/${id}`)
}
function openRecipe(id) {
  if (id) router.push(`/app/recipes/${id}`)
}
function truncate(s) { return s && s.length > 30 ? s.substring(0, 30) + '…' : (s || '-') }
function formatDate(v) { if (!v) return '-' ; return String(v).replace('T', ' ').substring(0, 16) }
</script>

<style scoped>
.admin-content { padding: 24px; }
.admin-content h2 { font-size: 24px; margin-bottom: 24px; }
.tabs { display: flex; gap: 4px; border-bottom: 1px solid #e4e7ed; margin-bottom: 16px; }
.tab { padding: 10px 20px; border: none; background: none; cursor: pointer; font-size: 15px; color: var(--text-regular); border-bottom: 2px solid transparent; }
.tab.active { color: var(--primary, #409eff); border-bottom-color: var(--primary, #409eff); font-weight: 600; }
.filter-bar { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 16px; align-items: center; }
.tab-hint { font-size: 12px; color: var(--text-secondary); background: #f5f7fa; border: 1px solid var(--border-color); border-radius: 6px; padding: 8px 12px; margin-bottom: 12px; line-height: 1.6; }
.link { color: var(--primary, #409eff); cursor: pointer; }
.link:hover { text-decoration: underline; }
.filter-input { padding: 8px 12px; border: 1px solid var(--border-color); border-radius: 6px; width: 240px; }
.filter-input.narrow { width: 120px; }
.filter-select { padding: 8px 10px; border: 1px solid var(--border-color); border-radius: 6px; }
.btn-clear { padding: 8px 12px; border: 1px solid var(--border-color); border-radius: 6px; background: #fff; cursor: pointer; color: var(--text-regular); }
.loading, .empty { padding: 40px; text-align: center; color: var(--text-secondary); }
.table { background: #fff; border-radius: 8px; box-shadow: 0 1px 4px rgba(0,0,0,0.08); overflow: hidden; }
.table-header, .table-row { display: grid; gap: 8px; padding: 12px 16px; align-items: center; }
.table-header { background: #fafafa; font-weight: 600; color: var(--text-regular); font-size: 13px; }
.table-row { border-top: 1px solid #f0f0f0; font-size: 14px; }
.table-row:hover { background: #fafcff; }
.table-header.share, .table-row.share { grid-template-columns: 50px 120px 2fr 1fr 100px 140px 120px; }
.table-header.review, .table-row.review { grid-template-columns: 50px 1fr 1.6fr 80px 80px 1.6fr 140px 110px; }
.table-header.recipe, .table-row.recipe { grid-template-columns: 50px 1.5fr 1.4fr 80px 64px 76px 80px 1.1fr 80px 110px; }
.badge { padding: 2px 8px; border-radius: 10px; font-size: 12px; }
.badge-industrial { color: #2f9e44; background: #e6fcf5; }
.badge-home { color: #868e96; background: #f1f3f5; }
.mono { font-family: Consolas, monospace; }
.mini-badge { padding: 2px 8px; border-radius: 10px; font-size: 12px; }
.mini-badge.on { background: #f0f9eb; color: #67c23a; }
.mini-badge.off { background: #f4f4f5; color: #909399; }
.stars { color: #e6a23c; }
.stars .dim { color: #e4e7ed; }
.action-btn { padding: 4px 8px; border: 1px solid var(--border-color); border-radius: 6px; background: #fff; cursor: pointer; font-size: 12px; }
.action-btn.view:hover { color: #409eff; border-color: #409eff; }
.action-btn.delete:hover { color: #f56c6c; border-color: #f56c6c; }
.page-total { color: var(--text-secondary); font-size: 13px; }
.page-total.center { text-align: center; margin-top: 16px; }
.ep-pagination { margin-top: 16px; justify-content: center; }
</style>
