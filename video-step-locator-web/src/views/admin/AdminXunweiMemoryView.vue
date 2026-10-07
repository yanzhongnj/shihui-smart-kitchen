<template>
  <div class="xunwei-memory">
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
      @current-change="p => { currentPage = p - 1; memoryLoad() }" @size-change="onSizeChange" class="ep-pagination" />
    <div v-else-if="!loading && memoryList.length > 0" class="page-total center">共 {{ total }} 条</div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { adminAPI } from '@/api/admin'

const memoryList = ref([])
const loading = ref(false)
const currentPage = ref(0)
const total = ref(0)
const totalPages = ref(0)
const pageSize = ref(10)

const memUserId = ref(null)
const memKeyword = ref('')
const memKey = ref('')
const memType = ref('')
const memSource = ref('')
const memDeleted = ref(false)
const memoryFilterActive = computed(() => memUserId.value || memKeyword.value || memKey.value || memType.value || memSource.value || memDeleted.value)

const memoryKeys = ['taste', 'taboo', 'cuisine', 'skill', 'scene', 'notes',
  'liked_videos', 'disliked_videos', 'liked_dishes', 'disliked_dishes',
  'preferred_ingredients', 'avoided_ingredients']
const sources = ['explicit', 'chat', 'download', 'favorite', 'review', 'vision']

onMounted(memoryLoad)

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
    total.value = res.data?.total || 0
    totalPages.value = Math.max(1, Math.ceil(total.value / pageSize.value))
    memoryList.value = res.data?.items || []
  } catch (e) { alert(e?.message || '加载失败') } finally { loading.value = false }
}
function memoryApply() { currentPage.value = 0; memoryLoad() }
function memoryClear() {
  memUserId.value = null; memKeyword.value = ''; memKey.value = ''; memType.value = ''; memSource.value = ''; memDeleted.value = false
  currentPage.value = 0; memoryLoad()
}
function onSizeChange(s) { pageSize.value = s; currentPage.value = 0; memoryLoad() }

async function removeMemory(m) {
  if (!confirm(`确定删除该画像记录（用户「${m.username || m.userId}」- ${m.memKey}）吗？`)) return
  try { await adminAPI.deleteMemory(m.id); memoryLoad() } catch (e) { alert(e?.message || '操作失败') }
}

function truncate(s, n = 30) { return s && s.length > n ? s.slice(0, n) + '…' : (s || '') }
function memTypeTag(t) {
  if (t === 'profile') return 'success'
  if (t === 'behavior') return 'info'
  if (t === 'entity') return 'primary'
  return 'info'
}
function confTag(c) {
  if (c >= 80) return 'success'
  if (c >= 50) return 'warning'
  return 'info'
}
</script>

<style scoped>
.filter-bar { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; flex-wrap: wrap; }
.filter-input { border: 1px solid #dcdfe6; border-radius: 6px; padding: 6px 10px; font-size: 13px; }
.filter-input.narrow { width: 110px; }
.filter-select { border: 1px solid #dcdfe6; border-radius: 6px; padding: 6px 8px; font-size: 13px; background: #fff; }
.filter-check { display: inline-flex; align-items: center; gap: 4px; font-size: 13px; color: var(--text-secondary); }
.btn-clear { border: 1px solid #dcdfe6; background: #fff; border-radius: 6px; padding: 6px 12px; cursor: pointer; }
.btn-clear:hover { color: #f56c6c; border-color: #f56c6c; }
.mono { font-family: Consolas, monospace; font-size: 12px; }
</style>
