<template>
  <div class="org-page">
    <!-- 管理员：企业管理（全部企业，折叠展示） -->
    <AdminOrgManageView v-if="isAdmin" />

    <template v-else>
    <!-- 无企业：创建引导 -->
    <div v-if="!loading && !myOrg" class="card create-card">
      <h2>🏢 开通企业管理</h2>
      <p class="desc">
        开通后可邀请团队成员共享视频处理与内容工作台，按席位 + 用量计费（B 端）。创建后您自动成为企业主（owner）。
      </p>
      <div class="create-row">
        <input v-model="createName" class="input" placeholder="企业名称，如：某品牌厨房内容团队" />
        <button class="btn primary" :disabled="creating" @click="createOrg">
          {{ creating ? '创建中…' : '创建企业' }}
        </button>
      </div>
      <div v-if="createError" class="error-text">{{ createError }}</div>
    </div>

    <template v-else-if="myOrg">
      <!-- 欠费停服提示（B3） -->
      <div v-if="myOrg.status === 2" class="overdue-banner">
        ⚠️ 企业已欠费停服（只读），请续费后恢复使用。
      </div>

      <!-- 企业信息 -->
      <div class="card">
        <div class="card-head" @click="toggleUser('info')">
          <h3>🏢 企业信息</h3>
          <span class="plan-badge">{{ planLabel(myOrg.planType) }}</span>
          <span class="col-arrow">{{ userOpen.has('info') ? '▴' : '▾' }}</span>
        </div>
        <div v-show="userOpen.has('info')" class="card-body">
          <div class="info-grid">
            <div><label>企业名称</label><span>{{ myOrg.name }}</span></div>
            <div><label>企业编码</label><span class="mono">{{ myOrg.code }}</span></div>
            <div><label>我的角色</label><span>{{ roleLabel(myOrg.myRole) }}</span></div>
            <div><label>成员数</label><span>{{ myOrg.memberCount }} 人</span></div>
          </div>
          <div v-if="canManage" class="edit-row">
            <input v-model="editName" class="input" placeholder="企业名称" />
            <button class="btn" :disabled="saving" @click="saveOrg">保存名称</button>
          </div>
        </div>
      </div>

      <!-- 用量 / 配额 -->
      <div class="card">
        <div class="card-head" @click="toggleUser('usage')">
          <h3>📊 企业用量与配额</h3>
          <span class="col-arrow">{{ userOpen.has('usage') ? '▴' : '▾' }}</span>
        </div>
        <div v-show="userOpen.has('usage')" class="card-body">
        <div class="stat-grid">
          <div class="stat"><b>{{ stats.videoTotal ?? '-' }}</b><span>视频总数</span></div>
          <div class="stat"><b>{{ stats.videoCompleted ?? '-' }}</b><span>已完成</span></div>
          <div class="stat"><b>{{ stats.taskRunning ?? '-' }}</b><span>处理中任务</span></div>
          <div class="stat"><b>{{ stats.memberCount ?? '-' }}</b><span>成员</span></div>
          <div class="stat"><b>{{ formatBytes(stats.storageBytes) }}</b><span>已用存储</span></div>
          <div class="stat"><b>{{ billing.totalGb ?? '-' }}</b><span>累计流量(GB)</span></div>
        </div>
        <div class="quota-bars" v-if="billing.quota">
          <div class="qbar">
            <span>视频 {{ billing.quota.videoUsed ?? 0 }}/{{ billing.quota.videoLimit ?? '-' }}</span>
            <div class="bar"><i :class="{ warn: (billing.quota.videoPercent||0) >= 80 }"
                 :style="{ width: (billing.quota.videoPercent||0) + '%' }"></i></div>
          </div>
          <div class="qbar">
            <span>成员 {{ billing.quota.memberUsed ?? 0 }}/{{ billing.quota.memberLimit ?? '-' }}</span>
            <div class="bar"><i :class="{ warn: (billing.quota.memberPercent||0) >= 80 }"
                 :style="{ width: (billing.quota.memberPercent||0) + '%' }"></i></div>
          </div>
          <div class="qbar">
            <span>存储 {{ formatBytes(billing.quota.storageUsed) }}/{{ formatBytes(billing.quota.storageLimit) }}</span>
            <div class="bar"><i :class="{ warn: (billing.quota.storagePercent||0) >= 80 }"
                 :style="{ width: (billing.quota.storagePercent||0) + '%' }"></i></div>
          </div>
        </div>
        </div>
      </div>

      <!-- B2：企业内容工作台 -->
      <div class="card">
        <div class="card-head" @click="toggleUser('workbench')">
          <h3>🚀 企业内容工作台</h3>
          <span class="col-arrow">{{ userOpen.has('workbench') ? '▴' : '▾' }}</span>
        </div>
        <div v-show="userOpen.has('workbench')" class="card-body">
        <div class="stat-grid">
          <div class="stat"><b>{{ orgTasks.successRate ?? '-' }}%</b><span>任务成功率</span></div>
          <div class="stat"><b>{{ orgTasks.total ?? '-' }}</b><span>累计任务</span></div>
          <div class="stat"><b>{{ orgTasks.todayCount ?? '-' }}</b><span>今日处理</span></div>
          <div class="stat"><b>{{ fmtMs(orgTasks.avgDurationMs) }}</b><span>平均耗时</span></div>
        </div>
        <div class="status-row">
          <span v-for="(v, k) in orgTasks.byStatus || {}" :key="k" class="chip">{{ k }}: {{ v }}</span>
          <span v-if="!Object.keys(orgTasks.byStatus || {}).length" class="muted">暂无处理任务</span>
        </div>
        <h4 class="sub-title">🍲 企业菜谱库</h4>
        <el-table :data="orgRecipes" border stripe style="width: 100%" empty-text="暂无企业菜谱（处理完成带菜谱的视频后自动收录）">
          <el-table-column prop="title" label="菜名" min-width="160" />
          <el-table-column prop="cuisine" label="菜系" min-width="100">
            <template #default="{ row }">{{ row.cuisine || '-' }}</template>
          </el-table-column>
          <el-table-column prop="tasteTags" label="口味" min-width="120">
            <template #default="{ row }">{{ row.tasteTags || '-' }}</template>
          </el-table-column>
          <el-table-column prop="estimatedTime" label="预计时间" min-width="110">
            <template #default="{ row }">{{ row.estimatedTime ? row.estimatedTime + ' 分钟' : '-' }}</template>
          </el-table-column>
          <el-table-column prop="videoTitle" label="视频" min-width="160">
            <template #default="{ row }">{{ row.videoTitle || '-' }}</template>
          </el-table-column>
        </el-table>
        </div>
      </div>

      <!-- 成员管理 -->
      <div class="card">
        <div class="card-head" @click="toggleUser('members')">
          <h3>👥 成员管理</h3>
          <span class="head-actions">
            <button v-if="canManage" class="btn" @click.stop="showAdd = true">＋ 邀请成员</button>
            <span class="col-arrow">{{ userOpen.has('members') ? '▴' : '▾' }}</span>
          </span>
        </div>
        <div v-show="userOpen.has('members')" class="card-body">
        <el-table :data="members" border stripe style="width: 100%" empty-text="暂无成员">
          <el-table-column prop="username" label="用户名" min-width="130" />
          <el-table-column prop="nickname" label="昵称" min-width="120">
            <template #default="{ row }">{{ row.nickname || '-' }}</template>
          </el-table-column>
          <el-table-column prop="role" label="角色" min-width="100">
            <template #default="{ row }">
              <el-tag :type="roleTagType(row.role)" size="small">{{ roleLabel(row.role) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="joinedAt" label="加入时间" width="170">
            <template #default="{ row }">{{ formatTime(row.joinedAt) }}</template>
          </el-table-column>
          <el-table-column v-if="canManage" label="操作" width="210" fixed="right">
            <template #default="{ row }">
              <template v-if="row.role !== 'owner'">
                <select class="select" :value="row.role" @change="changeRole(row, $event)" style="margin-right: 8px">
                  <option value="admin">admin</option>
                  <option value="operator">operator</option>
                  <option value="member">member</option>
                </select>
                <el-button link type="danger" size="small" @click="removeMember(row)">移除</el-button>
              </template>
              <span v-else class="muted">企业主</span>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="memberError" class="error-text">{{ memberError }}</div>
        </div>
      </div>

      <!-- A1：白标品牌配置（owner/admin） -->
      <div v-if="canManage" class="card">
        <div class="card-head" @click="toggleUser('brand')">
          <h3>🎨 品牌配置（白标）</h3>
          <span class="col-arrow">{{ userOpen.has('brand') ? '▴' : '▾' }}</span>
        </div>
        <div v-show="userOpen.has('brand')" class="card-body">
        <div class="brand-grid">
          <label>品牌名称 <input v-model="brandForm.appName" class="input" placeholder="如：某品牌厨电" /></label>
          <label>启动副标题 <input v-model="brandForm.splashText" class="input" placeholder="如：AI 帮做菜" /></label>
          <label>Logo URL <input v-model="brandForm.logoUrl" class="input" placeholder="https://...logo.png" /></label>
          <label>关于文案 <input v-model="brandForm.aboutText" class="input" placeholder="关于页文案" /></label>
        </div>
        <div class="edit-row">
          <button class="btn primary" :disabled="savingBrand" @click="saveBrand">{{ savingBrand ? '保存中…' : '保存品牌' }}</button>
          <span v-if="brandMsg" class="brand-msg">{{ brandMsg }}</span>
        </div>
        </div>
      </div>

      <!-- 套餐订阅 -->
      <div class="card" v-if="isOwner">
        <div class="card-head" @click="toggleUser('plans')">
          <h3>💳 套餐订阅</h3>
          <span class="col-arrow">{{ userOpen.has('plans') ? '▴' : '▾' }}</span>
        </div>
        <div v-show="userOpen.has('plans')" class="card-body">
        <div class="plan-grid">
          <div v-for="p in plans" :key="p.code" class="plan"
               :class="{ active: myOrg.planType === p.code }">
            <h4>{{ p.name }}</h4>
            <div class="price">¥{{ p.priceMonthly }}<span>/月</span></div>
            <div class="plan-desc">
              视频 {{ qv(p, 'video_limit') }} · 成员 {{ qv(p, 'member_limit') }} · 存储 {{ qvBytes(p) }}
            </div>
            <button v-if="myOrg.planType !== p.code" class="btn primary"
                    :disabled="subscribing" @click="subscribe(p)">升级到{{ p.name }}</button>
            <span v-else class="current">当前套餐</span>
          </div>
        </div>
        </div>
      </div>

      <!-- 账单 -->
      <div class="card" v-if="isOwner">
        <div class="card-head" @click="toggleUser('billing')">
          <h3>🧾 账单</h3>
          <span class="col-arrow">{{ userOpen.has('billing') ? '▴' : '▾' }}</span>
        </div>
        <div v-show="userOpen.has('billing')" class="card-body">
        <el-table :data="invoices" border stripe style="width: 100%" empty-text="暂无账单">
          <el-table-column prop="createdAt" label="时间" width="170">
            <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column prop="description" label="说明" min-width="160">
            <template #default="{ row }">{{ row.description }}</template>
          </el-table-column>
          <el-table-column prop="amount" label="金额" width="100">
            <template #default="{ row }">¥{{ row.amount }}</template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="invStatusTagType(row.status)" size="small">{{ invStatusLabel(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="110" fixed="right">
            <template #default="{ row }">
              <el-button v-if="row.status === 'pending'" link type="primary" size="small" :disabled="paying" @click="goPay(row)">{{ paying ? '处理中…' : '去支付' }}</el-button>
              <span v-else class="muted">-</span>
            </template>
          </el-table-column>
        </el-table>
        <!-- 支付单 -->
        <div v-if="payments.length" class="pay-list">
          <h4>💳 支付记录</h4>
          <div v-for="p in payments" :key="p.id" class="pay-row">
            <span>¥{{ p.amount }}</span>
            <span :class="p.status">{{ payStatusLabel(p.status) }}</span>
            <span v-if="p.payNo" class="mono">{{ p.payNo }}</span>
            <button v-if="p.status === 'created'" class="btn primary" :disabled="paying" @click="mockPay(p)">模拟支付</button>
          </div>
        </div>
        </div>
      </div>
    </template>

    <div v-else class="loading">加载中…</div>

    <!-- 邀请成员弹窗 -->
    <div v-if="showAdd" class="modal-mask" @click.self="showAdd = false">
      <div class="modal">
        <h3>邀请成员</h3>
        <input v-model="inviteAccount" class="input" placeholder="成员用户名或邮箱" />
        <select v-model="inviteRole" class="select">
          <option value="admin">admin（可管理成员/配额）</option>
          <option value="operator">operator（内容操作员）</option>
          <option value="member">member（普通成员）</option>
        </select>
        <div v-if="memberError" class="error-text">{{ memberError }}</div>
        <div class="modal-actions">
          <button class="btn" @click="showAdd = false">取消</button>
          <button class="btn primary" :disabled="inviting" @click="doInvite">邀请</button>
        </div>
      </div>
    </div>
    </template>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { orgAPI } from '@/api/org'
import { useAuthStore } from '@/stores/auth'
import AdminOrgManageView from './AdminOrgManageView.vue'

const authStore = useAuthStore()
// 管理员：企业版「我的企业」= 企业管理（全部企业，折叠展示）
const isAdmin = computed(() => authStore.isAdmin)
// 普通用户：长卡片折叠分区（默认只展开「企业信息」）
const userOpen = ref(new Set(['info']))
function toggleUser(key) {
  const s = new Set(userOpen.value)
  if (s.has(key)) s.delete(key); else s.add(key)
  userOpen.value = s
}

const loading = ref(true)
const myOrg = ref(null)
const stats = ref({})
const billing = ref({})
const members = ref([])
const plans = ref([])
const invoices = ref([])

const createName = ref('')
const creating = ref(false)
const createError = ref('')

const editName = ref('')
const saving = ref(false)

const showAdd = ref(false)
const inviteAccount = ref('')
const inviteRole = ref('member')
const inviting = ref(false)
const memberError = ref('')
const subscribing = ref(false)
// B3 支付
const payments = ref([])
const paying = ref(false)
// B2：企业内容工作台
const orgRecipes = ref([])
const orgTasks = ref({})
// A1：品牌配置
const brandForm = ref({ appName: '', splashText: '', logoUrl: '', aboutText: '' })
const savingBrand = ref(false)
const brandMsg = ref('')

const canManage = computed(() => ['owner', 'admin'].includes(myOrg.value?.myRole))
const isOwner = computed(() => myOrg.value?.myRole === 'owner')

async function loadAll() {
  loading.value = true
  try {
    const res = await orgAPI.getMyOrg()
    myOrg.value = res.data
    if (res.data) {
      editName.value = res.data.name
      loadStats()
      loadBilling()
      loadMembers()
      loadInvoices()
      loadPayments()
      loadWorkbench()
      if (isOwner.value) loadPlans()
      loadBrandForm()
    }
  } catch (e) {
    createError.value = e.message || '加载企业信息失败'
  } finally {
    loading.value = false
  }
}

// A1：加载品牌配置到表单
async function loadBrandForm() {
  try {
    const { request } = await import('@/api/request')
    const r = await request.get(`/brand/${myOrg.value.id}`)
    const b = r.data || {}
    brandForm.value = {
      appName: b.appName || myOrg.value.name || '',
      splashText: b.splashText || '',
      logoUrl: b.logoUrl || '',
      aboutText: b.aboutText || '',
    }
  } catch { /* 忽略，保留默认 */ }
}

async function saveBrand() {
  savingBrand.value = true
  brandMsg.value = ''
  try {
    const { useAuthStore } = await import('@/stores/auth')
    await orgAPI.updateBrand(myOrg.value.id, { ...brandForm.value })
    brandMsg.value = '已保存' + (brandForm.value.appName ? '（' + brandForm.value.appName + '）' : '')
    useAuthStore().loadBrand() // 刷新全局标题为品牌名
    setTimeout(() => { brandMsg.value = '' }, 2500)
  } catch (e) {
    brandMsg.value = e.message || '保存失败'
  } finally {
    savingBrand.value = false
  }
}

// B2：企业内容工作台（任务看板 + 菜谱库）
async function loadWorkbench() {
  loadOrgTasks()
  loadOrgRecipes()
}
async function loadOrgTasks() {
  try {
    const r = await orgAPI.getOrgTasks(myOrg.value.id)
    orgTasks.value = r.data || {}
  } catch { orgTasks.value = {} }
}
async function loadOrgRecipes() {
  try {
    const r = await orgAPI.getOrgRecipes(myOrg.value.id)
    orgRecipes.value = r.data || []
  } catch { orgRecipes.value = [] }
}

async function loadStats() {
  const r = await orgAPI.getOrgStats(myOrg.value.id)
  stats.value = r.data || {}
}
async function loadBilling() {
  const r = await orgAPI.getOrgBilling(myOrg.value.id)
  billing.value = r.data || {}
}
async function loadMembers() {
  const r = await orgAPI.getMembers(myOrg.value.id, { page: 0, size: 100 })
  members.value = r.data?.items || []
}
async function loadPlans() {
  const r = await orgAPI.getPlans()
  plans.value = r.data || []
}
async function loadInvoices() {
  const r = await orgAPI.getInvoices(myOrg.value.id, { page: 0, size: 50 })
  invoices.value = r.data?.items || []
}

// B3 支付
async function loadPayments() {
  try {
    const r = await orgAPI.getPayments(myOrg.value.id, { page: 0, size: 50 })
    payments.value = r.data?.items || []
  } catch { payments.value = [] }
}
async function goPay(inv) {
  paying.value = true
  memberError.value = ''
  try {
    await orgAPI.createPayment(inv.id)
    await loadPayments()
  } catch (e) { memberError.value = e.message || '创建支付单失败' }
  finally { paying.value = false }
}
async function mockPay(p) {
  paying.value = true
  memberError.value = ''
  try {
    await orgAPI.mockPay(p.id)
    await loadPayments()
    await loadInvoices()
    await loadBilling()
    myOrg.value.status = 1
  } catch (e) { memberError.value = e.message || '支付失败' }
  finally { paying.value = false }
}

async function createOrg() {
  if (!createName.value.trim()) { createError.value = '请输入企业名称'; return }
  creating.value = true
  createError.value = ''
  try {
    await orgAPI.createOrg(createName.value.trim())
    createName.value = ''
    await loadAll()
  } catch (e) {
    createError.value = e.message || '创建失败'
  } finally {
    creating.value = false
  }
}

async function saveOrg() {
  saving.value = true
  memberError.value = ''
  try {
    await orgAPI.updateOrg(myOrg.value.id, { name: editName.value })
    myOrg.value.name = editName.value
  } catch (e) {
    memberError.value = e.message || '保存失败'
  } finally {
    saving.value = false
  }
}

async function doInvite() {
  inviting.value = true
  memberError.value = ''
  try {
    await orgAPI.addMember(myOrg.value.id, { account: inviteAccount.value, role: inviteRole.value })
    showAdd.value = false
    inviteAccount.value = ''
    loadMembers()
    loadStats()
  } catch (e) {
    memberError.value = e.message || '邀请失败'
  } finally {
    inviting.value = false
  }
}

async function changeRole(m, ev) {
  memberError.value = ''
  try {
    await orgAPI.updateMemberRole(m.id, ev.target.value)
    m.role = ev.target.value
  } catch (e) {
    memberError.value = e.message || '修改失败'
    loadMembers()
  }
}

async function removeMember(m) {
  if (!confirm(`确认移除成员 ${m.username}？`)) return
  memberError.value = ''
  try {
    await orgAPI.removeMember(m.id)
    loadMembers()
    loadStats()
  } catch (e) {
    memberError.value = e.message || '移除失败'
  }
}

async function subscribe(p) {
  if (!confirm(`确认升级到「${p.name}」（¥${p.priceMonthly}/月）？`)) return
  subscribing.value = true
  memberError.value = ''
  try {
    await orgAPI.subscribe(myOrg.value.id, p.code)
    loadBilling()
    loadInvoices()
    myOrg.value.planType = p.code
  } catch (e) {
    memberError.value = e.message || '订阅失败'
  } finally {
    subscribing.value = false
  }
}

function qv(p, key) {
  try {
    const q = JSON.parse(p.quota)
    return q[key] ?? '-'
  } catch { return '-' }
}
function qvBytes(p) {
  try {
    const q = JSON.parse(p.quota)
    return formatBytes(q.storage_bytes)
  } catch { return '-' }
}
function planLabel(p) { return ({ free: '免费版', basic: '基础版', pro: '专业版', enterprise: '企业版' })[p] || p }
function roleLabel(r) { return ({ owner: '企业主', admin: '管理员', operator: '操作员', member: '成员' })[r] || r }
function roleTagType(r) { return ({ owner: 'danger', admin: 'warning', operator: 'success', member: 'info' })[r] || 'info' }
function invStatusLabel(s) { return ({ pending: '待支付', paid: '已支付', overdue: '逾期', canceled: '已取消' })[s] || s }
function invStatusTagType(s) { return ({ pending: 'warning', paid: 'success', overdue: 'danger', canceled: 'info' })[s] || 'info' }
function payStatusLabel(s) { return ({ created: '待支付', success: '已支付', failed: '失败', canceled: '已取消' })[s] || s }
function formatBytes(b) {
  if (b == null) return '-'
  const n = Number(b)
  if (n >= 1024 * 1024 * 1024) return (n / 1024 / 1024 / 1024).toFixed(2) + ' GB'
  if (n >= 1024 * 1024) return (n / 1024 / 1024).toFixed(1) + ' MB'
  return (n / 1024).toFixed(0) + ' KB'
}
function formatTime(t) { return t ? String(t).replace('T', ' ').slice(0, 16) : '-' }
function fmtMs(ms) {
  if (!ms) return '-'
  const s = Math.round(ms / 1000)
  if (s < 60) return s + 's'
  return Math.floor(s / 60) + 'm' + (s % 60) + 's'
}

onMounted(loadAll)
</script>

<style scoped>
.org-page { display: flex; flex-direction: column; gap: 16px; }
.card { background: #fff; border: 1px solid var(--border-color, #e5e7eb); border-radius: 10px; padding: 18px; }
.card-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 14px; cursor: pointer; user-select: none; }
.card-head h3 { margin: 0; font-size: 16px; }
.card-body { }
.col-arrow { color: var(--text-secondary, #9ca3af); font-size: 14px; margin-left: 8px; }
.head-actions { display: flex; align-items: center; gap: 10px; }
.desc { color: var(--text-secondary, #6b7280); font-size: 13px; margin: 8px 0 16px; }
.create-card h2 { margin: 0 0 4px; }
.create-row { display: flex; gap: 10px; }
.input, .select { padding: 8px 12px; border: 1px solid var(--border-color, #e5e7eb); border-radius: 6px; font-size: 14px; }
.input { flex: 1; min-width: 200px; }
.btn { padding: 8px 14px; border: 1px solid var(--border-color, #e5e7eb); border-radius: 6px; background: #fff; cursor: pointer; font-size: 14px; }
.btn.primary { background: var(--primary, #409eff); border-color: var(--primary, #409eff); color: #fff; }
.btn.danger-ghost { color: #e5484d; margin-left: 6px; }
.btn:disabled { opacity: 0.5; cursor: not-allowed; }
.plan-badge { padding: 2px 10px; border-radius: 20px; background: #e8f4ff; color: var(--primary, #409eff); font-size: 13px; }
.info-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 12px; }
.info-grid label { display: block; color: var(--text-secondary, #6b7280); font-size: 12px; }
.mono { font-family: monospace; }
.edit-row { display: flex; gap: 8px; margin-top: 14px; }
.overdue-banner { padding: 12px 16px; border-radius: 8px; background: #fdeaea; color: #c0392b; border: 1px solid #f5c6c6; font-size: 14px; }
.brand-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 12px; }
.brand-grid label { display: flex; flex-direction: column; gap: 6px; color: var(--text-secondary, #6b7280); font-size: 13px; }
.brand-grid .input { width: 100%; }
.brand-msg { color: var(--primary, #409eff); font-size: 13px; }
.pay-list { margin-top: 14px; border-top: 1px dashed var(--border-color, #eef0f2); padding-top: 12px; }
.pay-list h4 { margin: 0 0 8px; font-size: 14px; }
.pay-row { display: flex; align-items: center; gap: 12px; padding: 6px 0; font-size: 14px; }
.pay-row .success { color: #1a7f37; }
.pay-row .created { color: #b26a00; }
.stat-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(120px, 1fr)); gap: 12px; }
.stat { background: #f8fafc; border-radius: 8px; padding: 12px; text-align: center; }
.stat b { display: block; font-size: 20px; }
.stat span { color: var(--text-secondary, #6b7280); font-size: 12px; }
.quota-row { margin-top: 12px; color: var(--text-secondary, #6b7280); font-size: 13px; }
.quota-bars { display: flex; flex-direction: column; gap: 8px; margin-top: 14px; }
.qbar { display: grid; grid-template-columns: 180px 1fr; align-items: center; gap: 10px; font-size: 13px; color: var(--text-secondary, #6b7280); }
.bar { height: 8px; background: #eef1f5; border-radius: 6px; overflow: hidden; }
.bar i { display: block; height: 100%; background: var(--primary, #409eff); border-radius: 6px; transition: width .3s; }
.bar i.warn { background: #e5484d; }
.status-row { display: flex; flex-wrap: wrap; gap: 8px; margin: 12px 0 4px; }
.chip { padding: 2px 10px; border-radius: 20px; background: #f0f4ff; color: var(--primary, #409eff); font-size: 12px; }
.sub-title { margin: 16px 0 10px; font-size: 14px; }
.table { width: 100%; border-collapse: collapse; }
.table th, .table td { text-align: left; padding: 8px 10px; border-bottom: 1px solid var(--border-color, #f0f0f0); font-size: 14px; }
.table th { color: var(--text-secondary, #6b7280); font-weight: 500; }
.plan-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 12px; }
.plan { border: 1px solid var(--border-color, #e5e7eb); border-radius: 10px; padding: 14px; }
.plan.active { border-color: var(--primary, #409eff); background: #f6fbff; }
.plan h4 { margin: 0 0 6px; }
.price { font-size: 22px; font-weight: 600; }
.price span { font-size: 12px; color: var(--text-secondary, #6b7280); }
.plan-desc { color: var(--text-secondary, #6b7280); font-size: 12px; margin: 8px 0 12px; }
.current { color: var(--primary, #409eff); font-size: 13px; }
.error-text { color: #e5484d; font-size: 13px; margin-top: 8px; }
.loading { color: var(--text-secondary, #6b7280); padding: 40px; text-align: center; }
.muted { color: var(--text-secondary, #9ca3af); }
.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,0.4); display: flex; align-items: center; justify-content: center; z-index: 1000; }
.modal { background: #fff; border-radius: 10px; padding: 20px; width: 360px; display: flex; flex-direction: column; gap: 12px; }
.modal h3 { margin: 0; }
.modal-actions { display: flex; justify-content: flex-end; gap: 8px; }
</style>
