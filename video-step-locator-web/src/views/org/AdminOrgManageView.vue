<template>
  <div class="admin-org-page">
    <!-- 筛选栏 -->
    <div class="filter-bar">
      <input v-model="query.keyword" class="input" placeholder="企业名称" @keyup.enter="reload" />
      <select v-model="query.status" class="select" @change="reload">
        <option value="">全部状态</option>
        <option value="1">正常</option>
        <option value="0">禁用</option>
        <option value="2">欠费停服</option>
      </select>
      <select v-model="query.planType" class="select" @change="reload">
        <option value="">全部套餐</option>
        <option v-for="p in plans" :key="p.code" :value="p.code">{{ p.name }}</option>
      </select>
      <button class="btn primary" @click="reload">查询</button>
      <span v-if="!loading && orgs.length" class="total-tip">共 {{ total }} 个企业</span>
    </div>

    <div v-if="loading" class="loading">加载中...</div>
    <div v-else-if="!orgs.length" class="empty">暂无企业</div>

    <!-- 折叠式企业卡片（手风琴，一次展开一个） -->
    <div v-else class="org-acc">
      <div v-for="o in orgs" :key="o.id" class="org-card" :class="{ open: expandedId === o.id }">
        <!-- 卡头：点击折叠 -->
        <div class="org-head" @click="toggle(o)">
          <span class="o-name" :title="o.name">{{ o.name }}</span>
          <span class="o-mono" :title="o.code">{{ o.code }}</span>
          <span class="o-owner">主：{{ o.ownerName || '-' }}</span>
          <el-tag :type="orgStatusTag(o.status)" size="small">{{ statusLabel(o.status) }}</el-tag>
          <span class="o-plan">{{ planLabel(o.planType) }}</span>
          <span class="o-meta">成员 {{ o.memberCount ?? '-' }} · 视频 {{ videoCounts[o.id] ?? '-' }}</span>
          <span class="o-arrow">{{ expandedId === o.id ? '▴' : '▾' }}</span>
        </div>
        <!-- 卡体：折叠内容 -->
        <div v-show="expandedId === o.id" class="org-body">
          <div class="quick-row">
            <select class="select" :value="o.status" @change="setStatus(o, $event)">
              <option :value="1">正常</option>
              <option :value="0">禁用</option>
              <option :value="2">欠费</option>
            </select>
            <select class="select" :value="o.planType" @change="setPlan(o, $event)">
              <option v-for="p in plans" :key="p.code" :value="p.code">{{ p.name }}</option>
            </select>
          </div>

          <!-- 用量统计 -->
          <h4 class="sub">📊 用量统计</h4>
          <div class="stat-grid">
            <div class="stat"><b>{{ d(o).stats.videoTotal ?? '-' }}</b><span>视频</span></div>
            <div class="stat"><b>{{ d(o).stats.videoCompleted ?? '-' }}</b><span>已完成</span></div>
            <div class="stat"><b>{{ d(o).stats.taskTotal ?? '-' }}</b><span>任务</span></div>
            <div class="stat"><b>{{ d(o).stats.taskRunning ?? '-' }}</b><span>处理中</span></div>
            <div class="stat"><b>{{ d(o).stats.memberCount ?? '-' }}</b><span>成员</span></div>
            <div class="stat"><b>{{ fmtBytes(d(o).stats.storageBytes) }}</b><span>存储</span></div>
            <div class="stat"><b>{{ d(o).stats.recipesCount ?? '-' }}</b><span>菜谱</span></div>
            <div class="stat"><b>{{ d(o).stats.usage?.totalGb ?? '-' }}</b><span>流量(GB)</span></div>
          </div>

          <!-- 账单 -->
          <h4 class="sub">🧾 账单</h4>
          <el-table :data="d(o).invoices" border stripe size="small" style="width: 100%" empty-text="暂无账单">
            <el-table-column prop="createdAt" label="时间" width="165">
              <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column prop="description" label="说明" min-width="160" />
            <el-table-column label="金额" width="100">
              <template #default="{ row }">¥{{ row.amount }}</template>
            </el-table-column>
            <el-table-column label="状态" width="140">
              <template #default="{ row }">
                <select class="select" :value="row.status" @change="setInvoiceStatus(o, row, $event)">
                  <option value="pending">待支付</option>
                  <option value="paid">已支付</option>
                  <option value="overdue">逾期</option>
                  <option value="canceled">已取消</option>
                </select>
              </template>
            </el-table-column>
          </el-table>

          <!-- 品牌配置 -->
          <h4 class="sub">🎨 品牌配置（白标）</h4>
          <div class="brand-grid">
            <input v-model="brandForms[o.id].appName" class="input" placeholder="品牌名称" />
            <input v-model="brandForms[o.id].splashText" class="input" placeholder="启动副标题" />
            <input v-model="brandForms[o.id].logoUrl" class="input" placeholder="Logo URL" />
            <input v-model="brandForms[o.id].aboutText" class="input" placeholder="关于文案" />
          </div>
          <div class="brand-actions">
            <button class="btn primary" @click="saveBrand(o)">保存品牌</button>
            <span v-if="brandMsg[o.id]" class="brand-msg">{{ brandMsg[o.id] }}</span>
          </div>
        </div>
      </div>
    </div>

    <el-pagination v-if="pages > 1" background layout="total, prev, pager, next"
      :total="total" :current-page="query.page + 1" :page-size="size"
      @current-change="onPageChange" class="ep-pagination" />

    <div v-if="error" class="error-text">{{ error }}</div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { adminAPI } from '@/api/admin'

