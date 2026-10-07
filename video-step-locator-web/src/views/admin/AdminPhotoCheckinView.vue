<template>
  <div class="photo-page">
    <div class="page-head">
      <h2>📷 拍照打卡（饮食记录）</h2>
      <p class="tip">查看与管理用户拍照打卡数据（识别菜品/份量/营养估算）。删除为<b>软删</b>，进入回收站（photos 类型，级联软删关联饮食记录）；恢复请到「回收站」。</p>
    </div>

    <!-- 统计卡片 -->
    <div class="stat-row" v-if="stats">
      <div class="stat-card">
        <div class="stat-num">{{ stats.total ?? 0 }}</div>
        <div class="stat-label">累计拍照打卡</div>
      </div>
      <div class="stat-card">
        <div class="stat-num">{{ stats.today ?? 0 }}</div>
        <div class="stat-label">今日</div>
      </div>
      <div class="stat-card">
        <div class="stat-num">{{ stats.confirmedRate ?? 0 }}%</div>
        <div class="stat-label">确认率</div>
      </div>
      <div class="stat-card">
        <div class="stat-num">{{ stats.avgCalories ?? 0 }}</div>
        <div class="stat-label">平均热量(千卡)</div>
      </div>
      <div class="stat-card stat-sub" v-for="s in stats.byMealType || []" :key="s.meal_type">
        <div class="stat-num">{{ s.c }}</div>
        <div class="stat-label">{{ mealLabel(s.meal_type) }}</div>
      </div>
      <div class="stat-card stat-sub" v-for="s in stats.byStatus || []" :key="s.status">
        <div class="stat-num">{{ s.c }}</div>
        <div class="stat-label">{{ statusLabel(s.status) }}</div>
      </div>
    </div>

    <!-- 筛选 -->
    <div class="filter-bar">
      <input v-model="query.keyword" class="filter-input" placeholder="用户名/菜名关键字（可选）" @keyup.enter="reload" />
      <input v-model="query.dateFrom" class="filter-input" type="date" placeholder="开始日期" />
      <input v-model="query.dateTo" class="filter-input" type="date" placeholder="结束日期" />
      <select v-model="query.mealType" class="filter-input">
        <option value="">全部餐次</option>
        <option value="breakfast">早餐</option>
        <option value="lunch">午餐</option>
        <option value="dinner">晚餐</option>
        <option value="snack">加餐</option>
      </select>
      <select v-model="query.status" class="filter-input">
        <option value="">全部状态</option>
        <option value="pending">待确认</option>
        <option value="confirmed">已确认</option>
        <option value="corrected">已修正</option>
      </select>
      <button class="btn-filter" @click="reload">查询</button>
      <button class="btn-filter ghost" @click="reset">重置</button>
    </div>

    <!-- 列表 -->
    <el-table v-loading="loading" :data="rows" border stripe empty-text="暂无拍照打卡记录" style="width: 100%">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="username" label="用户" width="110">
        <template #default="{ row }">{{ row.username || ('#' + row.userId) }}</template>
      </el-table-column>
      <el-table-column prop="recordDate" label="日期" width="110" />
      <el-table-column label="餐次" width="80">
        <template #default="{ row }">{{ mealLabel(row.mealType) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="识别菜品" min-width="180">
        <template #default="{ row }">{{ row.dishSummary || '-' }}</template>
      </el-table-column>
      <el-table-column prop="calories" label="合计热量" width="90">
        <template #default="{ row }">{{ row.calories ?? '-' }}</template>
      </el-table-column>
      <el-table-column prop="dietRecordId" label="关联饮食" width="90">
        <template #default="{ row }">{{ row.dietRecordId || '-' }}</template>
      </el-table-column>
      <el-table-column label="创建时间" width="150">
        <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openDetail(row)">查看详情</el-button>
          <el-button link type="danger" size="small" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination v-if="total > pageSize" background layout="prev, pager, next" class="mt12"
      :total="total" :page-size="pageSize" :current-page="page"
      @current-change="p => { page = p; reload() }" />

    <!-- 详情弹窗 -->
    <el-dialog v-model="detailVisible" title="📷 拍照打卡详情" width="720px">
      <template v-if="detail">
        <div class="detail-grid">
          <div class="detail-photo">
            <el-image v-if="detail.photoPath" :src="photoUrl(detail.id)" fit="cover" style="width: 200px; height: 200px; border-radius: 8px;"
              :preview-src-list="[photoUrl(detail.id)]" preview-teleported />
            <div v-else class="no-photo">无照片</div>
          </div>
          <div class="detail-info">
            <p><b>用户：</b>{{ detail.userId }}</p>
            <p><b>日期：</b>{{ detail.recordDate }} · {{ mealLabel(detail.mealType) }}</p>
            <p><b>状态：</b><el-tag :type="statusTag(detail.status)" size="small">{{ statusLabel(detail.status) }}</el-tag></p>
            <p><b>关联饮食记录：</b>{{ detail.dietRecordId || '-' }}</p>
            <p><b>合计热量：</b>{{ detail.calories ?? 0 }} 千卡</p>
            <p><b>创建时间：</b>{{ fmt(detail.createdAt) }}</p>
          </div>
        </div>

        <h4 style="margin: 16px 0 8px">识别菜品（置信度）</h4>
        <el-table :data="detail.recognition || []" border size="small" empty-text="无">
          <el-table-column prop="dishName" label="菜名" min-width="120" />
          <el-table-column label="置信度" width="110">
            <template #default="{ row }">{{ Math.round((row.confidence ?? 0) * 100) }}%</template>
          </el-table-column>
        </el-table>

        <h4 style="margin: 16px 0 8px">份量与营养估算（每道菜）</h4>
        <el-table :data="detail.portion || []" border size="small" empty-text="无">
          <el-table-column prop="dishName" label="菜名" min-width="120" />
          <el-table-column prop="portionGrams" label="份量(g)" width="90" />
          <el-table-column label="方式" width="90">
            <template #default="{ row }">{{ row.method === 'manual' ? '手动' : '默认' }}</template>
          </el-table-column>
          <el-table-column prop="calories" label="热量" width="80" />
          <el-table-column prop="protein" label="蛋白" width="70" />
          <el-table-column prop="fat" label="脂肪" width="70" />
          <el-table-column prop="carbs" label="碳水" width="70" />
          <el-table-column label="估算" width="70">
            <template #default="{ row }">{{ row.estimated ? '✓' : '-' }}</template>
          </el-table-column>
        </el-table>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessageBox, ElMessage } from 'element-plus'
