<template>
  <div class="admin-versions">
    <div class="page-actions">
      <button class="btn-primary" @click="openCreate">📱 发布新版本</button>
    </div>

    <div class="filter-bar">
      <input v-model="keyword" class="filter-input" placeholder="搜索版本号 / 更新说明..." @keyup.enter="applyFilters" />
      <select v-model="platformFilter" class="filter-select" @change="applyFilters">
        <option value="">全部平台</option>
        <option value="android">Android</option>
        <option value="ios">iOS</option>
      </select>
      <button v-if="keyword || platformFilter" class="btn-clear" @click="clearFilters">✕ 清除</button>
    </div>

    <el-table v-loading="loading" :data="versions" border stripe style="width: 100%"
              @sort-change="onSortChange" empty-text="暂无版本记录">
      <el-table-column prop="id" label="ID" width="60" />
      <el-table-column prop="platform" label="平台" width="90">
        <template #default="{ row }">
          <el-tag :type="row.platform === 'android' ? 'primary' : 'success'" size="small">{{ row.platform === 'android' ? 'Android' : 'iOS' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="version" label="版本" min-width="100" />
      <el-table-column label="Build" width="80">
        <template #default="{ row }">{{ row.build ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="最低版本" min-width="110">
        <template #default="{ row }">{{ row.minVersion || '-' }}</template>
      </el-table-column>
      <el-table-column label="强制" width="80">
        <template #default="{ row }">
          <el-tag :type="row.forceUpdate ? 'danger' : 'info'" size="small">{{ row.forceUpdate ? '强制' : '否' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="启用" width="90">
        <template #default="{ row }">
          <el-button :type="row.enabled ? 'success' : 'info'" size="small" @click="toggleEnabled(row)">{{ row.enabled ? '启用' : '停用' }}</el-button>
        </template>
      </el-table-column>
      <el-table-column label="更新说明" min-width="200">
        <template #default="{ row }">
          <span :title="row.changelog">{{ truncate(row.changelog) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
          <el-button link type="success" size="small" @click="pickApk(row)">上传 APK</el-button>
          <el-button link type="danger" size="small" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 隐藏的 APK 文件选择 -->
    <input ref="apkInput" type="file" accept=".apk,application/vnd.android.package-archive" style="display:none" @change="onApkPicked" />

    <el-pagination v-if="totalPages > 1" background layout="total, sizes, prev, pager, next"
      :total="total" :current-page="currentPage + 1" :page-size="pageSize"
      :page-sizes="[10, 20, 50]"
      @current-change="onPageChange" @size-change="onSizeChange" class="ep-pagination" />
    <div v-else-if="!loading && versions.length > 0" class="page-total center">共 {{ total }} 条</div>

    <!-- 新增/编辑弹窗 -->
    <div v-if="formVisible" class="modal-mask" @click.self="formVisible = false">
      <div class="modal">
        <div class="modal-header">
          <h3>{{ form.id ? '编辑版本' : '发布新版本' }}</h3>
          <button class="close-btn" @click="formVisible = false">✕</button>
        </div>
        <div class="modal-body">
          <div class="form-grid">
            <label class="field">
              <span class="field-label">平台 *</span>
              <select v-model="form.platform" class="form-input">
                <option value="android">Android</option>
                <option value="ios">iOS</option>
              </select>
            </label>
            <label class="field">
              <span class="field-label">版本号 *</span>
              <input v-model="form.version" class="form-input" placeholder="如 1.1.0" />
            </label>
            <label class="field">
              <span class="field-label">Build 号</span>
              <input v-model.number="form.build" class="form-input" type="number" placeholder="如 10" />
            </label>
            <label class="field">
              <span class="field-label">最低支持版本</span>
              <input v-model="form.minVersion" class="form-input" placeholder="如 1.0.0" />
            </label>
            <label class="field">
              <span class="field-label">强制更新</span>
              <select v-model="form.forceUpdate" class="form-input">
                <option :value="true">是</option>
                <option :value="false">否</option>
              </select>
            </label>
            <label class="field">
              <span class="field-label">启用</span>
              <select v-model="form.enabled" class="form-input">
                <option :value="true">启用</option>
                <option :value="false">停用</option>
              </select>
            </label>
            <label class="field wide">
              <span class="field-label">下载地址 (APK)</span>
              <input v-model="form.downloadUrl" class="form-input" placeholder="https://..." />
            </label>
            <label class="field wide">
              <span class="field-label">MD5</span>
              <input v-model="form.md5" class="form-input" placeholder="安装包 MD5（可选）" />
            </label>
            <label class="field wide">
              <span class="field-label">更新说明</span>
              <textarea v-model="form.changelog" class="form-input textarea" rows="3" placeholder="本次更新内容"></textarea>
            </label>
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn-ghost" @click="formVisible = false">取消</button>
          <button class="btn-primary" :disabled="saving" @click="save">{{ saving ? '保存中...' : '保存' }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { adminAPI } from '@/api/admin'

const versions = ref([])
const loading = ref(false)
const currentPage = ref(0)
const total = ref(0)
const totalPages = ref(0)
const pageSize = ref(10)

const keyword = ref('')
const platformFilter = ref('')

const formVisible = ref(false)
const saving = ref(false)
const form = ref(emptyForm())
const apkInput = ref(null)
const uploading = ref(false)
const apkRow = ref(null)

function emptyForm() {
  return {
    id: null, platform: 'android', version: '', build: null, minVersion: '',
    forceUpdate: false, enabled: true, changelog: '', downloadUrl: '', md5: '',
  }
}

onMounted(loadVersions)

async function loadVersions() {
  loading.value = true
  try {
    const res = await adminAPI.getAppVersions({
      page: currentPage.value,
      size: pageSize.value,
      platform: platformFilter.value || undefined,
      keyword: keyword.value || undefined,
    })
    const d = res.data || {}
    versions.value = d.items || []
    total.value = d.total || 0
    totalPages.value = Math.max(1, Math.ceil(total.value / pageSize.value))
  } catch (e) {
    alert(e?.message || '加载版本失败')
  } finally {
    loading.value = false
  }
}

function applyFilters() { currentPage.value = 0; loadVersions() }
function clearFilters() { keyword.value = ''; platformFilter.value = ''; currentPage.value = 0; loadVersions() }
function onPageChange(p) { currentPage.value = p - 1; loadVersions() }
function onSizeChange(s) { pageSize.value = s; currentPage.value = 0; loadVersions() }
function onSortChange() { /* 版本页后端不支持排序，忽略 */ }

function openCreate() {
  form.value = emptyForm()
  formVisible.value = true
}
function openEdit(v) {
  form.value = {
    id: v.id, platform: v.platform, version: v.version, build: v.build,
    minVersion: v.minVersion || '', forceUpdate: !!v.forceUpdate, enabled: !!v.enabled,
    changelog: v.changelog || '', downloadUrl: v.downloadUrl || '', md5: v.md5 || '',
  }
  formVisible.value = true
}

async function save() {
  if (!form.value.version) { alert('请填写版本号'); return }
  saving.value = true
  try {
    const payload = {
      platform: form.value.platform,
      version: form.value.version,
      build: form.value.build,
      minVersion: form.value.minVersion || null,
      forceUpdate: !!form.value.forceUpdate,
      enabled: !!form.value.enabled,
      changelog: form.value.changelog || null,
      downloadUrl: form.value.downloadUrl || null,
      md5: form.value.md5 || null,
    }
    if (form.value.id) {
      await adminAPI.updateAppVersion(form.value.id, payload)
    } else {
      await adminAPI.createAppVersion(payload)
    }
    formVisible.value = false
    loadVersions()
  } catch (e) {
    alert(e?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function toggleEnabled(v) {
  try {
    await adminAPI.updateAppVersion(v.id, { enabled: !v.enabled })
    v.enabled = !v.enabled
  } catch (e) {
    alert(e?.message || '操作失败')
  }
}

async function remove(v) {
  if (!confirm(`确定删除版本 ${v.platform} ${v.version} 吗？`)) return
  try {
    await adminAPI.deleteAppVersion(v.id)
    loadVersions()
  } catch (e) {
    alert(e?.message || '操作失败')
  }
}

// ---------- APK 上传 ----------
function pickApk(row) {
  apkRow.value = row
  if (apkInput.value) apkInput.value.click()
}

async function onApkPicked(e) {
  const file = e.target.files?.[0]
  e.target.value = ''
  if (!file || !apkRow.value) return
  const row = apkRow.value
  uploading.value = true
  try {
    const res = await adminAPI.uploadAppApk(row.id, file)
    alert(`APK 上传成功：${res?.data?.fileName || ''}`)
    loadVersions()
  } catch (err) {
    alert(err?.message || 'APK 上传失败')
  } finally {
    uploading.value = false
    apkRow.value = null
  }
}

function truncate(s) {
  if (!s) return '-'
  return s.length > 40 ? s.substring(0, 40) + '…' : s
}
</script>

<style scoped>
.admin-versions { padding: 24px; }
.admin-versions h2 { font-size: 24px; margin-bottom: 24px; }
.page-actions { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; }
.btn-primary { padding: 10px 18px; background: var(--primary, #409eff); color: #fff; border: none; border-radius: 6px; cursor: pointer; }
.filter-bar { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 16px; align-items: center; }
.filter-input { padding: 8px 12px; border: 1px solid var(--border-color); border-radius: 6px; width: 240px; }
.filter-select { padding: 8px 10px; border: 1px solid var(--border-color); border-radius: 6px; }
.btn-clear { padding: 8px 12px; border: 1px solid var(--border-color); border-radius: 6px; background: #fff; cursor: pointer; color: var(--text-regular); }
.loading, .empty { padding: 40px; text-align: center; color: var(--text-secondary); }
.version-table { background: #fff; border-radius: 8px; box-shadow: 0 1px 4px rgba(0,0,0,0.08); overflow: hidden; }
.table-header, .table-row { display: grid; grid-template-columns: 50px 80px 90px 70px 90px 60px 70px 2fr 130px; gap: 8px; padding: 12px 16px; align-items: center; }
.table-header { background: #fafafa; font-weight: 600; color: var(--text-regular); font-size: 13px; }
.table-row { border-top: 1px solid #f0f0f0; font-size: 14px; }
.table-row:hover { background: #fafcff; }
.platform-badge { padding: 2px 10px; border-radius: 12px; font-size: 12px; }
.platform-badge.android { background: #ecf5ff; color: #409eff; }
.platform-badge.ios { background: #f0f9eb; color: #67c23a; }
.mini-badge { padding: 2px 8px; border-radius: 10px; font-size: 12px; }
.mini-badge.on { background: #fef0f0; color: #f56c6c; }
.mini-badge.off { background: #f4f4f5; color: #909399; }
.toggle-btn { padding: 4px 10px; border: 1px solid var(--border-color); border-radius: 12px; background: #f4f4f5; color: #909399; cursor: pointer; font-size: 12px; }
.toggle-btn.on { background: #f0f9eb; color: #67c23a; border-color: #67c23a; }
.action-btn { padding: 4px 8px; border: 1px solid var(--border-color); border-radius: 6px; background: #fff; cursor: pointer; font-size: 12px; }
.action-btn.edit:hover { color: #409eff; border-color: #409eff; }
.action-btn.delete:hover { color: #f56c6c; border-color: #f56c6c; }
.pagination { display: flex; align-items: center; gap: 8px; margin-top: 16px; justify-content: center; }
.page-btn { padding: 6px 12px; border: 1px solid var(--border-color); border-radius: 6px; background: #fff; cursor: pointer; }
.page-btn.active { background: var(--primary, #409eff); color: #fff; border-color: var(--primary, #409eff); }
.page-btn:disabled { opacity: 0.4; cursor: not-allowed; }
.page-nums { display: inline-flex; gap: 4px; }
.page-ellipsis { padding: 6px 2px; color: var(--text-secondary); user-select: none; }
.page-total { color: var(--text-secondary); font-size: 13px; }
.page-total.center { text-align: center; margin-top: 16px; }
.ep-pagination { margin-top: 16px; justify-content: center; }
.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,0.4); display: flex; align-items: center; justify-content: center; z-index: 1000; }
.modal { width: 640px; max-width: 92vw; max-height: 88vh; background: #fff; border-radius: 8px; display: flex; flex-direction: column; }
.modal-header { display: flex; align-items: center; justify-content: space-between; padding: 14px 18px; border-bottom: 1px solid #eee; }
.modal-header h3 { margin: 0; font-size: 16px; }
.close-btn { border: none; background: none; font-size: 16px; cursor: pointer; }
.modal-body { padding: 16px 18px; overflow-y: auto; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.field { display: flex; flex-direction: column; gap: 6px; }
.field.wide { grid-column: 1 / -1; }
.field-label { font-size: 13px; color: var(--text-secondary); }
.form-input { padding: 8px 10px; border: 1px solid var(--border-color); border-radius: 6px; font-size: 14px; }
.form-input.textarea { resize: vertical; font-family: inherit; }
.modal-footer { display: flex; justify-content: flex-end; gap: 10px; padding: 14px 18px; border-top: 1px solid #eee; }
.btn-ghost { padding: 8px 16px; border: 1px solid var(--border-color); border-radius: 6px; background: #fff; cursor: pointer; }
.btn-primary:disabled { opacity: 0.6; cursor: not-allowed; }
</style>
