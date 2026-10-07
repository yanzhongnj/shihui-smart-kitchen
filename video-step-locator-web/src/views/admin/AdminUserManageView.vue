<template>
  <div class="admin-users">
    <div class="filter-bar">
      <button class="btn-add" @click="openCreate">＋ 增加用户</button>
      <input v-model="keyword" class="filter-input" placeholder="搜索用户名 / 昵称 / 邮箱..." @keyup.enter="applyFilters" />
      <select v-model="statusFilter" class="filter-select" @change="applyFilters">
        <option value="">全部状态</option>
        <option value="1">启用</option>
        <option value="0">禁用</option>
      </select>
      <select v-model="roleFilter" class="filter-select" @change="applyFilters">
        <option value="">全部角色</option>
        <option value="ADMIN">管理员</option>
        <option value="USER">普通用户</option>
      </select>
      <select v-model="sortBy" class="filter-select" @change="applyFilters">
        <option value="createdAt">按注册时间</option>
        <option value="username">按用户名</option>
        <option value="role">按角色</option>
      </select>
      <button class="sort-order-btn" @click="toggleOrder">{{ sortOrder === 'asc' ? '↑ 升序' : '↓ 降序' }}</button>
      <button v-if="keyword || statusFilter || roleFilter" class="btn-clear" @click="clearFilters">✕ 清除</button>
    </div>

    <div v-if="loading" class="loading">加载中...</div>
    <div v-else-if="users.length === 0" class="empty"><p>暂无用户</p></div>

    <el-table v-loading="loading" :data="users" border stripe style="width: 100%"
              @sort-change="onSortChange" empty-text="暂无用户">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="username" label="用户名" min-width="120" sortable />
      <el-table-column prop="nickname" label="昵称" min-width="120">
        <template #default="{ row }">{{ row.nickname || '-' }}</template>
      </el-table-column>
      <el-table-column prop="email" label="邮箱" min-width="180">
        <template #default="{ row }">{{ row.email || '-' }}</template>
      </el-table-column>
      <el-table-column prop="role" label="角色" width="90" sortable>
        <template #default="{ row }">
          <el-tag :type="row.role === 'ADMIN' ? 'danger' : 'info'" size="small">{{ row.role === 'ADMIN' ? '管理员' : '用户' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">{{ row.status === 1 ? '启用' : '禁用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="注册时间" width="170" sortable>
        <template #default="{ row }">{{ formatDate(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="300" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="goDetail(row)">详情</el-button>
          <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
          <el-button link type="warning" size="small" @click="toggleRole(row)">{{ row.role === 'ADMIN' ? '设为用户' : '设为管理员' }}</el-button>
          <el-button link type="danger" size="small" @click="toggleStatus(row)">{{ row.status === 1 ? '禁用' : '启用' }}</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination v-if="totalPages > 1" background layout="total, sizes, prev, pager, next"
      :total="total" :current-page="currentPage + 1" :page-size="pageSize"
      :page-sizes="[10, 20, 50]"
      @current-change="onPageChange" @size-change="onSizeChange" class="ep-pagination" />
    <div v-else-if="!loading && users.length > 0" class="page-total center">共 {{ total }} 条</div>

    <!-- 增加 / 编辑用户 -->
    <div v-if="showModal" class="modal-mask" @click.self="closeModal">
      <div class="modal">
        <h3>{{ editing ? '编辑用户' : '增加用户' }}</h3>
        <label class="form-row">
          <span class="form-label">用户名</span>
          <input v-model="form.username" class="form-input" :disabled="editing" placeholder="登录用户名" />
        </label>
        <label class="form-row">
          <span class="form-label">邮箱</span>
          <input v-model="form.email" class="form-input" placeholder="选填，留空自动生成" />
        </label>
        <label class="form-row">
          <span class="form-label">密码</span>
          <input v-model="form.password" class="form-input" type="password" :placeholder="editing ? '留空则不修改' : '至少 6 位'" />
        </label>
        <label class="form-row">
          <span class="form-label">昵称</span>
          <input v-model="form.nickname" class="form-input" placeholder="选填，默认同用户名" />
        </label>
        <div class="form-row">
          <span class="form-label">角色</span>
          <select v-model="form.role" class="form-input">
            <option value="USER">普通用户</option>
            <option value="ADMIN">管理员</option>
          </select>
        </div>
        <div class="form-row">
          <span class="form-label">状态</span>
          <select v-model="form.status" class="form-input">
            <option :value="1">启用</option>
            <option :value="0">禁用</option>
          </select>
        </div>
        <div v-if="errorMsg" class="form-error">{{ errorMsg }}</div>
        <div class="modal-actions">
          <button class="btn-cancel" @click="closeModal">取消</button>
          <button class="btn-save" :disabled="saving" @click="saveUser">
            {{ saving ? '保存中…' : editing ? '保存' : '创建' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { adminAPI } from '@/api/admin'

const router = useRouter()

function goDetail(u) {
  router.push(`/admin/users/${u.id}`)
}

const users = ref([])
const loading = ref(false)
const currentPage = ref(0)
const total = ref(0)
const totalPages = ref(0)
const pageSize = ref(10)

const keyword = ref('')
const statusFilter = ref('')
const roleFilter = ref('')
const sortBy = ref('createdAt')
const sortOrder = ref('desc')

const showModal = ref(false)
const editing = ref(false)
const saving = ref(false)
const errorMsg = ref('')
const editId = ref(null)
const form = ref({ username: '', email: '', password: '', nickname: '', role: 'USER', status: 1 })

onMounted(loadUsers)

function openCreate() {
  editing.value = false
  editId.value = null
  errorMsg.value = ''
  form.value = { username: '', email: '', password: '', nickname: '', role: 'USER', status: 1 }
  showModal.value = true
}

function openEdit(u) {
  editing.value = true
  editId.value = u.id
  errorMsg.value = ''
  form.value = {
    username: u.username,
    email: u.email || '',
    password: '',
    nickname: u.nickname || '',
    role: u.role === 'ADMIN' ? 'ADMIN' : 'USER',
    status: u.status === 1 ? 1 : 0,
  }
  showModal.value = true
}

function closeModal() { showModal.value = false; errorMsg.value = '' }

async function saveUser() {
  errorMsg.value = ''
  if (!editing.value) {
    if (!form.value.username.trim()) { errorMsg.value = '用户名不能为空'; return }
    if (!form.value.password || form.value.password.length < 6) { errorMsg.value = '密码至少 6 位'; return }
  }
  saving.value = true
  try {
    const payload = {
      email: form.value.email,
      nickname: form.value.nickname,
      role: form.value.role,
      status: form.value.status,
    }
    if (!editing.value) {
      payload.username = form.value.username.trim()
      payload.password = form.value.password
    } else if (form.value.password) {
      payload.password = form.value.password
    }
    if (editing.value) {
      await adminAPI.updateUser(editId.value, payload)
    } else {
      await adminAPI.createUser(payload)
    }
    closeModal()
    loadUsers()
  } catch (e) {
    errorMsg.value = e?.message || '保存失败'
  } finally {
    saving.value = false
  }
}

async function loadUsers() {
  loading.value = true
  try {
    const res = await adminAPI.getUsers({
      page: currentPage.value,
      size: pageSize.value,
      keyword: keyword.value || undefined,
      status: statusFilter.value || undefined,
      role: roleFilter.value || undefined,
      sortBy: sortBy.value,
      order: sortOrder.value,
    })
    const d = res.data || {}
    users.value = d.items || []
    total.value = d.total || 0
    totalPages.value = Math.max(1, Math.ceil(total.value / pageSize.value))
  } catch (e) {
    alert(e?.message || '加载用户失败')
  } finally {
    loading.value = false
  }
}

function applyFilters() { currentPage.value = 0; loadUsers() }
function clearFilters() { keyword.value = ''; statusFilter.value = ''; roleFilter.value = ''; currentPage.value = 0; loadUsers() }
function toggleOrder() { sortOrder.value = sortOrder.value === 'asc' ? 'desc' : 'asc'; loadUsers() }
function onPageChange(p) { currentPage.value = p - 1; loadUsers() }
function onSizeChange(s) { pageSize.value = s; currentPage.value = 0; loadUsers() }
function onSortChange({ prop, order }) {
  if (!prop) return
  sortBy.value = prop
  sortOrder.value = order === 'ascending' ? 'asc' : 'desc'
  currentPage.value = 0
  loadUsers()
}

async function toggleStatus(u) {
  const target = u.status === 1 ? 0 : 1
  if (!confirm(`确定要${target === 1 ? '启用' : '禁用'}用户「${u.username}」吗？`)) return
  try {
    await adminAPI.setUserStatus(u.id, target)
    u.status = target
  } catch (e) {
    alert(e?.message || '操作失败')
  }
}

async function toggleRole(u) {
  const target = u.role === 'ADMIN' ? 'USER' : 'ADMIN'
  if (!confirm(`确定将用户「${u.username}」设为${target === 'ADMIN' ? '管理员' : '普通用户'}吗？`)) return
  try {
    await adminAPI.setUserRole(u.id, target)
    u.role = target
  } catch (e) {
    alert(e?.message || '操作失败')
  }
}

function formatDate(v) {
  if (!v) return '-'
  return String(v).replace('T', ' ').substring(0, 16)
}
</script>

<style scoped>
.admin-users { padding: 24px; }
.admin-users h2 { font-size: 24px; margin-bottom: 24px; }
.filter-bar { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 16px; align-items: center; }
.filter-input { padding: 8px 12px; border: 1px solid var(--border-color); border-radius: 6px; width: 240px; }
.filter-select { padding: 8px 10px; border: 1px solid var(--border-color); border-radius: 6px; }
.btn-add { padding: 8px 14px; border: none; border-radius: 6px; background: var(--primary, #409eff); color: #fff; cursor: pointer; font-size: 14px; }
.btn-add:hover { opacity: 0.9; }
.sort-order-btn { padding: 8px 12px; border: 1px solid var(--border-color); border-radius: 6px; background: #fff; cursor: pointer; }
.btn-clear { padding: 8px 12px; border: 1px solid var(--border-color); border-radius: 6px; background: #fff; cursor: pointer; color: var(--text-regular); }
.loading, .empty { padding: 40px; text-align: center; color: var(--text-secondary); }
.user-table { background: #fff; border-radius: var(--radius, 8px); box-shadow: var(--shadow, 0 1px 4px rgba(0,0,0,0.08)); overflow: hidden; }
.table-header, .table-row { display: grid; grid-template-columns: 60px 1.2fr 1fr 1.4fr 90px 80px 150px 160px; gap: 8px; padding: 12px 16px; align-items: center; }
.table-header { background: #fafafa; font-weight: 600; color: var(--text-regular); font-size: 13px; }
.table-row { border-top: 1px solid #f0f0f0; font-size: 14px; }
.table-row:hover { background: #fafcff; }
.role-badge, .status-badge { padding: 2px 10px; border-radius: 12px; font-size: 12px; }
.role-badge.admin { background: #ecf5ff; color: #409eff; }
.role-badge.user { background: #f4f4f5; color: #909399; }
.status-badge.ok { background: #f0f9eb; color: #67c23a; }
.status-badge.off { background: #fef0f0; color: #f56c6c; }
.action-btn { padding: 4px 10px; border: 1px solid var(--border-color); border-radius: 6px; background: #fff; cursor: pointer; font-size: 12px; }
.action-btn.edit:hover { color: #409eff; border-color: #409eff; }
.action-btn.role:hover { color: #67c23a; border-color: #67c23a; }
.action-btn.status:hover { color: #f56c6c; border-color: #f56c6c; }
.pagination { display: flex; align-items: center; gap: 8px; margin-top: 16px; justify-content: center; }
.ep-pagination { margin-top: 16px; justify-content: center; }
.page-btn { padding: 6px 12px; border: 1px solid var(--border-color); border-radius: 6px; background: #fff; cursor: pointer; }
.page-btn.active { background: var(--primary, #409eff); color: #fff; border-color: var(--primary, #409eff); }
.page-btn:disabled { opacity: 0.4; cursor: not-allowed; }
.page-nums { display: inline-flex; gap: 4px; }
.page-ellipsis { padding: 6px 2px; color: var(--text-secondary); user-select: none; }
.page-total { color: var(--text-secondary); font-size: 13px; }
.page-total.center { text-align: center; margin-top: 16px; }
/* 增加/编辑用户弹窗 */
.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,0.4); display: flex; align-items: center; justify-content: center; z-index: 1000; }
.modal { background: #fff; border-radius: 10px; padding: 20px; width: 400px; max-width: 92vw; display: flex; flex-direction: column; gap: 12px; }
.modal h3 { margin: 0; font-size: 17px; }
.form-row { display: flex; align-items: center; gap: 10px; }
.form-label { width: 52px; flex-shrink: 0; font-size: 13px; color: var(--text-regular, #606266); }
.form-input { flex: 1; min-width: 0; padding: 8px 10px; border: 1px solid var(--border-color, #e5e7eb); border-radius: 6px; font-size: 14px; }
.form-input:disabled { background: #f5f7fa; color: #909399; }
.form-error { color: #f56c6c; font-size: 13px; }
.modal-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 4px; }
.btn-cancel { padding: 8px 16px; border: 1px solid var(--border-color, #e5e7eb); border-radius: 6px; background: #fff; cursor: pointer; font-size: 14px; }
.btn-save { padding: 8px 16px; border: none; border-radius: 6px; background: var(--primary, #409eff); color: #fff; cursor: pointer; font-size: 14px; }
.btn-save:disabled { opacity: 0.6; cursor: not-allowed; }
</style>
