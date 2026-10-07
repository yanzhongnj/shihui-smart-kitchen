<template>
  <div class="recipe-list-page">
    <div class="page-actions">
      <div class="view-toggle" title="切换显示样式">
        <button class="toggle-btn" :class="{ active: viewMode === 'card' }" @click="setViewMode('card')">▦ 卡片</button>
        <button class="toggle-btn" :class="{ active: viewMode === 'table' }" @click="setViewMode('table')">☰ 表格</button>
      </div>
      <div class="actions">
        <div class="filters">
          <select v-model="cuisine" @change="applyFilters" class="filter-select">
            <option value="">全部菜系</option>
            <option v-for="c in cuisines" :key="c" :value="c">{{ c }}</option>
          </select>
          <select v-model="taste" @change="applyFilters" class="filter-select">
            <option value="">全部口味</option>
            <option v-for="t in tastes" :key="t" :value="t">{{ t }}</option>
          </select>
          <select v-model="type" @change="applyFilters" class="filter-select">
            <option value="">全部类型</option>
            <option value="INDUSTRIAL">工业型</option>
            <option value="HOME">家常型</option>
          </select>
          <select v-model="source" @change="applyFilters" class="filter-select">
            <option value="">全部来源</option>
            <option value="VIDEO_LINK">视频菜谱</option>
            <option value="ARTICLE_LINK">图文录入</option>
            <option value="USER">手动录入</option>
            <option value="AI_CREATE">AI创作</option>
          </select>
        </div>
        <button class="btn-primary" @click="$router.push('/app/recipes/new')">＋ 录入菜谱</button>
      </div>
    </div>

    <div v-if="loading" class="loading">加载中...</div>
    <div v-else-if="recipes.length === 0" class="empty">
      <p>暂无公开菜谱</p>
      <p class="empty-hint">可点击右上角「录入菜谱」解析图文链接，或注册用户处理视频后自动生成菜谱</p>
    </div>

    <div v-else-if="viewMode === 'card'" class="recipe-grid">
      <div v-for="r in recipes" :key="r.id" class="recipe-card"
           @click="$router.push(`/app/recipes/${r.id}`)">
        <div class="recipe-card-top">
          <div class="badges">
            <span :class="['compat-badge', compatClass(r)]">{{ compatText(r) }}</span>
            <span class="source-badge" :class="sourceClass(r)">{{ sourceLabel(r) }}</span>
            <span class="type-badge" :class="r.recipeType === 'INDUSTRIAL' ? 'industrial' : 'home'">
              {{ r.recipeType === 'INDUSTRIAL' ? '工业型' : '家常型' }}
            </span>
          </div>
          <span class="recipe-cuisine" v-if="r.cuisine">{{ r.cuisine }}</span>
        </div>
        <div class="recipe-title">
          {{ r.title }}
          <span class="score" v-if="r.precisionScore != null">评分 {{ Math.round(r.precisionScore * 100) }}/100</span>
        </div>
        <div class="recipe-meta">
          <span v-if="r.tasteTags" class="meta-tag">口味：{{ r.tasteTags }}</span>
          <span v-if="r.spiceLevel != null" class="meta-tag">辣度 {{ r.spiceLevel }}</span>
          <span v-if="r.estimatedTime" class="meta-tag">{{ r.estimatedTime }}分钟</span>
          <span v-if="r.calories" class="meta-tag">{{ r.calories }}千卡</span>
        </div>
        <div class="recipe-summary" v-if="r.summary">{{ r.summary }}</div>
        <div class="recipe-video" v-if="r.videoTitle">
          🎬 关联视频：{{ r.videoTitle }}
        </div>
        <div class="card-foot" v-if="canManage(r)">
          <span class="muted">我的菜谱</span>
          <button class="del-btn" @click.stop="confirmDelete(r)">🗑 删除</button>
        </div>
      </div>
    </div>

    <div v-else class="recipe-table-wrap">
      <table class="recipe-table">
        <thead>
          <tr>
            <th>菜谱</th>
            <th>类型</th>
            <th>来源</th>
            <th>评分</th>
            <th>口味</th>
            <th>辣度</th>
            <th>时间</th>
            <th>热量</th>
            <th v-if="hasManage">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in recipes" :key="r.id" @click="$router.push(`/app/recipes/${r.id}`)">
            <td>
              <div class="t-title">{{ r.title }}</div>
              <div class="t-video" v-if="r.videoTitle">🎬 {{ r.videoTitle }}</div>
            </td>
            <td><span :class="['type-badge', r.recipeType === 'INDUSTRIAL' ? 'industrial' : 'home']">{{ r.recipeType === 'INDUSTRIAL' ? '工业型' : '家常型' }}</span></td>
            <td><span :class="['source-badge', sourceClass(r)]">{{ sourceLabel(r) }}</span></td>
            <td>{{ r.precisionScore != null ? Math.round(r.precisionScore * 100) + '/100' : '-' }}</td>
            <td>{{ r.tasteTags || '-' }}</td>
            <td>{{ r.spiceLevel != null ? r.spiceLevel : '-' }}</td>
            <td>{{ r.estimatedTime ? r.estimatedTime + '分钟' : '-' }}</td>
            <td>{{ r.calories ? r.calories + '千卡' : '-' }}</td>
            <td v-if="hasManage"><button class="del-btn" @click.stop="confirmDelete(r)">🗑 删除</button></td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="totalPages > 1" class="pagination">
      <button class="page-btn" :disabled="currentPage === 0" @click="changePage(currentPage - 1)">‹ 上一页</button>
      <span class="page-nums">
        <template v-for="(item, idx) in pageList" :key="idx">
          <span v-if="item === '...'" class="page-ellipsis">…</span>
          <button v-else class="page-btn" :class="{ active: currentPage === item - 1 }" @click="changePage(item - 1)">{{ item }}</button>
        </template>
      </span>
      <button class="page-btn" :disabled="currentPage >= totalPages - 1" @click="changePage(currentPage + 1)">下一页 ›</button>
      <span class="page-total">共 {{ total }} 条</span>
    </div>
    <div v-else-if="!loading && recipes.length > 0" class="page-total center">共 {{ total }} 条</div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { smartPageList } from '@/utils/pagination'