const orgs = ref([])
const loading = ref(false)
const total = ref(0)
const size = ref(20)
const plans = ref([])
const error = ref('')
const videoCounts = ref({})
const expandedId = ref(null)
const detailMap = ref({})
const brandForms = ref({})
const brandMsg = ref({})

const query = reactive({ keyword: '', status: '', planType: '', page: 0 })
const pages = computed(() => Math.max(1, Math.ceil(total.value / size.value)))

function d(o) {
  if (!detailMap.value[o.id]) detailMap.value[o.id] = { stats: {}, invoices: [], loaded: false }
  return detailMap.value[o.id]
}

async function loadPlans() {
  try { const r = await adminAPI.getOrgPlans(); plans.value = r.data || [] } catch { plans.value = [] }
}

async function reload() {
  error.value = ''
  loading.value = true
  try {
    const r = await adminAPI.getOrgs({ page: query.page, size: size.value, keyword: query.keyword, status: query.status, planType: query.planType })
    orgs.value = r.data?.items || []
    total.value = r.data?.total || 0
    // 为每个企业初始化 brandForms（避免模板访问 undefined）
    orgs.value.forEach(o => {
      if (!brandForms.value[o.id]) {
        brandForms.value[o.id] = { appName: o.name || '', splashText: '', logoUrl: '', aboutText: '' }
      }
    })
    // 轻量统计视频数（列表即拉，不阻塞）
    orgs.value.forEach(o => {
      adminAPI.getOrgStats(o.id).then(res => {
        videoCounts.value[o.id] = res.data?.videoTotal ?? '-'
      }).catch(() => { videoCounts.value[o.id] = '-' })
    })
  } catch (e) {
    error.value = e.message || '加载失败'
  } finally {
    loading.value = false
  }
}

function onPageChange(p) { query.page = p - 1; reload() }

async function toggle(o) {
  if (expandedId.value === o.id) { expandedId.value = null; return }
  expandedId.value = o.id
  const det = d(o)
  if (!det.loaded) {
    det.loaded = true
    adminAPI.getOrgStats(o.id).then(res => { det.stats = res.data || {} }).catch(() => {})
    adminAPI.getInvoices({ orgId: o.id, page: 0, size: 50 }).then(res => {
      det.invoices = res.data?.items || []
    }).catch(() => {})
    // 展开时拉取已保存的品牌配置（覆盖默认）
    try {
      const { request } = await import('@/api/request')
      const r = await request.get(`/brand/${o.id}`)
      const b = r.data || {}
      brandForms.value[o.id] = { appName: b.appName || o.name || '', splashText: b.splashText || '', logoUrl: b.logoUrl || '', aboutText: b.aboutText || '' }
    } catch { /* 忽略，保留默认 */ }
  }
}

