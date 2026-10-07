<template>
  <div class="recipe-create-page">
    <div class="page-head">
      <h2>{{ isEdit ? '编辑菜谱' : '录入菜谱' }}</h2>
      <router-link to="/app/recipes" class="btn-ghost">← 返回菜谱</router-link>
    </div>

    <!-- 图文链接解析（仅新建模式可改来源） -->
    <div v-if="!isEdit" class="card parse-card">
      <div class="parse-row">
        <input v-model="url" class="input flex1" placeholder="粘贴图文链接（可选，解析菜谱草稿）" @keyup.enter="parseUrl" />
        <button class="btn-primary" :disabled="parsing" @click="parseUrl">
          {{ parsing ? '解析中…' : '解析' }}
        </button>
      </div>
      <p v-if="parseMsg" :class="parseOk ? 'success-msg' : 'error-msg'">{{ parseMsg }}</p>
    </div>

    <div class="card form-card">
      <div class="form-group">
        <label>菜名 *</label>
        <input v-model="form.title" class="input" placeholder="菜名" />
      </div>
      <div class="form-group">
        <label>摘要</label>
        <textarea v-model="form.summary" class="input" rows="2" placeholder="菜谱简介"></textarea>
      </div>
      <div class="form-row">
        <div class="form-group flex1">
          <label>菜系</label>
          <input v-model="form.cuisine" class="input" placeholder="如：中式" />
        </div>
        <div class="form-group w120">
          <label>份量(人份)</label>
          <input v-model.number="form.servings" type="number" class="input" placeholder="2" />
        </div>
      </div>
      <div class="form-row">
        <div class="form-group flex1">
          <label>热量(千卡)</label>
          <input v-model.number="form.calories" type="number" class="input" placeholder="可选" />
        </div>
        <div class="form-group flex1">
          <label>口味(逗号分隔)</label>
          <input v-model="form.tasteTags" class="input" placeholder="如：咸鲜,微辣" />
        </div>
      </div>

      <div class="switch-row">
        <label class="switch">
          <input type="checkbox" v-model="form.industrial" />
          <span class="slider"></span>
        </label>
        <div class="switch-text">
          <b>工业型（精确可度量）</b>
          <span class="muted">主料须给克/毫升精确用量；每步须有计时或温度/模式</span>
        </div>
      </div>
      <div v-if="form.industrial" class="ind-hint">
        工业型保存前请确保：①每样食材都有精确用量，单位在 g/ml/个/勺/片/瓣/人份/克/毫升 内；②每一步都填了 计时 或 温度/模式。不满足时提交会被拦截并提示具体项。
      </div>
    </div>

    <!-- 食材 -->
    <div class="card">
      <div class="section-head">
        <h3>食材</h3>
        <button class="btn-ghost add" @click="addIngredient">＋ 添加食材</button>
      </div>
      <div v-for="(ing, i) in form.ingredients" :key="i" class="row-card">
        <div class="ing-row">
          <input v-model="ing.name" class="input flex2" :placeholder="`食材名称 ${i + 1} *`" />
          <input v-model="ing.amount" class="input w100" type="number" step="0.1" placeholder="用量" />
          <input v-model="ing.unit" class="input w80" placeholder="单位" />
          <button class="btn-danger" @click="removeAt(form.ingredients, i)">✕</button>
        </div>
        <div class="ing-sub">
          <label class="check"><input type="checkbox" v-model="ing.isMain" /> 主料</label>
          <input v-model="ing.note" class="input flex1" placeholder="备注（可选）" />
        </div>
      </div>
    </div>

    <!-- 步骤 -->
    <div class="card">
      <div class="section-head">
        <h3>步骤</h3>
        <button class="btn-ghost add" @click="addStep">＋ 添加步骤</button>
      </div>
      <div v-for="(st, i) in form.steps" :key="i" class="row-card">
        <div class="step-head">
          <span class="step-no">{{ i + 1 }}</span>
          <input v-model="st.summary" class="input flex1" :placeholder="`步骤概括 ${i + 1} *`" />
          <button class="btn-danger" @click="removeAt(form.steps, i)">✕</button>
        </div>
        <div class="step-params">
          <input v-model="st.timerMin" class="input w110" type="number" placeholder="计时(min)" />
          <input v-model="st.temperature" class="input w100" type="number" placeholder="温度℃" />
          <input v-model="st.mode" class="input flex1" placeholder="模式(上下火等)" />
        </div>
        <textarea v-model="st.detail" class="input" rows="2" placeholder="操作要点（可选）"></textarea>
      </div>
    </div>

    <div class="submit-bar">
      <button class="btn-primary big" :disabled="submitting" @click="submit">
        {{ submitting ? '提交中…' : '提交菜谱' }}
      </button>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref, computed, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { recipeAPI } from '@/api/recipe'

