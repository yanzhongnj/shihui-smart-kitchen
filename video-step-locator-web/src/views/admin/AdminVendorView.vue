<template>
  <div>
    <!-- ===== 厂商总览（表格式，所有厂商） ===== -->
    <template v-if="!detailMode">
      <div class="filter-bar">
        <input v-model="keyword" class="input" placeholder="搜索企业名称/编码…" @keyup.enter="applyFilter" />
        <button class="btn primary" @click="applyFilter">查询</button>
      </div>
      <el-table v-loading="loading" :data="filteredVendors" border stripe style="width: 100%" empty-text="暂无厂商企业">
        <el-table-column label="企业" min-width="200">
          <template #default="{ row }">
            <b>{{ row.name }}</b>
            <span class="muted mono" style="margin-left:6px">{{ row.code }}</span>
          </template>
        </el-table-column>
        <el-table-column label="套餐" width="100">
          <template #default="{ row }">{{ planLabel(row.planType) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="96">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : row.status === 2 ? 'danger' : 'info'" size="small">
              {{ row.status === 1 ? '正常' : row.status === 2 ? '欠费停服' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="contractCount" label="合同" width="64" align="center" />
        <el-table-column prop="skuCount" label="SKU" width="64" align="center" />
        <el-table-column label="绑定码(已激活/总)" width="132" align="center">
          <template #default="{ row }">{{ row.bindingBound ?? 0 }}/{{ row.bindingTotal ?? 0 }}</template>
        </el-table-column>
        <el-table-column prop="licenseCount" label="内容授权" width="88" align="center" />
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="enterDetail(row)">管理</el-button>
          </template>
        </el-table-column>
      </el-table>
    </template>

    <!-- ===== 单厂商详情管理 ===== -->
    <template v-if="detailMode">
      <div class="filter-bar">
        <button class="btn" @click="backToList">← 返回厂商列表</button>
        <h3 style="margin:0">{{ activeOrg?.name }} <span class="muted mono">{{ activeOrg?.code }}</span></h3>
        <button class="btn" @click="showCreateSku = true">＋ 新建 SKU</button>
        <button class="btn" @click="showCreateContract = true">＋ 新建合同</button>
        <button class="btn" @click="showAddLicense = true">＋ 内容授权</button>
      </div>
      <!-- 激活统计 -->
      <div class="stat-grid">
        <div class="stat"><b>{{ stats.total ?? '-' }}</b><span>绑定码总数</span></div>
        <div class="stat"><b>{{ stats.bound ?? '-' }}</b><span>已激活</span></div>
        <div class="stat"><b>{{ stats.unbound ?? '-' }}</b><span>未激活</span></div>
        <div class="stat"><b>{{ stats.skuCount ?? '-' }}</b><span>SKU 数</span></div>
      </div>

      <!-- 标签 -->
      <div class="tabs">
        <button v-for="t in tabs" :key="t.key" class="tab" :class="{ active: tab === t.key }"
                @click="tab = t.key">{{ t.label }}</button>
      </div>

      <!-- 合同 -->
      <div v-if="tab === 'contracts'" class="card">
        <el-table :data="contracts" border stripe size="small" style="width: 100%" empty-text="暂无合同">
          <el-table-column prop="contractNo" label="合同号" width="190">
            <template #default="{ row }"><span class="mono">{{ row.contractNo }}</span></template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="110">
            <template #default="{ row }">{{ contractStatusText(row.status) }}</template>
          </el-table-column>
          <el-table-column prop="deviceQuota" label="设备配额" width="100" />
          <el-table-column prop="startedAt" label="开始" width="165">
            <template #default="{ row }">{{ fmtTime(row.startedAt) }}</template>
          </el-table-column>
          <el-table-column prop="expiredAt" label="到期" width="165">
            <template #default="{ row }">{{ fmtTime(row.expiredAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="200">
            <template #default="{ row }">
              <select class="select" :value="row.status" @change="setContractStatus(row, $event)">
                <option value="active">生效中</option>
                <option value="expired">已到期</option>
                <option value="suspended">已停用</option>
              </select>
              <el-button link type="danger" size="small" @click="deleteContract(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <!-- SKU -->
      <div v-if="tab === 'skus'" class="card">
        <el-table :data="skus" border stripe size="small" style="width: 100%" empty-text="暂无 SKU">
          <el-table-column prop="sku" label="型号" min-width="150" />
          <el-table-column prop="deviceType" label="设备类型" width="120" />
          <el-table-column prop="defaultRecipeGroup" label="默认分组" min-width="150">
            <template #default="{ row }">{{ row.defaultRecipeGroup || '-' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="190">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="genCodes(row)">生成绑定码</el-button>
              <el-button link type="danger" size="small" @click="deleteSku(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="bindCodes.length" class="codes-box">
          <b>已生成绑定码（{{ bindCodes.length }}）：</b>
          <code class="mono">{{ bindCodes.join(' , ') }}</code>
        </div>
      </div>

      <!-- 绑定码 -->
      <div v-if="tab === 'bindings'" class="card">
        <el-table :data="bindings" border stripe size="small" style="width: 100%" empty-text="暂无绑定码">
          <el-table-column prop="bindCode" label="激活码" width="200">
            <template #default="{ row }"><span class="mono">{{ row.bindCode }}</span></template>
          </el-table-column>
          <el-table-column prop="sku" label="型号" min-width="130">
            <template #default="{ row }">{{ row.sku || '-' }}</template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="110">
            <template #default="{ row }">{{ bindStatusText(row.status) }}</template>
          </el-table-column>
          <el-table-column prop="username" label="绑定用户" min-width="120">
            <template #default="{ row }">{{ row.username || '-' }}</template>
          </el-table-column>
          <el-table-column prop="boundAt" label="激活时间" width="165">
            <template #default="{ row }">{{ fmtTime(row.boundAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="80" fixed="right">
            <template #default="{ row }">
              <el-button link type="danger" size="small" :disabled="row.status === 'bound'" @click="deleteBinding(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination v-if="bindPages > 1" background layout="total, sizes, prev, pager, next"
          :total="bindTotal" :current-page="bindPage + 1" :page-size="bindSize"
          :page-sizes="[10, 20, 50]"
          @current-change="onBindPageChange" @size-change="onBindSizeChange" class="ep-pagination" />
        <div v-else-if="bindings.length > 0" class="page-total center">共 {{ bindTotal }} 条</div>
      </div>

      <!-- 内容授权 -->
      <div v-if="tab === 'licenses'" class="card">
        <el-table :data="licenses" border stripe size="small" style="width: 100%" empty-text="暂无内容授权">
          <el-table-column label="菜谱分组" min-width="180">
            <template #default="{ row }">{{ row.recipeGroup || 'AI 创作单品' }}</template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="110">
            <template #default="{ row }">{{ licenseStatusText(row.status) }}</template>
          </el-table-column>
          <el-table-column prop="licensedAt" label="授权时间" width="165">
            <template #default="{ row }">{{ fmtTime(row.licensedAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="200">
            <template #default="{ row }">
              <select class="select" :value="row.status" @change="setLicenseStatus(row, $event)">
                <option value="active">已上架</option>
                <option value="disabled">已下架</option>
              </select>
              <el-button link type="danger" size="small" @click="deleteLicense(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </template>

    <div v-if="error" class="error-text">{{ error }}</div>

    <!-- 新建合同 -->
    <div v-if="showCreateContract" class="modal-mask" @click.self="showCreateContract = false">
      <div class="modal">
        <h3>新建厂商合同</h3>
        <input v-model="contractForm.contractNo" class="input" placeholder="合同号（留空自动生成）" />
        <input v-model.number="contractForm.deviceQuota" class="input" type="number" placeholder="设备激活配额" />
        <div class="modal-actions">
          <button class="btn" @click="showCreateContract = false">取消</button>
          <button class="btn primary" @click="createContract">创建</button>
        </div>
      </div>
    </div>

    <!-- 新建 SKU -->
    <div v-if="showCreateSku" class="modal-mask" @click.self="showCreateSku = false">
      <div class="modal">
        <h3>新建设备 SKU</h3>
        <input v-model="skuForm.sku" class="input" placeholder="型号/货号，如 SH-OVEN-01" />
        <select v-model="skuForm.deviceType" class="select">
          <option value="oven">oven 烤箱</option><option value="steamer">steamer 蒸箱</option>
          <option value="cooktop">cooktop 集成灶</option><option value="airfryer">airfryer 空气炸锅</option>
        </select>
        <input v-model="skuForm.defaultGroup" class="input" placeholder="默认菜谱分组（可选）" />
        <div class="modal-actions">
          <button class="btn" @click="showCreateSku = false">取消</button>
          <button class="btn primary" @click="createSku">创建</button>
        </div>
      </div>
    </div>

    <!-- 内容授权 -->
    <div v-if="showAddLicense" class="modal-mask" @click.self="showAddLicense = false">
      <div class="modal">
        <h3>新增内容授权</h3>
        <input v-model="licenseGroup" class="input" placeholder="菜谱分组，如 oven-recipes" />
        <div class="modal-actions">
          <button class="btn" @click="showAddLicense = false">取消</button>
          <button class="btn primary" @click="addLicense">授权</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessageBox } from 'element-plus'
import { adminAPI } from '@/api/admin'

const vendors = ref([])
const keyword = ref('')
const loading = ref(false)
const detailMode = ref(false)
const activeOrg = ref(null)
const tab = ref('contracts')
const tabs = [
  { key: 'contracts', label: '合同' },
  { key: 'skus', label: 'SKU' },
  { key: 'bindings', label: '绑定码' },
  { key: 'licenses', label: '内容授权' },
]
const stats = ref({})
const contracts = ref([])
const skus = ref([])
const bindings = ref([])
const bindTotal = ref(0)
const bindPage = ref(0)
const bindSize = ref(10)
const licenses = ref([])
const bindCodes = ref([])
const error = ref('')

const showCreateContract = ref(false)
const contractForm = ref({ contractNo: '', deviceQuota: 100 })
const showCreateSku = ref(false)
const skuForm = ref({ sku: '', deviceType: 'oven', defaultGroup: '' })
const showAddLicense = ref(false)
const licenseGroup = ref('')

const bindPages = computed(() => Math.max(1, Math.ceil(bindTotal.value / bindSize.value)))
const filteredVendors = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  if (!k) return vendors.value
  return vendors.value.filter(v => (v.name || '').toLowerCase().includes(k) || (v.code || '').toLowerCase().includes(k))
})