import { useRouter } from 'vue-router'
import { recipeAPI } from '@/api/recipe'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const authStore = useAuthStore()
const recipes = ref([])
const loading = ref(false)
const cuisine = ref('')
const taste = ref('')
const type = ref('')
const source = ref('')
// 分页（避免长列表垂直滚动）：page/size 传给后端返回 {items,total,page,size,totalPages}
const currentPage = ref(0)
const total = ref(0)
const totalPages = ref(0)
const pageList = computed(() => smartPageList(currentPage.value + 1, totalPages.value))
const pageSize = 9

// 视图样式：卡片 / 表格（用户可切换，默认卡片；偏好存 localStorage）
const viewMode = ref(localStorage.getItem('recipeViewMode') || 'card')
function setViewMode(m) {
  viewMode.value = m
  try { localStorage.setItem('recipeViewMode', m) } catch (e) {}
}
const hasManage = computed(() => recipes.value.some(r => canManage(r)))

const cuisines = ['川菜', '粤菜', '鲁菜', '苏菜', '湘菜', '闽菜', '浙菜', '徽菜', '家常', '家常菜', '西餐']
const tastes = ['酸甜', '咸鲜', '麻辣', '鲜香', '清淡', '微辣', '香辣', '甜', '酸']

function compatClass(r) {
  if (!r.compatibility) return 'none'
  const level = r.compatibility.level
  return level === 'RED' ? 'red' : level === 'YELLOW' ? 'yellow' : 'green'
}

function compatText(r) {
  if (!r.compatibility) return '通用'
  const level = r.compatibility.level
  return level === 'RED' ? '忌口' : level === 'YELLOW' ? '慎选' : '推荐'
}

