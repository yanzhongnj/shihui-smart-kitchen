<template>
  <div class="admin-content">
    <!-- ===== 卡路里分级口径 ===== -->
    <section class="section-card">
      <h3>🌡️ 卡路里分级口径</h3>
      <div v-if="levels.length === 0" class="empty"><p>加载中...</p></div>
      <div v-else class="levels">
        <span v-for="l in levels" :key="l.level" class="level-chip" :style="{ borderColor: l.color || '#888', color: l.color || '#333' }">
          {{ l.level }}：{{ l.label }}（{{ l.min ?? 0 }} ~ {{ l.max ?? '∞' }} 千卡）
        </span>
      </div>
    </section>

    <!-- ===== 食物检索 ===== -->
    <section class="section-card">
      <h3>🥬 食物营养库 <span class="chip-tip">共 {{ total }} 条（核心 / 自定义 / AI 回填，每 100g 营养）</span></h3>
      <div class="filter-bar">
        <input v-model="keyword" class="filter-input" placeholder="搜索食材名..." @keyup.enter="apply" />
        <select v-model="category" class="filter-select" @change="apply">
          <option value="">全部分类</option>
          <option v-for="c in categories" :key="c.code" :value="c.code">{{ c.name }}</option>
        </select>
        <select v-model="source" class="filter-select" @change="apply">
          <option value="">全部来源</option>
          <option value="seed">核心(seed)</option>
          <option value="user">用户自定义</option>
          <option value="ai">AI 回填</option>
        </select>
        <button v-if="keyword || category || source" class="btn-clear" @click="clear">✕ 清除</button>
      </div>
      <el-table v-loading="loading" :data="foods" border stripe style="width: 100%"
                empty-text="暂无食材">
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="name" label="名称" min-width="150">
          <template #default="{ row }">
            <span class="food-name">{{ row.name }}</span>
          </template>
        </el-table-column>
        <el-table-column label="分类" min-width="100">
          <template #default="{ row }">{{ row.category || '-' }}</template>
        </el-table-column>
        <el-table-column label="热量/100g" width="100">
          <template #default="{ row }">{{ fmtNum(row.calories) }}</template>
        </el-table-column>
        <el-table-column label="蛋白" width="80">
          <template #default="{ row }">{{ fmtNum(row.protein) }}</template>
        </el-table-column>
        <el-table-column label="脂肪" width="80">
          <template #default="{ row }">{{ fmtNum(row.fat) }}</template>
        </el-table-column>
        <el-table-column label="碳水" width="80">
          <template #default="{ row }">{{ fmtNum(row.carbs) }}</template>
        </el-table-column>
        <el-table-column label="来源" width="90">
          <template #default="{ row }">
            <el-tag :type="row.source === 'ai' ? 'warning' : row.source === 'user' ? 'primary' : 'success'" size="small">{{ sourceLabel(row.source) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" size="small" @click="removeFood(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination v-if="totalPages > 1" background layout="total, sizes, prev, pager, next"
        :total="total" :current-page="currentPage + 1" :page-size="pageSize"
        :page-sizes="[10, 20, 50]"
        @current-change="onPageChange" @size-change="onSizeChange" class="ep-pagination" />
      <div v-else-if="!loading && foods.length > 0" class="page-total center">共 {{ total }} 条</div>
    </section>

    <!-- ===== 编辑弹窗 ===== -->
    <div v-if="editing" class="modal-mask" @click.self="editing = null">
      <div class="modal">
        <h3>✏️ 编辑食材「{{ editing.name }}」</h3>
        <div class="form-grid">
          <label>分类
            <select v-model="editForm.category">
              <option v-for="c in categories" :key="c.code" :value="c.code">{{ c.name }}</option>
            </select>
          </label>
          <label>热量(千卡/100g)<input v-model.number="editForm.calories" type="number" step="0.1" /></label>
          <label>蛋白(g)<input v-model.number="editForm.protein" type="number" step="0.1" /></label>
          <label>脂肪(g)<input v-model.number="editForm.fat" type="number" step="0.1" /></label>
          <label>碳水(g)<input v-model.number="editForm.carbs" type="number" step="0.1" /></label>
          <label>纤维(g)<input v-model.number="editForm.fiber" type="number" step="0.1" /></label>
          <label>钠(mg)<input v-model.number="editForm.sodium" type="number" step="1" /></label>
        </div>
        <div class="modal-actions">
          <button class="btn-plain" @click="editing = null">取消</button>
          <button class="btn-primary" :disabled="busy.save" @click="saveEdit">保存</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { foodAPI } from '@/api/admin'

const keyword = ref('')
const category = ref('')
const source = ref('')
const foods = ref([])
const categories = ref([])
const levels = ref([])
const loading = ref(false)
const currentPage = ref(0)
const total = ref(0)
const totalPages = ref(0)
const pageSize = ref(10)

const editing = ref(null)
const editForm = reactive({ category: '', calories: null, protein: null, fat: null, carbs: null, fiber: null, sodium: null })
const busy = reactive({ save: false })

onMounted(async () => {
  try {
    const [cats, lv] = await Promise.all([foodAPI.getCategories(), foodAPI.getCalorieLevels()])
    categories.value = cats.data || []
    levels.value = lv.data || []
  } catch (e) { /* 守卫已处理 */ }
  load()
})

async function load() {
  loading.value = true
  try {
    const res = await foodAPI.searchFoods({
      page: currentPage.value, size: pageSize.value,
      keyword: keyword.value || undefined,
      category: category.value || undefined,
      source: source.value || undefined,
    })
    foods.value = res.data?.items || []
    total.value = res.data?.total || 0
    totalPages.value = Math.max(1, Math.ceil(total.value / pageSize.value))
  } catch (e) { alert(e?.message || '加载失败') } finally { loading.value = false }
}
function apply() { currentPage.value = 0; load() }
function clear() { keyword.value = ''; category.value = ''; source.value = ''; currentPage.value = 0; load() }
function onPageChange(p) { currentPage.value = p - 1; load() }
function onSizeChange(s) { pageSize.value = s; currentPage.value = 0; load() }

function openEdit(f) {
  editing.value = f
  editForm.category = f.category || ''
  editForm.calories = f.calories != null ? Number(f.calories) : null
  editForm.protein = f.protein != null ? Number(f.protein) : null
  editForm.fat = f.fat != null ? Number(f.fat) : null
  editForm.carbs = f.carbs != null ? Number(f.carbs) : null
  editForm.fiber = f.fiber != null ? Number(f.fiber) : null
  editForm.sodium = f.sodium != null ? Number(f.sodium) : null
}
async function saveEdit() {
  busy.save = true
  try {
    await foodAPI.updateFood(editing.value.id, { ...editForm })
    editing.value = null
    load()
  } catch (e) { alert(e?.message || '保存失败') } finally { busy.save = false }
}
async function removeFood(f) {
  if (!confirm(`确认删除食材「${f.name}」吗？`)) return
  try { await foodAPI.deleteFood(f.id); load() } catch (e) { alert(e?.message || '删除失败') }
}

function sourceLabel(s) { return { seed: '核心', user: '用户', ai: 'AI' }[s] || s || '-' }
function fmtNum(v) { return v == null ? '-' : Number(v).toFixed(1) }
</script>

<style scoped>
.admin-content { padding: 24px; }
.section-card { background: #fff; border-radius: 8px; box-shadow: 0 1px 4px rgba(0,0,0,0.08); padding: 18px 20px; margin-bottom: 20px; }
.section-card h3 { margin: 0 0 14px; font-size: 16px; }
.chip-tip { font-size: 12px; color: var(--text-secondary); font-weight: 400; margin-left: 8px; }
.levels { display: flex; flex-wrap: wrap; gap: 10px; }
.level-chip { padding: 6px 14px; border: 1px solid; border-radius: 16px; font-size: 13px; font-weight: 600; background: #fff; }
.filter-bar { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 14px; align-items: center; }
.filter-input { padding: 8px 12px; border: 1px solid var(--border-color); border-radius: 6px; font-size: 14px; min-width: 200px; }
.filter-select { padding: 8px 10px; border: 1px solid var(--border-color); border-radius: 6px; font-size: 14px; }
.btn-clear { padding: 8px 12px; border: 1px solid var(--border-color); border-radius: 6px; background: #fff; cursor: pointer; }
.table { background: #fff; border-radius: 8px; box-shadow: 0 1px 4px rgba(0,0,0,0.06); overflow: hidden; }
.table-header, .table-row { display: grid; gap: 8px; padding: 10px 14px; align-items: center; font-size: 13px; }
.table-header { background: #fafafa; font-weight: 600; color: var(--text-regular); }
.table-row { border-top: 1px solid #f0f0f0; }
.table-row:hover { background: #fafcff; }
.table-header.food, .table-row.food { grid-template-columns: 50px 1.5fr 1fr 1fr 1fr 1fr 1fr 100px 150px; }
.food-name { font-weight: 500; }
.badge { padding: 3px 10px; border-radius: 12px; font-size: 12px; }
.badge.seed { background: #e6f7ed; color: #1a7f37; }
.badge.user { background: #e8f0fe; color: #1a56db; }
.badge.ai { background: #fdf3e7; color: #c7741e; }
.action-btn { padding: 4px 10px; border-radius: 6px; border: 1px solid var(--border-color); background: #fff; cursor: pointer; font-size: 12px; }
.action-btn.delete { color: #c0392b; border-color: #e5b8b4; }
.col-actions { display: flex; gap: 6px; }
.loading { text-align: center; color: var(--text-secondary); padding: 24px 0; }
.empty { text-align: center; color: var(--text-secondary); padding: 24px 0; }
.page-total.center { text-align: center; margin-top: 10px; font-size: 13px; color: var(--text-secondary); }
.ep-pagination { margin-top: 16px; justify-content: center; }
.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,0.4); display: flex; align-items: center; justify-content: center; z-index: 100; }
.modal { background: #fff; border-radius: 10px; padding: 22px 26px; width: 480px; max-width: 92vw; max-height: 88vh; overflow: auto; }
.modal h3 { margin: 0 0 16px; font-size: 17px; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.form-grid label { display: flex; flex-direction: column; gap: 4px; font-size: 13px; color: var(--text-secondary); }
.form-grid input, .form-grid select { padding: 8px 10px; border: 1px solid var(--border-color); border-radius: 6px; font-size: 14px; }
.modal-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 18px; }
.btn-plain { padding: 8px 16px; border: 1px solid var(--border-color); border-radius: 6px; background: #fff; cursor: pointer; }
.btn-primary { padding: 8px 16px; border: none; border-radius: 6px; background: var(--primary, #409eff); color: #fff; cursor: pointer; }
.btn-primary:disabled { opacity: 0.5; cursor: not-allowed; }
</style>