const router = useRouter()
const route = useRoute()
const isEdit = computed(() => !!route.params.id)
const editId = computed(() => (route.params.id ? Number(route.params.id) : null))
const url = ref('')
const sourceUrl = ref('')
const parsing = ref(false)
const parseMsg = ref('')
const parseOk = ref(false)
const submitting = ref(false)

const form = reactive({
  title: '',
  summary: '',
  cuisine: '',
  servings: 2,
  calories: null,
  tasteTags: '',
  industrial: false,
  ingredients: [emptyIng()],
  steps: [emptyStep()],
})

function emptyIng() {
  return { name: '', amount: null, unit: '', isMain: false, note: '' }
}
function emptyStep() {
  return { summary: '', detail: '', timerMin: null, temperature: null, mode: '' }
}
function addIngredient() { form.ingredients.push(emptyIng()) }
function addStep() { form.steps.push(emptyStep()) }
function removeAt(arr, i) {
  if (arr.length > 1) arr.splice(i, 1)
}

async function parseUrl() {
  const u = url.value.trim()
  if (!u) { parseMsg.value = '请粘贴文章链接'; parseOk.value = false; return }
  parsing.value = true
  parseMsg.value = ''
  try {
    const res = await recipeAPI.parseArticle(u)
    const d = res.data?.draft || {}
    sourceUrl.value = res.data?.sourceUrl || ''
    form.title = d.title || ''
    form.summary = d.summary || ''
    form.cuisine = d.cuisine || ''
    form.servings = d.servings || 2
    form.calories = d.calories ?? null
    form.tasteTags = Array.isArray(d.tasteTags) ? d.tasteTags.join(',') : (d.tasteTags || '')
    form.industrial = d.industrial === true
    const ings = d.ingredients || []
    form.ingredients = ings.length
      ? ings.map(x => ({ name: x.name || '', amount: x.amount ?? null, unit: x.unit || '', isMain: !!x.isMain, note: x.note || '' }))
      : [emptyIng()]
    const steps = d.steps || []
    form.steps = steps.length
      ? steps.map(x => ({ summary: x.summary || '', detail: x.detail || '', timerMin: x.timerMin ?? null, temperature: x.temperature ?? null, mode: x.mode || '' }))
      : [emptyStep()]
    const emptyDraft = !form.title.trim() && !ings.length && !steps.length
    parseMsg.value = emptyDraft ? '未能从该链接解析出菜谱内容，请手动填写或更换链接' : '解析完成，请核对后保存'
    parseOk.value = !emptyDraft
  } catch (e) {
    parseMsg.value = e?.message || '解析失败，请稍后重试'
    parseOk.value = false
  } finally {
    parsing.value = false
  }
}

function cleanIng(ing) {
  return {
    name: ing.name.trim(),
    amount: ing.amount === '' || ing.amount == null ? null : Number(ing.amount),
    unit: ing.unit.trim(),
    tolerance: null,
    isMain: !!ing.isMain,
    note: ing.note?.trim() || null,
  }
}
function cleanStep(st) {
  return {
    summary: st.summary.trim(),
    detail: st.detail?.trim() || '',
    timerMin: st.timerMin === '' || st.timerMin == null ? null : Number(st.timerMin),
    temperature: st.temperature === '' || st.temperature == null ? null : Number(st.temperature),
    mode: st.mode?.trim() || '',
  }
}