import { adminAPI } from '@/api/admin'

const mealLabels = { breakfast: '早餐', lunch: '午餐', dinner: '晚餐', snack: '加餐' }
const statusLabels = { pending: '待确认', confirmed: '已确认', corrected: '已修正' }
const statusTags = { pending: 'warning', confirmed: 'success', corrected: 'primary' }

const mealLabel = (k) => mealLabels[k] || k || '-'
const statusLabel = (k) => statusLabels[k] || k || '-'
const statusTag = (k) => statusTags[k] || 'info'

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = 20
const stats = ref(null)
const query = reactive({ keyword: '', dateFrom: '', dateTo: '', mealType: '', status: '' })

const detailVisible = ref(false)
const detail = ref(null)

onMounted(async () => {
  await loadStats()
  await reload()
})

async function loadStats() {
  try { stats.value = (await adminAPI.getPhotoCheckinStats()).data || {} } catch (e) { /* 静默 */ }
}

async function reload() {
  loading.value = true
  try {
    const params = {
      page: page.value - 1,
      size: pageSize,
      keyword: query.keyword || undefined,
      dateFrom: query.dateFrom || undefined,
      dateTo: query.dateTo || undefined,
      mealType: query.mealType || undefined,
      status: query.status || undefined,
    }
    const res = (await adminAPI.getPhotoCheckins(params)).data || {}
    rows.value = res.items || []
    total.value = res.total || 0
  } catch (e) {
    ElMessage.error('加载拍照打卡数据失败')
  } finally {
    loading.value = false
  }
}

function reset() {
  Object.assign(query, { keyword: '', dateFrom: '', dateTo: '', mealType: '', status: '' })
  page.value = 1
  reload()
}

function photoUrl(id) {
  const token = localStorage.getItem('token') || ''
  return `/api/admin/photo-checkins/${id}/image?token=${token}`
}

async function openDetail(row) {
  try {
    detail.value = (await adminAPI.getPhotoCheckin(row.id)).data || null
    detailVisible.value = true
  } catch (e) {
    ElMessage.error('加载详情失败')
  }
}

function remove(row) {
  ElMessageBox.prompt(`确认软删拍照打卡 #${row.id}（用户 ${row.username || row.userId}）？删除后进入回收站，可在「回收站-photos」恢复。`, '删除确认', {
    confirmButtonText: '确认删除',
    cancelButtonText: '取消',
    inputPlaceholder: '请输入「删除」确认',
    inputValidator: (v) => (v === '删除' ? true : '请输入「删除」以确认'),
  }).then(async ({ value }) => {
    if (value !== '删除') return
    try {
      await adminAPI.deletePhotoCheckin(row.id)
      ElMessage.success('已软删，进入回收站')
      await loadStats()
      await reload()
    } catch (e) {
      ElMessage.error('删除失败')
    }
  }).catch(() => {})
}

function fmt(v) { return v ? String(v).replace('T', ' ').slice(0, 19) : '-' }
</script>

<style scoped>
.photo-page { padding: 4px 2px; }
.page-head h2 { margin: 0 0 6px; }
.tip { color: var(--text-secondary); font-size: 13px; margin: 0 0 14px; }
.stat-row { display: flex; gap: 10px; margin-bottom: 14px; flex-wrap: wrap; }
.stat-card { border: 1px solid #dcdfe6; border-radius: 10px; padding: 10px 18px; background: #fff; min-width: 96px; text-align: center; }
.stat-card.stat-sub { min-width: 80px; }
.stat-num { font-size: 20px; font-weight: 600; color: var(--primary, #409eff); }
.stat-label { font-size: 12px; color: var(--text-secondary); margin-top: 2px; }
.filter-bar { display: flex; gap: 10px; margin-bottom: 14px; flex-wrap: wrap; }
.filter-input { border: 1px solid #dcdfe6; border-radius: 6px; padding: 7px 10px; font-size: 14px; outline: none; }
.filter-input:focus { border-color: var(--primary, #409eff); }
.btn-filter { border: none; background: var(--primary, #409eff); color: #fff; border-radius: 6px; padding: 7px 16px; cursor: pointer; font-size: 14px; }
.btn-filter.ghost { background: #fff; color: #606266; border: 1px solid #dcdfe6; }
.btn-filter:hover { opacity: 0.9; }
.mt12 { margin-top: 12px; }
.detail-grid { display: flex; gap: 20px; }
.detail-info p { margin: 4px 0; }
.no-photo { width: 200px; height: 200px; border-radius: 8px; background: #f5f7fa; display: flex; align-items: center; justify-content: center; color: #909399; }
</style>