function planLabel(p) { return ({ free: '免费', basic: '基础', pro: '专业', enterprise: '企业' })[p] || p || '-' }

function bindStatusText(s) { return ({ bound: '已激活', unbound: '未激活', unbound_released: '已解绑' })[s] || s || '-' }

function licenseStatusText(s) { return ({ active: '已上架', disabled: '已下架' })[s] || s || '-' }

function contractStatusText(s) { return ({ active: '生效中', expired: '已到期', suspended: '已停用' })[s] || s || '-' }

function onBindPageChange(p) { loadBindings(p - 1) }
function onBindSizeChange(s) { bindSize.value = s; loadBindings(0) }

async function loadVendors() {
  loading.value = true
  try { vendors.value = (await adminAPI.getVendors()).data || [] }
  catch (e) { error.value = e.message || '加载失败' } finally { loading.value = false }
}
function applyFilter() { /* filteredVendors 已实时过滤 */ }

function enterDetail(v) {
  activeOrg.value = v
  detailMode.value = true
  tab.value = 'contracts'
  bindCodes.value = []
  reloadAll()
}
function backToList() {
  detailMode.value = false
  activeOrg.value = null
  bindCodes.value = []
  contracts.value = []; skus.value = []; bindings.value = []; licenses.value = []
  loadVendors() // 刷新厂商总览计数（生成绑定码/新建合同等后）
}

