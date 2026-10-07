<template>
  <div class="dishdb-page">
    <div class="page-head">
      <h2>🥘 菜品级营养库（拍照打卡）</h2>
      <p class="tip">菜品库命中优先于 AI 兜底（识别/确认用）。「AI 候选」由拍照打卡确认时的 AI 兜底自动收录，需在此<b>审核通过/驳回</b>；种子与用户自定义默认已复核。hit_count 驱动扩充优先级。</p>
    </div>

    <!-- 统计卡 -->
    <div class="stat-row" v-if="stats">
      <div class="stat-card">
        <div class="stat-num">{{ stats.total ?? 0 }}</div>
        <div class="stat-label">菜品总数</div>
      </div>
      <div class="stat-card">
        <div class="stat-num">{{ stats.verified ?? 0 }}</div>
        <div class="stat-label">已复核</div>
      </div>
      <div class="stat-card">
        <div class="stat-num">{{ stats.aiCandidates ?? 0 }}</div>
        <div class="stat-label">AI 待审</div>
      </div>
      <div class="stat-card stat-sub" v-for="t in (stats.topByHit || []).slice(0, 5)" :key="t.dish_name">
        <div class="stat-num" style="font-size:14px">{{ t.hit_count }}</div>
        <div class="stat-label">{{ t.dish_name }}</div>
      </div>
    </div>

    <!-- 筛选 -->
    <div class="filter-bar">
      <input v-model="query.keyword" class="filter-input" placeholder="菜名关键字（可选）" @keyup.enter="reload" />
      <select v-model="query.isVerified" class="filter-input">
        <option value="">全部复核状态</option>
        <option :value="1">已复核</option>
        <option :value="0">待复核</option>
      </select>
      <select v-model="query.source" class="filter-input">
        <option value="">全部来源</option>
        <option value="seed">seed 种子</option>
        <option value="ai">ai 候选</option>
        <option value="user">user 自定义</option>
      </select>
      <label class="filter-check">
        <input type="checkbox" v-model="query.sortHit" /> 按命中热度排序
      </label>
      <button class="btn-filter" @click="reload">查询</button>
      <button class="btn-filter ghost" @click="reset">重置</button>
    </div>

    <!-- 列表 -->
    <el-table v-loading="loading" :data="rows" border stripe empty-text="暂无菜品" style="width: 100%">
      <el-table-column prop="id" label="ID" width="60" />
      <el-table-column prop="dishName" label="菜名" min-width="120" />
      <el-table-column prop="cuisine" label="菜系" width="70">
        <template #default="{ row }">{{ row.cuisine || '-' }}</template>
      </el-table-column>
      <el-table-column prop="servingGrams" label="份量g" width="70" />
      <el-table-column label="营养(每份)" min-width="200">
        <template #default="{ row }">
          <span v-if="row.nutrition">{{ row.nutrition.calories ?? 0 }}千卡 · 蛋白{{ row.nutrition.protein ?? 0 }} · 脂{{ row.nutrition.fat ?? 0 }} · 碳{{ row.nutrition.carbs ?? 0 }}</span>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column label="复核" width="80">
        <template #default="{ row }">
          <el-tag :type="row.isVerified ? 'success' : 'warning'" size="small">{{ row.isVerified ? '已复核' : '待复核' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="source" label="来源" width="80" />
      <el-table-column prop="hitCount" label="命中" width="60" />
      <el-table-column label="创建时间" width="150">
        <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="210" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
          <el-button v-if="!row.isVerified" link type="success" size="small" @click="verify(row)">通过</el-button>
          <el-button link type="danger" size="small" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination v-if="total > pageSize" background layout="prev, pager, next" class="mt12"
      :total="total" :page-size="pageSize" :current-page="page"
      @current-change="p => { page = p; reload() }" />

    <!-- 编辑弹窗 -->
    <el-dialog v-model="editVisible" :title="'编辑菜品：' + (editing?.dishName || '')" width="min(560px, 94vw)">
      <div v-if="editing">
        <div class="edit-row"><label>菜名</label><input v-model="form.dishName" class="filter-input" style="flex:1" /></div>
        <div class="edit-row"><label>菜系</label><input v-model="form.cuisine" class="filter-input" style="flex:1" placeholder="川/粤/家常…" /></div>
        <div class="edit-row"><label>份量(g)</label><input v-model.number="form.servingGrams" type="number" class="filter-input" style="flex:1" /></div>
        <div class="edit-grid">
          <div class="edit-row"><label>热量(千卡)</label><input v-model.number="form.nutrition.calories" type="number" class="filter-input" style="flex:1" /></div>
          <div class="edit-row"><label>蛋白(g)</label><input v-model.number="form.nutrition.protein" type="number" class="filter-input" style="flex:1" /></div>
          <div class="edit-row"><label>脂肪(g)</label><input v-model.number="form.nutrition.fat" type="number" class="filter-input" style="flex:1" /></div>
          <div class="edit-row"><label>碳水(g)</label><input v-model.number="form.nutrition.carbs" type="number" class="filter-input" style="flex:1" /></div>
        </div>
        <div class="edit-row"><label>食材</label><input v-model="form.ingredientsText" class="filter-input" style="flex:1" placeholder="逗号分隔，如：番茄,鸡蛋" /></div>
      </div>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessageBox, ElMessage } from 'element-plus'
import { adminAPI } from '@/api/admin'

const loading = ref(false)
const saving = ref(false)
const rows = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = 20
const stats = ref(null)
const query = reactive({ keyword: '', isVerified: '', source: '', sortHit: false })

const editVisible = ref(false)
const editing = ref(null)
const form = reactive({ dishName: '', cuisine: '', servingGrams: 300, nutrition: {}, ingredientsText: '' })

onMounted(async () => {
  await loadStats()
  await reload()
})

async function loadStats() {
  try { stats.value = (await adminAPI.getDishNutritionStats()).data || {} } catch (e) { /* 静默 */ }
}

async function reload() {
  loading.value = true
  try {
    const params = {
      page: page.value - 1,
      size: pageSize,
      keyword: query.keyword || undefined,
      isVerified: query.isVerified === '' ? undefined : Number(query.isVerified),
      source: query.source || undefined,
      sortHit: query.sortHit,
    }
    const res = (await adminAPI.getDishNutrition(params)).data || {}
    rows.value = res.items || []
    total.value = res.total || 0
  } catch (e) {
    ElMessage.error('加载菜品库失败')
  } finally {
    loading.value = false
  }
}

function reset() {
  Object.assign(query, { keyword: '', isVerified: '', source: '', sortHit: false })
  page.value = 1
  reload()
}

function openEdit(row) {
  editing.value = row
  const n = row.nutrition || {}
  Object.assign(form, {
    dishName: row.dishName || '',
    cuisine: row.cuisine || '',
    servingGrams: row.servingGrams || 300,
    nutrition: { calories: n.calories ?? 0, protein: n.protein ?? 0, fat: n.fat ?? 0, carbs: n.carbs ?? 0 },
    ingredientsText: (row.mainIngredients || []).join(','),
  })
  editVisible.value = true
}

async function save() {
  saving.value = true
  try {
    const ingredients = form.ingredientsText.split(/[,，]/).map(s => s.trim()).filter(Boolean)
    await adminAPI.updateDishNutrition(editing.value.id, {
      dishName: form.dishName,
      cuisine: form.cuisine,
      servingGrams: form.servingGrams,
      nutrition: form.nutrition,
      ingredients,
    })
    ElMessage.success('已保存')
    editVisible.value = false
    await reload()
  } catch (e) {
    ElMessage.error(e?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function verify(row) {
  try {
    await adminAPI.verifyDishNutrition(row.id)
    ElMessage.success('已审核通过')
    await loadStats()
    await reload()
  } catch (e) {
    ElMessage.error('操作失败')
  }
}

function remove(row) {
  ElMessageBox.prompt(`确认删除菜品「${row.dishName}」？删除后进入回收站（可恢复），拍照确认将回退 AI 兜底。`, '删除确认', {
    confirmButtonText: '确认删除',
    cancelButtonText: '取消',
    inputPlaceholder: '请输入「删除」确认',
    inputValidator: (v) => (v === '删除' ? true : '请输入「删除」以确认'),
  }).then(async ({ value }) => {
    if (value !== '删除') return
    try {
      await adminAPI.deleteDishNutrition(row.id)
      ElMessage.success('已删除（可在回收站恢复）')
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
.dishdb-page { padding: 4px 2px; }
.page-head h2 { margin: 0 0 6px; }
.tip { color: var(--text-secondary); font-size: 13px; margin: 0 0 14px; }
.stat-row { display: flex; gap: 10px; margin-bottom: 14px; flex-wrap: wrap; }
.stat-card { border: 1px solid #dcdfe6; border-radius: 10px; padding: 10px 18px; background: #fff; min-width: 96px; text-align: center; }
.stat-card.stat-sub { min-width: 70px; }
.stat-num { font-size: 20px; font-weight: 600; color: var(--primary, #409eff); }
.stat-label { font-size: 12px; color: var(--text-secondary); margin-top: 2px; }
.filter-bar { display: flex; gap: 10px; margin-bottom: 14px; flex-wrap: wrap; align-items: center; }
.filter-check { font-size: 13px; color: var(--text-secondary); }
.filter-input { border: 1px solid #dcdfe6; border-radius: 6px; padding: 7px 10px; font-size: 14px; outline: none; }
.filter-input:focus { border-color: var(--primary, #409eff); }
.btn-filter { border: none; background: var(--primary, #409eff); color: #fff; border-radius: 6px; padding: 7px 16px; cursor: pointer; font-size: 14px; }
.btn-filter.ghost { background: #fff; color: #606266; border: 1px solid #dcdfe6; }
.btn-filter:hover { opacity: 0.9; }
.mt12 { margin-top: 12px; }
.edit-row { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; min-width: 0; }
.edit-row label { min-width: 76px; color: var(--text-secondary); font-size: 13px; flex-shrink: 0; white-space: nowrap; }
.edit-grid { display: grid; grid-template-columns: 1fr; gap: 0; }
.edit-row .filter-input { flex: 1 1 auto; min-width: 0; width: 0; }
</style>
