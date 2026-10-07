<template>
  <div class="recycle-page">
    <div class="page-head">
      <div class="head-line">
        <h2>🗑️ 回收站（软删除记录治理）</h2>
        <button class="btn-filter" @click="openSettings">⚙️ 自动清理设置</button>
      </div>
      <p class="tip">仅系统管理员可操作。恢复：还原记录（打卡级联恢复饮食、作品图片保留）。归档并彻底删除：先写入归档表（审计副本）再物理删除，<b>不可撤销</b>。超过保留期的软删记录由系统自动清理并归档（点右上「⚙️ 自动清理设置」可配置保留期/执行时间、启停或手动触发）。</p>
    </div>

    <!-- 各类型待处理数 + 审计入口 -->
    <div class="stat-row">
      <span v-for="t in types" :key="t.key" class="stat-chip" :class="{ active: !showAudit && activeType === t.key }" @click="switchType(t.key)">
        {{ t.label }} <b>{{ stats[t.key] ?? 0 }}</b>
      </span>
      <span class="stat-chip" :class="{ active: showAudit }" @click="switchAudit">
        🧾 审计记录 <b>{{ auditTotal }}</b>
      </span>
    </div>

    <!-- ===== 记录列表 ===== -->
    <template v-if="!showAudit">
      <div class="filter-bar">
        <input v-model="query.userId" class="filter-input narrow" type="number" placeholder="用户ID（可选）" @keyup.enter="reload" />
        <input v-model="query.keyword" class="filter-input" :placeholder="keywordPlaceholder" @keyup.enter="reload" />
        <button class="btn-filter" @click="reload">查询</button>
        <button class="btn-filter ghost" @click="reset">重置</button>
      </div>
      <div class="batch-bar">
        <span class="sel-tip">已选 {{ selectedRows.length }} 条</span>
        <button class="btn-filter" :disabled="!selectedRows.length" @click="batchRestore">批量恢复</button>
        <button class="btn-filter danger" :disabled="!selectedRows.length" @click="batchPurge">批量归档并彻底删除</button>
      </div>

      <el-table v-loading="loading" :data="rows" border stripe empty-text="回收站暂无记录" style="width: 100%"
                @selection-change="sel => selectedRows = sel" row-key="id">
        <el-table-column type="selection" width="45" reserve-selection />
        <el-table-column label="类型" width="90">
          <template #default>{{ typeLabel(activeType) }}</template>
        </el-table-column>
        <el-table-column prop="username" label="用户" width="110" />
        <!-- 打卡 -->
        <template v-if="activeType === 'checkins'">
          <el-table-column prop="checkin_date" label="日期" width="110" />
          <el-table-column prop="recipe_title" label="菜名" min-width="140">
            <template #default="{ row }">{{ row.recipe_title || '-' }}</template>
          </el-table-column>
          <el-table-column prop="meal_type" label="餐次" width="90" />
          <el-table-column prop="calories" label="热量" width="80">
            <template #default="{ row }">{{ row.calories ?? '-' }}</template>
          </el-table-column>
        </template>
        <!-- 饮食 -->
        <template v-else-if="activeType === 'diet'">
          <el-table-column prop="record_date" label="日期" width="110" />
          <el-table-column prop="meal_type" label="餐次" width="90" />
          <el-table-column label="摘要" min-width="160">
            <template #default="{ row }">{{ dietSummary(row) }}</template>
          </el-table-column>
          <el-table-column prop="calories" label="热量" width="80">
            <template #default="{ row }">{{ row.calories ?? '-' }}</template>
          </el-table-column>
        </template>
        <!-- 计划 -->
        <template v-else-if="activeType === 'plans'">
          <el-table-column prop="title" label="标题" min-width="160">
            <template #default="{ row }">{{ row.title || '-' }}</template>
          </el-table-column>
          <el-table-column prop="plan_type" label="类型" width="90" />
          <el-table-column prop="start_date" label="起始日" width="110" />
        </template>
        <!-- 作品 -->
        <template v-else-if="activeType === 'works'">
          <el-table-column label="标题" min-width="160">
            <template #default="{ row }">{{ row.title || row.recipe_title || row.id }}</template>
          </el-table-column>
          <el-table-column label="图片" width="70">
            <template #default="{ row }">{{ workImageCount(row) }}</template>
          </el-table-column>
          <el-table-column prop="status" label="可见性" width="90" />
        </template>
        <!-- 拍照打卡 -->
        <template v-else-if="activeType === 'photos'">
          <el-table-column prop="record_date" label="日期" width="110" />
          <el-table-column label="餐次" width="90">
            <template #default="{ row }">{{ mealTypeLabel(row.meal_type) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="photoStatusTag(row.status)" size="small">{{ photoStatusLabel(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="识别摘要" min-width="160">
            <template #default="{ row }">{{ photoSummary(row) }}</template>
          </el-table-column>
          <el-table-column prop="diet_record_id" label="关联饮食" width="90">
            <template #default="{ row }">{{ row.diet_record_id || '-' }}</template>
          </el-table-column>
        </template>
        <!-- 菜品营养库 -->
        <template v-else-if="activeType === 'dishes'">
          <el-table-column prop="dish_name" label="菜名" min-width="140">
            <template #default="{ row }">{{ row.dish_name || '-' }}</template>
          </el-table-column>
          <el-table-column label="来源" width="90">
            <template #default="{ row }">
              <el-tag :type="sourceTag(row.source)" size="small">{{ sourceLabel(row.source) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="复核" width="80">
            <template #default="{ row }">{{ verifiedLabel(row.is_verified) }}</template>
          </el-table-column>
          <el-table-column prop="hit_count" label="命中" width="70">
            <template #default="{ row }">{{ row.hit_count ?? 0 }}</template>
          </el-table-column>
          <el-table-column label="每份营养" min-width="150">
            <template #default="{ row }">{{ dishNutritionSummary(row) }}</template>
          </el-table-column>
        </template>
        <!-- 记忆 -->
        <template v-else>
          <el-table-column prop="mem_key" label="画像键" width="130" />
          <el-table-column prop="mem_value" label="记忆内容" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">{{ row.mem_value || '-' }}</template>
          </el-table-column>
          <el-table-column prop="mem_type" label="类型" width="90" />
          <el-table-column prop="confidence" label="置信度" width="80" />
        </template>

        <el-table-column label="创建/更新时间" width="150">
          <template #default="{ row }">{{ fmt(row.created_at || row.updated_at) }}</template>
        </el-table-column>
        <el-table-column label="删除时间" width="150">
          <template #default="{ row }">{{ fmt(row.deleted_at) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="restore(row)">恢复</el-button>
            <el-button link type="danger" size="small" @click="purge(row)">归档并彻底删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination v-if="total > pageSize" background layout="prev, pager, next" class="mt12"
        :total="total" :page-size="pageSize" :current-page="page"
        @current-change="p => { page = p; reload() }" />
    </template>

    <!-- ===== 审计记录 ===== -->
    <template v-else>
      <div class="filter-bar">
        <select v-model="auditQuery.type" class="filter-input">
          <option value="">全部类型</option>
          <option v-for="t in types" :key="t.key" :value="t.key">{{ t.label }}</option>
        </select>
        <select v-model="auditQuery.action" class="filter-input">
          <option value="">全部操作</option>
          <option value="restore">恢复</option>
          <option value="purge">彻底删除</option>
        </select>
        <button class="btn-filter" @click="loadAudit">查询</button>
      </div>
      <el-table v-loading="auditLoading" :data="auditRows" border stripe empty-text="暂无审计记录" style="width: 100%">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="created_at" label="时间" width="150">
          <template #default="{ row }">{{ fmt(row.created_at) }}</template>
        </el-table-column>
        <el-table-column label="操作人" width="110">
          <template #default="{ row }">{{ row.username || '系统任务' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="110">
          <template #default="{ row }">
            <el-tag :type="row.action === 'purge' ? 'danger' : 'success'" size="small">
              {{ row.action === 'purge' ? '彻底删除' : '恢复' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="110">
          <template #default="{ row }">{{ typeLabel(row.target_type) }}</template>
        </el-table-column>
        <el-table-column prop="target_id" label="记录ID" width="90" />
        <el-table-column prop="detail" label="备注" min-width="160">
          <template #default="{ row }">{{ row.detail || '-' }}</template>
        </el-table-column>
      </el-table>
      <el-pagination v-if="auditTotal > auditPageSize" background layout="prev, pager, next" class="mt12"
        :total="auditTotal" :page-size="auditPageSize" :current-page="auditPage"
        @current-change="p => { auditPage = p; loadAudit() }" />
    </template>

    <!-- ⚙️ 自动清理设置弹窗 -->
    <el-dialog v-model="settingsOpen" title="⚙️ 回收站超期自动清理设置" width="560px" append-to-body>
      <div class="settings-form">
        <div class="set-row">
          <label>保留期（天）</label>
          <input v-model.number="settings.retentionDays" type="number" min="1" max="3650" class="filter-input narrow" />
          <span class="set-hint">软删记录超过该天数后自动「归档 + 彻底删除」</span>
        </div>
        <div class="set-row">
          <label>执行 Cron</label>
          <input v-model="settings.purgeCron" class="filter-input" placeholder="0 30 3 * * *（每日 03:30）" />
          <span class="set-hint">修改后运行时生效，无需重启；非法表达式会被拒绝</span>
        </div>
        <div class="set-row">
          <label>启用自动清理</label>
          <el-switch v-model="settings.enabled" />
          <span class="set-hint">停用后定时任务跳过；「立即清理」不受影响</span>
        </div>
        <div class="set-row last-run">
          <span v-if="settings.lastRunAt">最近执行：{{ fmt(settings.lastRunAt) }} · 清理 {{ settings.lastRunCount }} 条（{{ settings.lastRunDetail || '无' }}）</span>
          <span v-else>尚未执行过自动清理</span>
        </div>
      </div>
      <template #footer>
        <div class="dialog-footer">
          <button class="btn-filter" :disabled="running" @click="runPurgeNow">{{ running ? '清理中…' : '立即清理' }}</button>
          <button class="btn-filter primary" @click="saveSettings">保存配置</button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessageBox, ElMessage } from 'element-plus'
import { adminAPI } from '@/api/admin'

const types = [
  { key: 'checkins', label: '打卡' },
  { key: 'diet', label: '饮食记录' },
  { key: 'plans', label: '做饭计划' },
  { key: 'works', label: '作品' },
  { key: 'memories', label: '记忆' },
  { key: 'photos', label: '拍照打卡' },
  { key: 'dishes', label: '菜品营养库' },
]

const activeType = ref('checkins')
const stats = ref({})
const loading = ref(false)
const rows = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = 20
const query = ref({ userId: '', keyword: '' })
const selectedRows = ref([])
const showAudit = ref(false)

// ⚙️ 超期自动清理设置（弹窗）
const settings = ref({ retentionDays: 90, purgeCron: '0 30 3 * * *', enabled: true, lastRunAt: null, lastRunCount: 0, lastRunDetail: '' })
const running = ref(false)
const settingsOpen = ref(false)

// 审计
const auditRows = ref([])
const auditTotal = ref(0)
const auditPage = ref(1)
const auditPageSize = 20
const auditQuery = ref({ type: '', action: '' })
const auditLoading = ref(false)

const keywordPlaceholder = computed(() => ({
  checkins: '菜名/备注关键字（可选）',
  diet: '备注/食物关键字（可选）',
  plans: '计划标题关键字（可选）',
  works: '标题/描述关键字（可选）',
  memories: '画像键/内容关键字（可选）',
  photos: '用户名/识别摘要关键字（可选）',
  dishes: '菜名/来源关键字（可选）',
}[activeType.value]))

onMounted(async () => {
  await loadSettings()
  await loadStats()
  await reload()
  await loadAudit()
})

async function loadSettings() {
  try { settings.value = (await adminAPI.getRecycleSettings()).data || settings.value } catch (e) { /* 静默 */ }
}

function openSettings() {
  loadSettings()
  settingsOpen.value = true
}

async function saveSettings() {
  try {
    const d = (await adminAPI.updateRecycleSettings({
      retentionDays: Number(settings.value.retentionDays),
      purgeCron: (settings.value.purgeCron || '').trim(),
      enabled: !!settings.value.enabled,
    })).data
    settings.value = d || settings.value
    ElMessage.success('已保存（运行时生效，无需重启）')
    settingsOpen.value = false // 保存成功后关闭对话框
  } catch (e) { ElMessage.error(e?.message || '保存失败') }
}

async function runPurgeNow() {
  if (!confirm('立即执行一次超期自动清理（归档并彻底删除超过保留期的软删记录）？')) return
  running.value = true
  try {
    const d = (await adminAPI.runRecyclePurge()).data || {}
    const label = { checkins: '打卡', diet: '饮食', plans: '计划', works: '作品', memories: '记忆', photos: '拍照', dishes: '菜品' }
    const parts = Object.entries(d.byType || {}).map(([k, v]) => `${label[k] || k} ${v} 条`)
    ElMessage.success(`清理完成：共 ${d.total} 条（${parts.join('，') || '无'}）`)
    await loadSettings()
    await loadStats()
  } catch (e) { ElMessage.error(e?.message || '清理失败') } finally { running.value = false }
}

async function loadStats() {
  try { stats.value = (await adminAPI.getRecycleStats()).data || {} } catch (e) { /* 静默 */ }
}

async function reload() {
  loading.value = true
  try {
    const params = { page: page.value - 1, size: pageSize }
    if (query.value.userId) params.userId = Number(query.value.userId)
    if (query.value.keyword) params.keyword = query.value.keyword
    const res = (await adminAPI.getRecycleList(activeType.value, params)).data || {}
    rows.value = res.items || []
    total.value = res.total || 0
    selectedRows.value = []
  } catch (e) { alert(e?.message || '加载失败') } finally { loading.value = false }
}

function reset() {
  query.value = { userId: '', keyword: '' }
  page.value = 1
  reload()
}

function switchType(t) {
  showAudit.value = false
  if (t === activeType.value) return
  activeType.value = t
  page.value = 1
  query.value = { userId: '', keyword: '' }
  reload()
}

function switchAudit() {
  showAudit.value = !showAudit.value
  if (showAudit.value) loadAudit()
}

async function loadAudit() {
  auditLoading.value = true
  try {
    const params = { page: auditPage.value - 1, size: auditPageSize }
    if (auditQuery.value.type) params.type = auditQuery.value.type
    if (auditQuery.value.action) params.action = auditQuery.value.action
    const res = (await adminAPI.getRecycleAudit(params)).data || {}
    auditRows.value = res.items || []
    auditTotal.value = res.total || 0
  } catch (e) { alert(e?.message || '加载失败') } finally { auditLoading.value = false }
}

function typeLabel(t) {
  return types.find(x => x.key === t)?.label || t
}

function rowLabel(row) {
  if (activeType.value === 'checkins') return row.recipe_title || row.checkin_date
  if (activeType.value === 'plans') return row.title
  if (activeType.value === 'memories') return `${row.mem_key} (${row.username || row.user_id})`
  return row.title || row.recipe_title || row.id
}

function dietSummary(row) {
  const n = row.note
  if (n) return n
  return `饮食记录 #${row.id}`
}

function workImageCount(row) {
  if (!row.images) return 0
  try { const a = JSON.parse(row.images); return Array.isArray(a) ? a.length : 0 } catch (e) { return 0 }
}

// ---- 拍照打卡（photos 类型）辅助 ----
function mealTypeLabel(k) {
  return ({ breakfast: '早餐', lunch: '午餐', dinner: '晚餐', snack: '加餐' })[k] || k || '-'
}
function photoStatusLabel(k) {
  return ({ pending: '待确认', confirmed: '已确认', corrected: '已修正' })[k] || k || '-'
}
function photoStatusTag(k) {
  return ({ pending: 'warning', confirmed: 'success', corrected: 'primary' })[k] || 'info'
}
function photoSummary(row) {
  if (!row.recognition_json) return '-'
  try {
    const a = JSON.parse(row.recognition_json)
    if (Array.isArray(a)) return a.map(x => x.dishName || x.dish_name).filter(Boolean).join('、') || '-'
  } catch (e) { /* ignore */ }
  return String(row.recognition_json).slice(0, 60)
}

// ---- 菜品营养库（dishes 类型）辅助 ----
function sourceLabel(k) {
  return ({ seed: '种子', ai: 'AI候选', user: '用户' })[k] || k || '-'
}
function sourceTag(k) {
  return ({ seed: 'success', user: 'primary', ai: 'warning' })[k] || 'info'
}
function verifiedLabel(k) {
  return Number(k) === 1 ? '已复核' : '待复核'
}
function dishNutritionSummary(row) {
  const n = row.nutrition_per_serving
  if (!n) return '-' + (row.serving_grams ? ` / ${row.serving_grams}g` : '')
  try {
    const o = JSON.parse(n)
    return `${o.calories ?? '?'} 千卡 / ${row.serving_grams ?? 300}g`
  } catch (e) { /* ignore */ }
  return String(n).slice(0, 40)
}

async function restore(row) {
  const t = typeLabel(activeType.value)
  if (!confirm(`恢复${t}「${rowLabel(row)}」？恢复后将在用户端/APP 重新可见。`)) return
  try {
    await adminAPI.restoreRecycle(activeType.value, row.id)
    alert('已恢复')
    await reload(); await loadStats(); await loadAudit()
  } catch (e) { alert(e?.message || '操作失败') }
}

async function purge(row) {
  const t = typeLabel(activeType.value)
  const extra = activeType.value === 'works' ? '；磁盘图片将一并删除' : ''
  if (!confirm(`彻底删除${t}「${rowLabel(row)}」？将先归档（审计副本）再从主表物理删除${extra}，此操作不可撤销。`)) return
  try {
    await ElMessageBox.prompt(`彻底删除${t}「${rowLabel(row)}」不可撤销，请输入「彻底删除」以确认：`, '二次确认', {
      confirmButtonText: '确认彻底删除',
      cancelButtonText: '取消',
      inputPlaceholder: '彻底删除',
      inputValidator: (v) => v === '彻底删除' || '请输入「彻底删除」',
    })
  } catch (e) { ElMessage.info('已取消'); return }
  try {
    await adminAPI.purgeRecycle(activeType.value, row.id)
    ElMessage.success('已归档并彻底删除')
    await reload(); await loadStats(); await loadAudit()
  } catch (e) { ElMessage.error(e?.message || '操作失败') }
}

async function batchRestore() {
  const ids = selectedRows.value.map(r => r.id)
  if (!ids.length) return
  const t = typeLabel(activeType.value)
  if (!confirm(`批量恢复 ${ids.length} 条${t}记录？`)) return
  try {
    const r = (await adminAPI.batchRestoreRecycle(activeType.value, ids)).data || {}
    alert(`批量恢复完成：成功 ${r.success} 条${r.failed?.length ? '，失败 ' + r.failed.length + ' 条' : ''}`)
    await reload(); await loadStats(); await loadAudit()
  } catch (e) { alert(e?.message || '操作失败') }
}

async function batchPurge() {
  const ids = selectedRows.value.map(r => r.id)
  if (!ids.length) return
  const t = typeLabel(activeType.value)
  const extra = activeType.value === 'works' ? '；作品磁盘图片将一并删除' : ''
  if (!confirm(`批量彻底删除 ${ids.length} 条${t}记录？将先归档（审计副本）再从主表物理删除${extra}，此操作不可撤销。`)) return
  try {
    await ElMessageBox.prompt(`批量彻底删除 ${ids.length} 条${t}记录不可撤销，请输入「彻底删除」以确认：`, '二次确认', {
      confirmButtonText: '确认彻底删除',
      cancelButtonText: '取消',
      inputPlaceholder: '彻底删除',
      inputValidator: (v) => v === '彻底删除' || '请输入「彻底删除」',
    })
  } catch (e) { ElMessage.info('已取消'); return }
  try {
    const r = (await adminAPI.batchPurgeRecycle(activeType.value, ids)).data || {}
    ElMessage.success(`批量彻底删除完成：成功 ${r.success} 条${r.failed?.length ? '，失败 ' + r.failed.length + ' 条' : ''}`)
    await reload(); await loadStats(); await loadAudit()
  } catch (e) { ElMessage.error(e?.message || '操作失败') }
}

function fmt(v) { return v ? String(v).replace('T', ' ').slice(0, 19) : '-' }
</script>

<style scoped>
.recycle-page { padding: 4px 2px; }
.page-head h2 { margin: 0; }
.head-line { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 6px; }
.settings-form { display: flex; flex-direction: column; gap: 14px; }
.set-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.set-row label { width: 96px; font-weight: 600; flex-shrink: 0; }
.set-hint { width: 100%; font-size: 12px; color: #909399; padding-left: 106px; }
.set-row.last-run { color: #606266; font-size: 13px; background: #f5f7fa; border-radius: 6px; padding: 8px 10px; }
.dialog-footer { display: flex; justify-content: flex-end; align-items: center; gap: 16px; }
.btn-filter.primary { background: #409eff; color: #fff; }
.tip { color: var(--text-secondary); font-size: 13px; margin: 0 0 14px; }
.stat-row { display: flex; gap: 10px; margin-bottom: 14px; flex-wrap: wrap; }
.stat-chip { border: 1px solid #dcdfe6; border-radius: 14px; padding: 5px 14px; font-size: 13px; cursor: pointer; background: #fff; }
.stat-chip.active { border-color: var(--primary, #409eff); color: var(--primary, #409eff); background: #ecf5ff; }
.stat-chip b { margin-left: 4px; }
.filter-bar { display: flex; gap: 10px; margin-bottom: 14px; flex-wrap: wrap; }
.batch-bar { display: flex; gap: 10px; align-items: center; margin-bottom: 14px; flex-wrap: wrap; }
.sel-tip { color: var(--text-secondary); font-size: 13px; }
.btn-filter:disabled { opacity: 0.5; cursor: not-allowed; }
.btn-filter.danger { background: #f56c6c; }
.filter-input { border: 1px solid #dcdfe6; border-radius: 6px; padding: 7px 10px; font-size: 14px; outline: none; }
.filter-input:focus { border-color: var(--primary, #409eff); }
.filter-input.narrow { width: 130px; }
.btn-filter { border: none; background: var(--primary, #409eff); color: #fff; border-radius: 6px; padding: 7px 16px; cursor: pointer; font-size: 14px; }
.btn-filter.ghost { background: #fff; color: #606266; border: 1px solid #dcdfe6; }
.btn-filter:hover { opacity: 0.9; }
.mt12 { margin-top: 12px; }
</style>
