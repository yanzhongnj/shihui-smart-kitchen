<template>
  <div>
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
    </div>

    <el-table v-loading="loading" :data="items" border stripe style="width: 100%" empty-text="暂无企业">
      <el-table-column prop="name" label="企业" min-width="180">
        <template #default="{ row }"><b>{{ row.name }}</b></template>
      </el-table-column>
      <el-table-column prop="code" label="编码" width="140">
        <template #default="{ row }"><span class="mono">{{ row.code }}</span></template>
      </el-table-column>
      <el-table-column label="企业主" min-width="110">
        <template #default="{ row }">{{ row.ownerName || '-' }}</template>
      </el-table-column>
      <el-table-column label="套餐" width="100">
        <template #default="{ row }">{{ planLabel(row.planType) }}</template>
      </el-table-column>
      <el-table-column label="成员" width="80">
        <template #default="{ row }">{{ row.memberCount ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="视频" width="80">
        <template #default="{ row }">{{ videoCounts[row.id] ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="orgStatusTag(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="创建时间" width="165">
        <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="250" fixed="right">
        <template #default="{ row }">
          <span class="ops">
            <select class="select" :value="row.status" @change="setStatus(row, $event)">
              <option :value="1">正常</option>
              <option :value="0">禁用</option>
              <option :value="2">欠费</option>
            </select>
            <select class="select" :value="row.planType" @change="setPlan(row, $event)">
              <option v-for="p in plans" :key="p.code" :value="p.code">{{ p.name }}</option>
            </select>
            <el-button link type="primary" size="small" @click="viewStats(row)">详情</el-button>
          </span>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination v-if="pages > 1" background layout="total, sizes, prev, pager, next"
      :total="total" :current-page="query.page + 1" :page-size="size"
      :page-sizes="[10, 20, 50]"
      @current-change="onPageChange" @size-change="onSizeChange" class="ep-pagination" />
    <div v-else-if="!loading && items.length > 0" class="page-total center">共 {{ total }} 条</div>

    <div v-if="error" class="error-text">{{ error }}</div>

    <!-- 企业详情弹窗 -->
    <div v-if="detail" class="modal-mask" @click.self="detail = null">
      <div class="modal wide">
        <h3>🏢 {{ detail.name }}（{{ detail.code }}）</h3>
        <div class="stat-grid">
          <div class="stat"><b>{{ stats.videoTotal ?? '-' }}</b><span>视频</span></div>
          <div class="stat"><b>{{ stats.videoCompleted ?? '-' }}</b><span>已完成</span></div>
          <div class="stat"><b>{{ stats.taskTotal ?? '-' }}</b><span>任务</span></div>
          <div class="stat"><b>{{ stats.taskRunning ?? '-' }}</b><span>处理中</span></div>
          <div class="stat"><b>{{ stats.memberCount ?? '-' }}</b><span>成员</span></div>
          <div class="stat"><b>{{ fmtBytes(stats.storageBytes) }}</b><span>存储</span></div>
          <div class="stat"><b>{{ stats.recipesCount ?? '-' }}</b><span>菜谱</span></div>
          <div class="stat"><b>{{ stats.usage?.totalGb ?? '-' }}</b><span>流量(GB)</span></div>
        </div>

        <!-- B3：账单管理 -->
        <h4 class="sub">🧾 账单</h4>
        <el-table :data="invoices" border stripe size="small" style="width: 100%" empty-text="暂无账单">
          <el-table-column prop="createdAt" label="时间" width="165">
            <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column prop="description" label="说明" min-width="180" />
          <el-table-column label="金额" width="100">
            <template #default="{ row }">¥{{ row.amount }}</template>
          </el-table-column>
          <el-table-column label="状态" width="140">
            <template #default="{ row }">
              <select class="select" :value="row.status" @change="setInvoiceStatus(row, $event)">
                <option value="pending">待支付</option>
                <option value="paid">已支付</option>
                <option value="overdue">逾期</option>
                <option value="canceled">已取消</option>
              </select>
            </template>
          </el-table-column>
        </el-table>

        <!-- A1：品牌配置 -->
        <h4 class="sub">🎨 品牌配置（白标）</h4>
        <div class="brand-grid">
          <input v-model="brandForm.appName" class="input" placeholder="品牌名称" />
          <input v-model="brandForm.splashText" class="input" placeholder="启动副标题" />
          <input v-model="brandForm.logoUrl" class="input" placeholder="Logo URL" />
          <input v-model="brandForm.aboutText" class="input" placeholder="关于文案" />
        </div>
        <div class="modal-actions">
          <button class="btn primary" @click="saveBrand">保存品牌</button>
          <button class="btn" @click="detail = null">关闭</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { adminAPI } from '@/api/admin'

const items = ref([])
const loading = ref(false)
const total = ref(0)
const size = ref(10)
const plans = ref([])
const videoCounts = ref({})
const detail = ref(null)
const stats = ref({})
const error = ref('')
const invoices = ref([])
const brandForm = ref({ appName: '', splashText: '', logoUrl: '', aboutText: '' })
const brandMsg = ref('')

const query = reactive({ keyword: '', status: '', planType: '', page: 0 })

const pages = computed(() => Math.max(1, Math.ceil(total.value / size.value)))

async function loadPlans() {
  const r = await adminAPI.getOrgPlans()
  plans.value = r.data || []
}