function reloadAll() { loadStats(); loadContracts(); loadSkus(); loadBindings(0); loadLicenses() }

async function loadStats() {
  const r = await adminAPI.getVendorActivationStats({ orgId: activeOrg.value?.id })
  stats.value = r.data || {}
}
async function loadContracts() {
  const r = await adminAPI.getVendorContracts({ orgId: activeOrg.value?.id })
  contracts.value = r.data || []
}
async function loadSkus() {
  const r = await adminAPI.getVendorSkus({ orgId: activeOrg.value?.id })
  skus.value = r.data || []
}
async function loadBindings(p) {
  bindPage.value = p
  const r = await adminAPI.getVendorBindings({ orgId: activeOrg.value?.id, page: p, size: bindSize.value })
  bindings.value = r.data?.items || []
  bindTotal.value = r.data?.total || 0
}
async function loadLicenses() {
  const r = await adminAPI.getVendorLicenses({ orgId: activeOrg.value?.id })
  licenses.value = r.data || []
}

async function createContract() {
  try {
    await adminAPI.createVendorContract({ orgId: activeOrg.value?.id, contractNo: contractForm.value.contractNo, deviceQuota: contractForm.value.deviceQuota })
    showCreateContract.value = false
    contractForm.value = { contractNo: '', deviceQuota: 100 }
    loadContracts()
  } catch (e) { error.value = e.message || '创建失败' }
}
async function setContractStatus(c, ev) {
  try { await adminAPI.setVendorContractStatus(c.id, ev.target.value); c.status = ev.target.value }
  catch (e) { error.value = e.message || '操作失败' }
}
async function createSku() {
  try {
    await adminAPI.createVendorSku({ orgId: activeOrg.value?.id, sku: skuForm.value.sku, deviceType: skuForm.value.deviceType, defaultGroup: skuForm.value.defaultGroup })
    showCreateSku.value = false
    skuForm.value = { sku: '', deviceType: 'oven', defaultGroup: '' }
    loadSkus()
  } catch (e) { error.value = e.message || '创建失败' }
}
async function genCodes(s) {
  let n
  try {
    const r = await ElMessageBox.prompt(`为 ${s.sku} 生成绑定码数量（1-1000）：`, '生成绑定码', {
      confirmButtonText: '生成',
      cancelButtonText: '取消',
      inputValue: '10',
      inputValidator: (v) => {
        const num = Number(v)
        return (v && Number.isInteger(num) && num >= 1 && num <= 1000) || '请输入 1-1000 的整数'
      },
    })
    n = Number(r.value)
  } catch (e) { return } // 取消
  if (!Number.isInteger(n) || n < 1 || n > 1000) { error.value = '请输入 1-1000 的整数'; return }
  try {
    const r = await adminAPI.generateBindCodes(s.id, n)
    bindCodes.value = r.data || []
    loadBindings(0) // 刷新「绑定码」标签页
    loadStats()     // 刷新激活统计
  } catch (e) { error.value = e.message || '生成失败' }
}
async function addLicense() {
  try {
    await adminAPI.addVendorLicense({ orgId: activeOrg.value?.id, recipeGroup: licenseGroup.value })
    showAddLicense.value = false
    licenseGroup.value = ''
    loadLicenses()
  } catch (e) { error.value = e.message || '授权失败' }
}
async function setLicenseStatus(l, ev) {
  try { await adminAPI.setVendorLicenseStatus(l.id, ev.target.value); l.status = ev.target.value }
  catch (e) { error.value = e.message || '操作失败' }
}
async function deleteContract(row) {
  if (!confirm(`删除合同「${row.contractNo}」？`)) return
  try { await adminAPI.deleteVendorContract(row.id); loadContracts() } catch (e) { error.value = e.message || '操作失败' }
}
async function deleteSku(row) {
  if (!confirm(`删除 SKU「${row.sku}」？其全部绑定码将一并删除。`)) return
  try { await adminAPI.deleteVendorSku(row.id); loadSkus(); loadBindings(0); loadStats() } catch (e) { error.value = e.message || '操作失败' }
}
async function deleteLicense(row) {
  if (!confirm(`删除内容授权「${row.recipeGroup || row.id}」？`)) return
  try { await adminAPI.deleteVendorLicense(row.id); loadLicenses() } catch (e) { error.value = e.message || '操作失败' }
}
async function deleteBinding(row) {
  if (!confirm(`删除绑定码「${row.bindCode}」？`)) return
  try { await adminAPI.deleteVendorBinding(row.id); loadBindings(0); loadStats() } catch (e) { error.value = e.message || '操作失败' }
}