function sourceLabel(r) {
  const s = r.source || r.sourceType
  if (s === 'ARTICLE_LINK') return '图文录入'
  if (s === 'USER') return '手动录入'
  if (s === 'AI_CREATE' || r.sourceType === 'ai_creation') return 'AI创作'
  if (s === 'VIDEO_LINK') return '视频菜谱'
  return s || '菜谱'
}
function sourceClass(r) {
  const s = r.source
  if (s === 'ARTICLE_LINK') return 'article'
  if (s === 'USER') return 'user'
  if (s === 'AI_CREATE' || r.sourceType === 'ai_creation') return 'ai'
  return 'video'
}

function canManage(r) {
  const u = authStore.user
  if (!u) return false
  if (u.role === 'ADMIN') return true
  return r.userId != null && r.userId === u.id
}

async function confirmDelete(r) {
  if (!window.confirm(`确定删除「${r.title}」吗？删除后不可恢复。`)) return
  try {
    await recipeAPI.deleteRecipe(r.id)
    loadRecipes() // 重新加载当前页
  } catch (e) {
    alert(e?.message || '删除失败')
  }
}

async function loadRecipes() {
  loading.value = true
  try {
    const params = { page: currentPage.value, size: pageSize }
    if (cuisine.value) params.cuisine = cuisine.value
    if (taste.value) params.taste = taste.value
    if (type.value) params.type = type.value
    if (source.value) params.source = source.value
    const res = await recipeAPI.getList(params)
    const data = res.data || {}
    recipes.value = data.items || []
    total.value = data.total || 0
    totalPages.value = data.totalPages || 0
  } catch (e) {
    console.error('[菜谱加载失败]', e)
    recipes.value = []
    total.value = 0
    totalPages.value = 0
  } finally {
    loading.value = false
  }
}

function applyFilters() {
  currentPage.value = 0
  loadRecipes()
}

function changePage(p) {
  if (p < 0 || p >= totalPages.value) return
  currentPage.value = p
  loadRecipes()
}

onMounted(loadRecipes)
</script>