async function reload() {
  error.value = ''
  loading.value = true
  try {
    const r = await adminAPI.getOrgs({ page: query.page, size: size.value, keyword: query.keyword, status: query.status, planType: query.planType })
    items.value = r.data?.items || []
    total.value = r.data?.total || 0
    // 聚合各企业视频数（详情接口）
    for (const o of items.value) {
      adminAPI.getOrgStats(o.id).then(res => {
        videoCounts.value[o.id] = res.data?.videoTotal ?? '-'
      }).catch(() => { videoCounts.value[o.id] = '-' })
    }
  } catch (e) {
    error.value = e.message || '加载失败'
  } finally {
    loading.value = false
  }
}

function onPageChange(p) { query.page = p - 1; reload() }
function onSizeChange(s) { size.value = s; query.page = 0; reload() }

async function setStatus(o, ev) {
  try {
    await adminAPI.setOrgStatus(o.id, Number(ev.target.value))
    o.status = Number(ev.target.value)
  } catch (e) { error.value = e.message || '操作失败' }
}

async function setPlan(o, ev) {
  try {
    await adminAPI.setOrgPlan(o.id, ev.target.value)
    o.planType = ev.target.value
  } catch (e) { error.value = e.message || '操作失败' }
}

async function viewStats(o) {
  detail.value = o
  const r = await adminAPI.getOrgStats(o.id)
  stats.value = r.data || {}
  loadInvoices(o.id)
  loadBrand(o.id)
}

async function loadInvoices(orgId) {
  const r = await adminAPI.getInvoices({ orgId, page: 0, size: 50 })
  invoices.value = r.data?.items || []
}

async function setInvoiceStatus(inv, ev) {
  try {
    await adminAPI.setInvoiceStatus(inv.id, ev.target.value)
    inv.status = ev.target.value
  } catch (e) { error.value = e.message || '操作失败' }
}

async function loadBrand(orgId) {
  try {
    const { request } = await import('@/api/request')
    const r = await request.get(`/brand/${orgId}`)
    const b = r.data || {}
    brandForm.value = { appName: b.appName || '', splashText: b.splashText || '', logoUrl: b.logoUrl || '', aboutText: b.aboutText || '' }
  } catch { /* 忽略 */ }
}

async function saveBrand() {
  try {
    await adminAPI.setOrgBrand(detail.value.id, { ...brandForm.value })
    brandMsg.value = '已保存'
    setTimeout(() => { brandMsg.value = '' }, 2000)
  } catch (e) { error.value = e.message || '保存失败' }
}

function planLabel(p) { return ({ free: '免费版', basic: '基础版', pro: '专业版', enterprise: '企业版' })[p] || p }
function statusLabel(s) { return ({ 0: '禁用', 1: '正常', 2: '欠费停服' })[s] || s }
function orgStatusTag(s) {
  if (s === 1) return 'success'
  if (s === 0) return 'info'
  return 'warning'
}
function fmtTime(t) { return t ? String(t).replace('T', ' ').slice(0, 16) : '-' }
function fmtBytes(b) {
  if (b == null) return '-'
  const n = Number(b)
  if (n >= 1024 * 1024 * 1024) return (n / 1024 / 1024 / 1024).toFixed(2) + ' GB'
  return (n / 1024 / 1024).toFixed(1) + ' MB'
}

onMounted(() => { loadPlans(); reload() })
</script>

<style scoped>
.filter-bar { display: flex; gap: 10px; margin-bottom: 14px; flex-wrap: wrap; }
.input, .select { padding: 8px 12px; border: 1px solid var(--border-color, #e5e7eb); border-radius: 6px; font-size: 14px; }
.btn { padding: 8px 14px; border: 1px solid var(--border-color, #e5e7eb); border-radius: 6px; background: #fff; cursor: pointer; font-size: 14px; }
.btn.primary { background: var(--primary, #409eff); border-color: var(--primary, #409eff); color: #fff; }
.table { width: 100%; border-collapse: collapse; background: #fff; border-radius: 10px; overflow: hidden; }
.table th, .table td { text-align: left; padding: 10px 12px; border-bottom: 1px solid var(--border-color, #f0f0f0); font-size: 14px; }
.table th { background: #f8fafc; color: var(--text-secondary, #6b7280); font-weight: 500; }
.mono { font-family: monospace; font-size: 13px; }
.ops { display: flex; gap: 6px; align-items: center; }
.error-text { color: #e5484d; margin-top: 10px; font-size: 13px; }
.muted { color: var(--text-secondary, #9ca3af); }
.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,0.4); display: flex; align-items: center; justify-content: center; z-index: 1000; }
.modal { background: #fff; border-radius: 10px; padding: 20px; width: 560px; }
.modal.wide { width: 720px; }
.modal h3 { margin: 0 0 14px; }
.sub { margin: 16px 0 8px; }
.brand-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 8px; }
.brand-grid .input { width: 100%; box-sizing: border-box; }
.stat-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; }
.stat { background: #f8fafc; border-radius: 8px; padding: 12px; text-align: center; }
.stat b { display: block; font-size: 18px; }
.stat span { color: var(--text-secondary, #6b7280); font-size: 12px; }
.modal-actions { display: flex; justify-content: flex-end; margin-top: 16px; }
.ep-pagination { margin-top: 16px; justify-content: center; }
</style>