function fmtTime(t) { return t ? String(t).replace('T', ' ').slice(0, 16) : '-' }

onMounted(loadVendors)
</script>

<style scoped>
.filter-bar { display: flex; gap: 10px; margin-bottom: 14px; flex-wrap: wrap; align-items: center; }
.input, .select { padding: 8px 12px; border: 1px solid var(--border-color, #e5e7eb); border-radius: 6px; font-size: 14px; }
.btn { padding: 8px 14px; border: 1px solid var(--border-color, #e5e7eb); border-radius: 6px; background: #fff; cursor: pointer; font-size: 14px; }
.btn.primary { background: var(--primary, #409eff); border-color: var(--primary, #409eff); color: #fff; }
.empty { padding: 40px 0; text-align: center; }
.stat-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); gap: 12px; margin-bottom: 14px; }
.stat { background: #fff; border: 1px solid var(--border-color, #e5e7eb); border-radius: 10px; padding: 12px; text-align: center; }
.stat b { display: block; font-size: 20px; }
.stat span { color: var(--text-secondary, #6b7280); font-size: 12px; }
.tabs { display: flex; gap: 6px; margin-bottom: 12px; }
.tab { padding: 6px 14px; border: 1px solid var(--border-color, #e5e7eb); border-radius: 6px; background: #fff; cursor: pointer; }
.tab.active { background: var(--primary, #409eff); color: #fff; border-color: var(--primary, #409eff); }
.card { background: #fff; border: 1px solid var(--border-color, #e5e7eb); border-radius: 10px; padding: 14px; }
.table { width: 100%; border-collapse: collapse; }
.table th, .table td { text-align: left; padding: 8px 10px; border-bottom: 1px solid var(--border-color, #f0f0f0); font-size: 14px; }
.table th { color: var(--text-secondary, #6b7280); font-weight: 500; }
.mono { font-family: monospace; font-size: 13px; }
.muted { color: var(--text-secondary, #9ca3af); }
.error-text { color: #e5484d; margin-top: 10px; }
.codes-box { margin-top: 12px; font-size: 13px; word-break: break-all; }
.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,0.4); display: flex; align-items: center; justify-content: center; z-index: 1000; }
.modal { background: #fff; border-radius: 10px; padding: 20px; width: 380px; display: flex; flex-direction: column; gap: 12px; }
.modal h3 { margin: 0; }
.modal-actions { display: flex; justify-content: flex-end; gap: 8px; }
.ep-pagination { margin-top: 16px; justify-content: center; }
</style>