async function submit() {
  if (!form.title.trim()) { parseMsg.value = '请填写菜名'; parseOk.value = false; return }
  const ingredients = form.ingredients.filter(x => x.name.trim()).map(cleanIng)
  const steps = form.steps.filter(x => x.summary.trim()).map(cleanStep)
  if (!ingredients.length) { parseMsg.value = '请至少填写一种食材'; parseOk.value = false; return }
  if (!steps.length) { parseMsg.value = '请至少填写一个步骤'; parseOk.value = false; return }

  if (form.industrial) {
    const units = ['g', 'ml', '个', '勺', '片', '瓣', '人份', '克', '毫升']
    const qual = ['适量', '少许', '一把', '若干', '少量', '一点', '一点点', '略']
    for (const ig of ingredients) {
      if (ig.amount == null) { parseMsg.value = `工业型：食材「${ig.name}」需给出精确用量（克/毫升）`; parseOk.value = false; return }
      if (!units.includes(ig.unit.toLowerCase())) { parseMsg.value = `工业型：食材「${ig.name}」用量单位须为 ${units.join('/')}`; parseOk.value = false; return }
      for (const q of qual) {
        if (String(ig.amount).includes(q)) { parseMsg.value = `工业型：食材「${ig.name}」不能使用「${q}」等定性描述`; parseOk.value = false; return }
      }
    }
    for (let i = 0; i < steps.length; i++) {
      const s = steps[i]
      if (s.timerMin == null && s.temperature == null && !s.mode) {
        parseMsg.value = `工业型：第 ${i + 1} 步需填写 计时(min) 或 温度℃/模式`
        parseOk.value = false
        return
      }
    }
  }

  const body = {
    title: form.title.trim(),
    summary: form.summary.trim() || null,
    cuisine: form.cuisine.trim() || null,
    servings: form.servings ? Number(form.servings) : null,
    calories: form.calories ? Number(form.calories) : null,
    tasteTags: form.tasteTags
      ? form.tasteTags.split(/[,，]/).map(x => x.trim()).filter(Boolean)
      : null,
    industrial: form.industrial,
    // 解析自图文链接则标记来源 ARTICLE_LINK + sourceUrl（与 APP 一致）
    ...(sourceUrl.value ? { source: 'ARTICLE_LINK', sourceUrl: sourceUrl.value } : {}),
    ingredients,
    steps,
  }

  submitting.value = true
  try {
    let id = null
    if (isEdit.value) {
      await recipeAPI.updateRecipe(editId.value, body)
      id = editId.value
    } else {
      const res = await recipeAPI.createRecipe(body)
      id = res.data?.id
    }
    parseMsg.value = form.industrial ? '保存成功（工业型已强校验）' : '保存成功'
    parseOk.value = true
    router.push(id ? `/recipes/${id}` : '/recipes')
  } catch (e) {
    parseMsg.value = e?.message || '保存失败，请稍后重试'
    parseOk.value = false
  } finally {
    submitting.value = false
  }
}

// 编辑模式：加载现有菜谱并预填表单
async function loadForEdit() {
  if (!isEdit.value) return
  try {
    const res = await recipeAPI.getDetail(editId.value)
    const d = res.data || {}
    sourceUrl.value = d.sourceUrl || ''
    form.title = d.title || ''
    form.summary = d.summary || ''
    form.cuisine = d.cuisine || ''
    form.servings = d.servings || 2
    form.calories = d.calories ?? null
    form.tasteTags = Array.isArray(d.tasteTags) ? d.tasteTags.join(',') : (d.tasteTags || '')
    form.industrial = d.recipeType === 'INDUSTRIAL'
    const ings = d.ingredients || []
    form.ingredients = ings.length
      ? ings.map(x => ({ name: x.name || '', amount: x.amount ?? null, unit: x.unit || '', isMain: !!x.isMain, note: x.note || '' }))
      : [emptyIng()]
    const steps = d.steps || []
    form.steps = steps.length
      ? steps.map(x => ({ summary: x.summary || '', detail: x.detail || '', timerMin: x.timerMin ?? null, temperature: x.temperature ?? null, mode: x.mode || '' }))
      : [emptyStep()]
  } catch (e) {
    parseMsg.value = e?.message || '加载菜谱失败'
    parseOk.value = false
  }
}
onMounted(loadForEdit)
</script>

