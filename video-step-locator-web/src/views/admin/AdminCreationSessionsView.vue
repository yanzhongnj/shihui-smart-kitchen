<template>
  <div class="creation-sessions">
    <div class="filter-bar">
      <input v-model="keyword" class="filter-input" placeholder="搜索会话标题..." @keyup.enter="apply" />
      <input v-model.number="userId" class="filter-input narrow" type="number" placeholder="用户ID" @keyup.enter="apply" />
      <select v-model="status" class="filter-select" @change="apply">
        <option value="">全部状态</option>
        <option v-for="s in statuses" :key="s" :value="s">{{ s }}</option>
      </select>
      <button class="btn-filter" @click="apply">查询</button>
      <button v-if="keyword || userId || status" class="btn-clear" @click="clear">✕ 清除</button>
    </div>

    <el-table v-loading="loading" :data="sessions" border stripe empty-text="暂无酿味会话" style="width: 100%">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="title" label="标题" min-width="180">
        <template #default="{ row }">{{ row.title || '(未命名)' }}</template>
      </el-table-column>
      <el-table-column label="用户" width="140">
        <template #default="{ row }">{{ row.username || `#${row.userId}` }}</template>
      </el-table-column>
      <el-table-column label="状态" width="120">
        <template #default="{ row }">
          <el-tag size="small" :type="statusTag(row.status)">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="messageCount" label="消息" width="80" />
      <el-table-column prop="updatedAt" label="最近更新" width="170">
        <template #default="{ row }">{{ fmt(row.updatedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openMessages(row)">消息</el-button>
          <el-button link type="danger" size="small" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination v-if="total > pageSize" background layout="total, prev, pager, next"
      :total="total" :page-size="pageSize" :current-page="currentPage + 1"
      @current-change="p => { currentPage = p - 1; load() }" class="ep-pagination" />

    <el-drawer v-model="drawer" :title="`会话消息 #${current.id}`" size="48%">
      <div v-if="msgLoading" class="loading">加载中...</div>
      <div v-else class="msg-list">
        <div v-for="m in messages" :key="m.id" class="msg-item" :class="m.role">
          <div class="msg-role">{{ m.role === 'user' ? '👤 用户' : '🤖 助手' }} <span class="msg-time">{{ fmt(m.createdAt) }}</span></div>
          <div class="msg-content">{{ m.content }}</div>
        </div>
        <el-empty v-if="!messages.length" description="暂无消息" />
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { adminAPI } from '@/api/admin'

const sessions = ref([])
const messages = ref([])
const loading = ref(false)
const msgLoading = ref(false)
const drawer = ref(false)
const current = ref({})
const keyword = ref('')
const userId = ref(null)
const status = ref('')
const currentPage = ref(0)
const pageSize = 10
const total = ref(0)

const statuses = ['CREATED', 'GATHERING', 'CONFIRMED', 'GENERATING', 'RECIPE_READY', 'VIDEO_GEN', 'VIDEO_READY', 'DONE']

onMounted(load)

async function load() {
  loading.value = true
  try {
    const res = await adminAPI.getCreationSessions({
      page: currentPage.value, size: pageSize,
      keyword: keyword.value || undefined,
      userId: userId.value || undefined,
      status: status.value || undefined,
    })
    total.value = res.data?.total || 0
    sessions.value = res.data?.items || []
  } catch (e) { alert(e?.message || '加载失败') } finally { loading.value = false }
}
function apply() { currentPage.value = 0; load() }
function clear() { keyword.value = ''; userId.value = null; status.value = ''; currentPage.value = 0; load() }

async function openMessages(row) {
  current.value = row
  drawer.value = true
  msgLoading.value = true
  messages.value = []
  try { messages.value = (await adminAPI.getCreationSessionMessages(row.id)).data || [] }
  catch (e) { alert(e?.message || '加载失败') } finally { msgLoading.value = false }
}

async function remove(row) {
  if (!confirm(`删除酿味会话「${row.title || row.id}」（用户 ${row.username || row.userId}）？其关联创作/视频/TTS 文件一并删除。`)) return
  try { await adminAPI.deleteCreationSession(row.id); load() }
  catch (e) { alert(e?.message || '操作失败') }
}

function statusTag(s) {
  if (['DONE', 'VIDEO_READY', 'RECIPE_READY'].includes(s)) return 'success'
  if (['GENERATING', 'VIDEO_GEN'].includes(s)) return 'warning'
  if (['CONFIRMED'].includes(s)) return 'primary'
  return 'info'
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
.msg-list { display: flex; flex-direction: column; gap: 12px; }
.msg-item { border-radius: 8px; padding: 10px 12px; border: 1px solid #ebeef5; }
.msg-item.user { background: #f0f9eb; }
.msg-item.assistant { background: #f4f4f5; }
.msg-role { font-size: 12px; color: var(--text-secondary); margin-bottom: 6px; }
.msg-time { float: right; }
.msg-content { white-space: pre-wrap; word-break: break-word; font-size: 13px; }
.loading { padding: 30px; text-align: center; color: var(--text-secondary); }
</style>