<style scoped>
.recipe-list-page { padding: 24px; }
.page-actions { display: flex; justify-content: space-between; align-items: center; margin-bottom: 24px; flex-wrap: wrap; gap: 12px; }
.page-actions h2 { margin: 0; }
.actions { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.filters { display: flex; gap: 10px; }
.filter-select {
  padding: 8px 12px;
  border: 1px solid var(--border-color, #ddd);
  border-radius: var(--radius, 8px);
  background: #fff;
  font-size: 14px;
  cursor: pointer;
}
.btn-primary { padding: 9px 18px; background: var(--primary, #409eff); color: #fff; border: none; border-radius: var(--radius, 8px); cursor: pointer; font-size: 14px; white-space: nowrap; }
.btn-primary:hover { opacity: 0.9; }
.loading, .empty { text-align: center; padding: 60px; color: #999; }
.empty-hint { font-size: 12px; color: #bbb; margin-top: 8px; }
.pagination { display: flex; justify-content: center; align-items: center; gap: 8px; margin-top: 24px; flex-wrap: wrap; }
.page-btn { padding: 7px 12px; border: 1px solid #ddd; background: #fff; border-radius: 6px; cursor: pointer; font-size: 13px; }
.page-btn:hover:not(:disabled) { border-color: var(--primary); color: var(--primary); }
.page-btn:disabled { opacity: 0.4; cursor: not-allowed; }
.page-btn.active { background: var(--primary); color: #fff; border-color: var(--primary); }
.page-nums { display: inline-flex; gap: 4px; }
.page-ellipsis { padding: 6px 2px; color: var(--text-secondary); user-select: none; }
.page-total { font-size: 12px; color: #999; margin-left: 6px; }
.page-total.center { text-align: center; margin-top: 16px; }
/* 视图切换（卡片/表格） */
.view-toggle { display: inline-flex; border: 1px solid var(--border-color, #ddd); border-radius: var(--radius, 8px); overflow: hidden; }
.toggle-btn { padding: 8px 14px; border: none; background: #fff; cursor: pointer; font-size: 13px; color: #666; }
.toggle-btn.active { background: var(--primary, #409eff); color: #fff; }
/* 表格视图 */
.recipe-table-wrap { background: #fff; border-radius: var(--radius, 8px); box-shadow: var(--shadow, 0 2px 8px rgba(0,0,0,0.08)); overflow-x: auto; }
.recipe-table { width: 100%; border-collapse: collapse; }
.recipe-table th, .recipe-table td { text-align: left; padding: 12px 14px; border-bottom: 1px solid var(--border-color, #f0f2f4); font-size: 13px; white-space: nowrap; }
.recipe-table th { background: #f8f9fa; color: #666; font-weight: 600; }
.recipe-table tbody tr { cursor: pointer; }
.recipe-table tbody tr:hover { background: #f5f7fa; }
.t-title { font-weight: 600; color: var(--text-primary, #303133); white-space: normal; }
.t-video { font-size: 12px; color: #999; margin-top: 2px; white-space: normal; }
.recipe-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 16px; }
.recipe-card {
  background: #fff;
  border-radius: var(--radius, 8px);
  box-shadow: var(--shadow, 0 2px 8px rgba(0,0,0,0.08));
  padding: 18px;
  cursor: pointer;
  transition: transform 0.15s, box-shadow 0.15s;
}
.recipe-card:hover { transform: translateY(-2px); box-shadow: 0 4px 16px rgba(0,0,0,0.12); }
.recipe-card-top { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; gap: 8px; }
.badges { display: flex; gap: 6px; flex-wrap: wrap; }
.compat-badge { padding: 3px 10px; border-radius: 12px; font-size: 12px; font-weight: 600; }
.compat-badge.none { background: #e2e3e5; color: #6c757d; }
.compat-badge.green { background: #d4edda; color: #155724; }
.compat-badge.yellow { background: #fff3cd; color: #856404; }
.compat-badge.red { background: #f8d7da; color: #721c24; }
.source-badge { padding: 3px 10px; border-radius: 12px; font-size: 12px; font-weight: 600; }
.source-badge.article { background: #e8f4fd; color: #0c63e4; }
.source-badge.user { background: #e6f7e6; color: #1a7f37; }
.source-badge.ai { background: #f3e8ff; color: #6f42c1; }
.source-badge.video { background: #e2e3e5; color: #495057; }
.type-badge { padding: 3px 10px; border-radius: 12px; font-size: 12px; font-weight: 600; }
.type-badge.industrial { background: #d9f2e3; color: #0f7b3d; }
.type-badge.home { background: #f1f3f5; color: #495057; }
.recipe-cuisine { color: #6f42c1; font-size: 12px; background: #f3e8ff; padding: 2px 10px; border-radius: 10px; white-space: nowrap; }
.recipe-title { font-size: 18px; font-weight: 600; color: var(--text-primary, #303133); margin-bottom: 8px; }
.score { font-size: 12px; color: #e8590c; font-weight: 500; margin-left: 6px; }
.recipe-meta { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 10px; }
.meta-tag { font-size: 12px; color: #666; background: #f5f5f5; padding: 2px 10px; border-radius: 10px; }
.recipe-summary {
  font-size: 13px;
  color: #777;
  line-height: 1.6;
  margin-bottom: 10px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.recipe-video { font-size: 12px; color: var(--primary-color, #409eff); }
.card-foot { display: flex; justify-content: space-between; align-items: center; margin-top: 10px; padding-top: 10px; border-top: 1px dashed var(--border-color, #eef0f2); }
.card-foot .muted { color: #999; font-size: 12px; }
.del-btn { padding: 4px 12px; background: transparent; border: 1px solid #f5c6cb; color: #c0392b; border-radius: 6px; cursor: pointer; font-size: 12px; }
.del-btn:hover { background: #fdf2f2; }
</style>