<style scoped>
.recipe-create-page { padding: 24px; max-width: 860px; }
.page-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px; }
.page-head h2 { margin: 0; font-size: 24px; }
.card { background: #fff; border-radius: var(--radius, 8px); box-shadow: var(--shadow, 0 2px 8px rgba(0,0,0,0.08)); padding: 18px; margin-bottom: 16px; }
.parse-row { display: flex; gap: 10px; }
.form-group { margin-bottom: 14px; }
.form-group label { display: block; font-size: 13px; color: #666; margin-bottom: 6px; }
.form-row { display: flex; gap: 16px; }
.flex1 { flex: 1; }
.flex2 { flex: 2; }
.w80 { width: 80px; }
.w100 { width: 100px; }
.w110 { width: 110px; }
.w120 { width: 120px; }
.input { width: 100%; padding: 9px 12px; border: 1px solid var(--border-color, #e4e7ed); border-radius: var(--radius, 6px); font-size: 14px; box-sizing: border-box; }
textarea.input { resize: vertical; font-family: inherit; }
.btn-primary { padding: 9px 20px; background: var(--primary, #409eff); color: #fff; border: none; border-radius: var(--radius, 6px); cursor: pointer; font-size: 14px; white-space: nowrap; }
.btn-primary:hover { opacity: 0.9; }
.btn-primary:disabled { opacity: 0.5; cursor: not-allowed; }
.btn-primary.big { padding: 12px 48px; font-size: 16px; }
.btn-ghost { padding: 8px 16px; background: transparent; border: 1px solid var(--border-color, #e4e7ed); border-radius: var(--radius, 6px); color: var(--text-primary, #333); cursor: pointer; font-size: 13px; text-decoration: none; }
.btn-ghost.add { color: var(--primary, #409eff); border-color: var(--primary, #409eff); }
.btn-danger { padding: 6px 10px; background: transparent; border: 1px solid #f5c6cb; color: #c0392b; border-radius: 6px; cursor: pointer; }
.success-msg { color: #155724; font-size: 13px; margin: 10px 0 0; }
.error-msg { color: #c0392b; font-size: 13px; margin: 10px 0 0; }
.switch-row { display: flex; align-items: center; gap: 12px; margin-top: 6px; }
.switch { position: relative; display: inline-block; width: 44px; height: 24px; flex-shrink: 0; }
.switch input { opacity: 0; width: 0; height: 0; }
.slider { position: absolute; inset: 0; background: #ccc; border-radius: 24px; transition: 0.2s; }
.slider::before { content: ''; position: absolute; height: 18px; width: 18px; left: 3px; top: 3px; background: #fff; border-radius: 50%; transition: 0.2s; }
.switch input:checked + .slider { background: var(--primary, #409eff); }
.switch input:checked + .slider::before { transform: translateX(20px); }
.switch-text b { font-size: 15px; }
.switch-text .muted { display: block; font-size: 12px; color: #999; margin-top: 2px; }
.ind-hint { margin-top: 10px; padding: 10px 12px; background: #fff8e1; border: 1px solid #ffe082; border-radius: 8px; color: #b26a00; font-size: 12px; }
.section-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.section-head h3 { margin: 0; }
.row-card { border: 1px solid var(--border-color, #eef0f2); border-radius: 8px; padding: 12px; margin-bottom: 10px; }
.ing-row { display: flex; gap: 8px; align-items: center; }
.ing-sub { display: flex; align-items: center; gap: 10px; margin-top: 8px; }
.check { display: flex; align-items: center; gap: 4px; font-size: 13px; color: #555; white-space: nowrap; }
.step-head { display: flex; align-items: center; gap: 8px; }
.step-no { width: 26px; height: 26px; border-radius: 50%; background: #fff0e6; color: #e8590c; display: flex; align-items: center; justify-content: center; font-size: 13px; font-weight: 600; flex-shrink: 0; }
.step-params { display: flex; gap: 8px; margin: 8px 0; }
.submit-bar { text-align: center; margin: 20px 0 40px; }
</style>