async function setStatus(o, ev) {
  try { await adminAPI.setOrgStatus(o.id, Number(ev.target.value)); o.status = Number(ev.target.value) }
  catch (e) { error.value = e.message || '操作失败' }
}
async function setPlan(o, ev) {
  try { await adminAPI.setOrgPlan(o.id, ev.target.value); o.planType = ev.target.value }
  catch (e) { error.value = e.message || '操作失败' }
}
async function setInvoiceStatus(o, inv, ev) {
  try { await adminAPI.setInvoiceStatus(inv.id, ev.target.value); inv.status = ev.target.value }
  catch (e) { error.value = e.message || '操作失败' }
}
async function saveBrand(o) {
  brandMsg.value[o.id] = ''
  try {
    await adminAPI.setOrgBrand(o.id, { ...brandForms.value[o.id] })
    brandMsg.value[o.id] = '已保存'
    setTimeout(() => { brandMsg.value[o.id] = '' }, 2000)
  } catch (e) { brandMsg.value[o.id] = e.message || '保存失败' }
}

function planLabel(p) { return ({ free: '免费版', basic: '基础版', pro: '专业版', enterprise: '企业版' })[p] || p }
function statusLabel(s) { return ({ 0: '禁用', 1: '正常', 2: '欠费停服' })[s] || s }
function orgStatusTag(s) { return s === 1 ? 'success' : s === 0 ? 'info' : 'warning' }
function fmtTime(t) { return t ? String(t).replace('T', ' ').slice(0, 16) : '-' }
function fmtBytes(b) {
  if (b == null) return '-'
  const n = Number(b)
  if (n >= 1024 * 1024 * 1024) return (n / 1024 / 1024 / 1024).toFixed(2) + ' GB'
  if (n >= 1024 * 1024) return (n / 1024 / 1024).toFixed(1) + ' MB'
  return (n / 1024).toFixed(0) + ' KB'
}

onMounted(() => { loadPlans(); reload() })
</script>

<style scoped>
.admin-org-page { display: flex; flex-direction: column; gap: 14px; }
.filter-bar { display: flex; gap: 10px; flex-wrap: wrap; align-items: center; }
.input, .select { padding: 8px 12px; border: 1px solid var(--border-color, #e5e7eb); border-radius: 6px; font-size: 14px; }
.input { flex: 1; min-width: 200px; }
.btn { padding: 8px 14px; border: 1px solid var(--border-color, #e5e7eb); border-radius: 6px; background: #fff; cursor: pointer; font-size: 14px; }
.btn.primary { background: var(--primary, #409eff); border-color: var(--primary, #409eff); color: #fff; }
.total-tip { color: var(--text-secondary, #6b7280); font-size: 13px; }
.loading, .empty { color: var(--text-secondary, #6b7280); padding: 24px; text-align: center; }
.org-acc { display: flex; flex-direction: column; gap: 10px; }
.org-card { border: 1px solid var(--border-color, #e5e7eb); border-radius: 10px; background: #fff; overflow: hidden; }
.org-card.open { border-color: var(--primary, #409eff); box-shadow: 0 2px 8px rgba(0,0,0,0.06); }
.org-head { display: flex; align-items: center; gap: 12px; padding: 12px 16px; cursor: pointer; flex-wrap: wrap; }
.org-head:hover { background: #fafafa; }
.o-name { font-weight: 600; font-size: 15px; }
.o-mono { font-family: monospace; font-size: 12px; color: var(--text-secondary, #9ca3af); }
.o-owner { font-size: 13px; color: var(--text-secondary, #6b7280); }
.o-plan { font-size: 12px; color: var(--primary, #409eff); }
.o-meta { font-size: 13px; color: var(--text-secondary, #6b7280); margin-left: auto; }
.o-arrow { color: var(--text-secondary, #9ca3af); font-size: 14px; }
.org-body { padding: 6px 16px 16px; border-top: 1px solid var(--border-color, #f0f0f0); }
.quick-row { display: flex; gap: 8px; padding: 10px 0; }
.sub { margin: 14px 0 8px; font-size: 14px; }
.stat-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(110px, 1fr)); gap: 10px; }
.stat { background: #f8fafc; border-radius: 8px; padding: 10px; text-align: center; }
.stat b { display: block; font-size: 18px; }
.stat span { color: var(--text-secondary, #6b7280); font-size: 12px; }
.brand-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 8px; }
.brand-actions { display: flex; align-items: center; gap: 10px; margin-top: 10px; }
.brand-msg { color: var(--primary, #409eff); font-size: 13px; }
.error-text { color: #e5484d; font-size: 13px; }
.mono { font-family: monospace; }
</style>
