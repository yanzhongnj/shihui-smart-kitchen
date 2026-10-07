<template>
  <div class="creations">
    <div class="filter-bar">
      <input v-model="keyword" class="filter-input" placeholder="搜索创作标题..." @keyup.enter="apply" />
      <input v-model.number="userId" class="filter-input narrow" type="number" placeholder="用户ID" @keyup.enter="apply" />
      <select v-model="status" class="filter-select" @change="apply">
        <option value="">全部状态</option>
        <option value="draft">草稿</option>
        <option value="published">已发布</option>
        <option value="archived">已归档</option>
      </select>
      <button class="btn-filter" @click="apply">查询</button>
      <button v-if="keyword || userId || status" class="btn-clear" @click="clear">✕ 清除</button>
    </div>

    <el-table v-loading="loading" :data="creations" border stripe empty-text="暂无酿味创作" style="width: 100%">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="title" label="标题" min-width="180" />
      <el-table-column label="用户" width="140">
        <template #default="{ row }">{{ row.username || `#${row.userId}` }}</template>
      </el-table-column>
      <el-table-column label="灵感" width="90">
        <template #default="{ row }">{{ row.inspiration || '-' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="statusTag(row.status)">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="version" label="版本" width="70" />
      <el-table-column prop="stepCount" label="步骤" width="70" />
      <el-table-column prop="updatedAt" label="更新时间" width="170">
        <template #default="{ row }">{{ fmt(row.updatedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
          <el-button link type="danger" size="small" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination v-if="total > pageSize" background layout="total, prev, pager, next"
      :total="total" :page-size="pageSize" :current-page="currentPage + 1"
      @current-change="p => { currentPage = p - 1; load() }" class="ep-pagination" />

    <!-- 详情弹窗 -->
    <el-dialog v-model="detailShow" :title="`创作详情 #${detail?.id}`" width="720px" top="5vh">
      <div v-if="detailLoading" class="loading">加载中...</div>
      <div v-else-if="detail" class="detail-body">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="标题" :span="2">{{ detail.title }}</el-descriptions-item>
          <el-descriptions-item label="用户">{{ detail.username || `#${detail.userId}` }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag size="small" :type="statusTag(detail.status)">{{ detail.status }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="灵感">{{ detail.inspiration || '-' }}</el-descriptions-item>
          <el-descriptions-item label="难度">{{ detail.difficulty || '-' }}</el-descriptions-item>
          <el-descriptions-item label="热量">{{ detail.calories ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="用时(分)">{{ detail.estimatedTime ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="版本">{{ detail.version ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="迭代">{{ detail.iterCount ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="步骤数">{{ detail.stepCount ?? 0 }}</el-descriptions-item>
          <el-descriptions-item label="创建时间" :span="2">{{ fmt(detail.createdAt) }}</el-descriptions-item>
        </el-descriptions>
        <div v-if="detail.summary" class="sec"><b>摘要：</b>{{ detail.summary }}</div>
        <div v-if="detail.mainIngredients" class="sec"><b>主料：</b>{{ detail.mainIngredients }}</div>
        <div v-if="detail.auxiliaryIngredients" class="sec"><b>辅料/调料：</b>{{ detail.auxiliaryIngredients }}</div>
        <div v-if="detail.tasteTags" class="sec"><b>口味：</b>{{ detail.tasteTags }}</div>
        <div v-if="detail.allergens" class="sec"><b>过敏原：</b>{{ detail.allergens }}</div>
        <div v-if="detail.steps" class="sec">
          <b>步骤（{{ detail.stepCount }}）：</b>
          <pre class="steps-pre">{{ detail.steps }}</pre>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { adminAPI } from '@/api/admin'

const creations = ref([])
const loading = ref(false)
const keyword = ref('')
const userId = ref(null)
const status = ref('')
const currentPage = ref(0)
const pageSize = 10
const total = ref(0)

const detailShow = ref(false)
const detailLoading = ref(false)
const detail = ref(null)

onMounted(load)

async function load() {
  loading.value = true
  try {
    const res = await adminAPI.getCreations({
      page: currentPage.value, size: pageSize,
      keyword: keyword.value || undefined,
      userId: userId.value || undefined,
      status: status.value || undefined,
    })
    total.value = res.data?.total || 0
    creations.value = res.data?.items || []
  } catch (e) { alert(e?.message || '加载失败') } finally { loading.value = false }
}
function apply() { currentPage.value = 0; load() }
function clear() { keyword.value = ''; userId.value = null; status.value = ''; currentPage.value = 0; load() }

async function openDetail(row) {
  detailShow.value = true
  detailLoading.value = true
  detail.value = null
  try { detail.value = (await adminAPI.getCreationDetail(row.id)).data || null }
  catch (e) { alert(e?.message || '加载失败') } finally { detailLoading.value = false }
}

async function remove(row) {
  if (!confirm(`删除创作「${row.title || row.id}」（用户 ${row.username || row.userId}）？其磁盘视频/TTS 文件与关联表一并删除。`)) return
  try { await adminAPI.deleteCreation(row.id); load() }
  catch (e) { alert(e?.message || '操作失败') }
}

function statusTag(s) {
  if (s === 'published') return 'success'
  if (s === 'archived') return 'info'
  return 'warning'
}
function fmt(v) { return v ? String(v).replace('T', ' ').slice(0, 19) : '-' }
</script>

<style scoped>
.filter-bar { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.filter-input { border: 1px solid #dcdfe6; border-radius: 6px; padding: 6px 10px; font-size: 13px; }
.filter-input.narrow { width: 120px; }
.filter-select { border: 1px solid #dcdfe6; border-radius: 6px; padding: 6px 8px; font-size: 13px; background: #fff; }
.btn-filter, .btn-clear { border: 1px solid #dcdfe6; background: #fff; border-radius: 6px; padding: 6px 12px; cursor: pointer; }
.btn-filter:hover { color: var(--primary, #409eff); border-color: var(--primary, #409eff); }
.detail-body .sec { margin-top: 10px; font-size: 13px; word-break: break-word; }
.steps-pre { background: #f4f4f5; border-radius: 6px; padding: 10px; font-size: 12px; white-space: pre-wrap; max-height: 320px; overflow: auto; }
.loading { padding: 30px; text-align: center; color: var(--text-secondary); }
</style>
