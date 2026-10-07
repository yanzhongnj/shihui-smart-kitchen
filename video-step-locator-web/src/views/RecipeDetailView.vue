<template>
  <div class="recipe-detail-page" v-if="recipe">
    <div class="detail-header">
      <div>
        <h2>{{ recipe.title }}</h2>
        <div class="detail-meta">
          <span v-if="recipe.cuisine" class="meta-chip">🍽 {{ recipe.cuisine }}</span>
          <span v-if="recipe.spiceLevel != null" class="meta-chip">🌶 辣度 {{ recipe.spiceLevel }}</span>
          <span v-if="recipe.estimatedTime" class="meta-chip">⏱ {{ recipe.estimatedTime }}分钟</span>
          <span v-if="recipe.calories" class="meta-chip">🔥 {{ recipe.calories }}千卡</span>
          <span v-if="recipe.tasteTags" class="meta-chip">口味：{{ recipe.tasteTags }}</span>
        </div>
        <div class="detail-badges">
          <span class="source-badge" :class="sourceClass(recipe)">{{ sourceLabel(recipe) }}</span>
          <span class="type-badge" :class="recipe.recipeType === 'INDUSTRIAL' ? 'industrial' : 'home'">
            {{ recipe.recipeType === 'INDUSTRIAL' ? '工业级精确' : '家常型' }}
          </span>
          <span v-if="recipe.precisionScore != null" class="score-badge">
            精确化评分 {{ Math.round(recipe.precisionScore * 100) }}/100
          </span>
        </div>
      </div>
      <div class="header-actions">
        <button v-if="canManage" class="btn-edit" @click="$router.push(`/app/recipes/${recipe.id}/edit`)">✏️ 编辑</button>
        <button v-if="canManage" class="btn-primary" :disabled="upgrading" @click="confirmUpgrade">
          {{ upgrading ? '精化中…' : '⚡ 精确化增强' }}
        </button>
        <button v-if="canManage" class="btn-danger" @click="confirmDelete">🗑 删除菜谱</button>
        <button v-if="recipe.videoId" class="btn-primary" @click="$router.push(`/app/videos/${recipe.videoId}`)">
          🎬 查看关联视频
        </button>
      </div>
    </div>

    <!-- 兼容性提示 -->
    <div v-if="compat" :class="['compat-card', compat.level]">
      <span class="compat-title">
        {{ compatText }}
      </span>
      <ul v-if="compat.reasons && compat.reasons.length" class="compat-reasons">
        <li v-for="(reason, i) in compat.reasons" :key="i">{{ reason }}</li>
      </ul>
      <button v-else-if="!compatChecked" class="compat-calc" @click="calcCompat">计算口味匹配</button>
    </div>
    <div v-else class="compat-card none">
      <span>登录后可查看口味/忌口匹配</span>
    </div>

    <!-- 过敏原警示 -->
    <div v-if="recipe.allergens" class="allergen-warn">
      ⚠️ 含过敏原：{{ recipe.allergens }}
    </div>

    <!-- 操作步骤（steps_json，图文/创作菜谱核心内容） -->
    <div v-if="steps.length" class="section-card">
      <h3>操作步骤 <span class="chip-tip">共 {{ steps.length }} 步</span></h3>
      <div v-for="(s, i) in steps" :key="i" class="step-block">
        <div class="step-no">{{ i + 1 }}</div>
        <div class="step-body">
          <div class="step-summary" v-if="s.summary">{{ s.summary }}</div>
          <div class="step-badges">
            <span v-if="s.timerMin != null" class="step-badge">⏱ {{ s.timerMin }} 分钟</span>
            <span v-if="s.temperature != null" class="step-badge">🌡 {{ s.temperature }} ℃</span>
            <span v-if="s.mode" class="step-badge">⚙ {{ s.mode }}</span>
          </div>
          <div v-if="s.detail" class="step-detail">{{ s.detail }}</div>
        </div>
      </div>
    </div>

    <!-- 简介 -->
    <div v-if="recipe.summary" class="section-card">
      <h3>简介</h3>
      <p>{{ recipe.summary }}</p>
    </div>

    <!-- 食材明细（含用量/单位/主料） -->
    <div v-if="ingredients.length" class="section-card">
      <h3>食材</h3>
      <table class="ing-table">
        <thead><tr><th>食材</th><th>用量</th><th>单位</th><th>角色</th></tr></thead>
        <tbody>
          <tr v-for="(ig, i) in ingredients" :key="i">
            <td>{{ ig.name }}<span v-if="ig.note" class="ing-note">（{{ ig.note }}）</span></td>
            <td>{{ ig.amount ?? '-' }}</td>
            <td>{{ ig.unit || '-' }}</td>
            <td><span :class="ig.isMain ? 'main-tag' : 'aux-tag'">{{ ig.isMain ? '主料' : '辅料' }}</span></td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- ⚖️ 分量换算（按人数 / 按模具，P2） -->
    <div class="section-card">
      <h3>⚖️ 分量换算 <span class="chip-tip">按人数 / 按模具</span></h3>
      <div class="scale-controls">
        <span class="scale-mode">
          <button :class="{ active: scaleMode === 'people' }" @click="setScaleMode('people')">按人数</button>
          <button :class="{ active: scaleMode === 'mold' }" @click="setScaleMode('mold')">按模具</button>
        </span>
        <template v-if="scaleMode === 'people'">
          <span class="stepper">
            <button @click="adjustServings(-1)" :disabled="scaleServings <= 1">−</button>
            <input v-model.number="scaleServings" type="number" min="1" class="scale-input" />
            <button @click="adjustServings(1)">＋</button>
          </span>
          <span class="scale-unit">人份</span>
          <button class="btn-primary" :disabled="scaleLoading" @click="applyScale">换算</button>
        </template>
        <template v-else>
          <select v-model="scaleShape" class="select">
            <option value="round">圆模（直径 cm）</option>
            <option value="square">方模（边长 cm）</option>
            <option value="rect">长方模（长×宽 cm）</option>
          </select>
          <input v-model.number="scaleSize1" type="number" class="scale-input" placeholder="尺寸1" />
          <input v-if="scaleShape === 'rect'" v-model.number="scaleSize2" type="number" class="scale-input" placeholder="尺寸2" />
          <button class="btn-primary" :disabled="scaleLoading" @click="applyScale">换算</button>
        </template>
      </div>
      <div v-if="scaleResult" class="scale-result">
        <div class="scale-meta">倍率 ×{{ scaleResult.factor }}
          <span v-if="scaleResult.mode === 'mold'">（基准 {{ scaleResult.baseMold }}）</span>
          <span v-else>（基准 {{ scaleResult.baseServings }} 人份 → {{ scaleResult.servings }} 人份）</span>
        </div>
        <table class="ing-table">
          <thead><tr><th>食材</th><th>原用量</th><th>换算后</th></tr></thead>
          <tbody>
            <tr v-for="(ig, i) in scaleResult.ingredients" :key="i">
              <td>{{ ig.name }}</td>
              <td>{{ ig.amount ?? '-' }} {{ ig.unit || '' }}</td>
              <td><b>{{ ig.scaledAmount ?? '-' }} {{ ig.unit || '' }}</b></td>
            </tr>
          </tbody>
        </table>
        <div v-if="scaleResult.nutrition" class="scale-nutrition">
          营养：热量 {{ scaleResult.nutrition.calories ?? 0 }} 千卡 · 蛋白 {{ scaleResult.nutrition.protein ?? 0 }}g · 脂肪 {{ scaleResult.nutrition.fat ?? 0 }}g · 碳水 {{ scaleResult.nutrition.carbs ?? 0 }}g
        </div>
      </div>
    </div>

    <!-- 主料 -->
    <div v-if="recipe.mainIngredients" class="section-card">
      <h3>主料</h3>
      <div class="ingredient-tags">
        <span v-for="ing in splitList(recipe.mainIngredients)" :key="ing" class="ing-tag">{{ ing }}</span>
      </div>
    </div>

    <!-- 辅料 -->
    <div v-if="recipe.auxiliaryIngredients" class="section-card">
      <h3>辅料</h3>
      <div class="ingredient-tags">
        <span v-for="ing in splitList(recipe.auxiliaryIngredients)" :key="ing" class="ing-tag">{{ ing }}</span>
      </div>
    </div>

    <!-- 营养估算 -->
    <div v-if="nutrition" class="section-card">
      <h3>营养估算 <span class="chip-tip">基于精确用量</span></h3>
      <div class="nutri-grid">
        <div class="nutri-item"><b>{{ nutrition.calories ?? 0 }}</b><span>热量(千卡)</span></div>
        <div class="nutri-item"><b>{{ nutrition.protein ?? 0 }} g</b><span>蛋白</span></div>
        <div class="nutri-item"><b>{{ nutrition.fat ?? 0 }} g</b><span>脂肪</span></div>
        <div class="nutri-item"><b>{{ nutrition.carbs ?? 0 }} g</b><span>碳水</span></div>
      </div>
      <div v-if="nutrition.estimated === true" class="muted">（部分食材未计入）</div>
    </div>

    <!-- 溯源 -->
    <div v-if="provenance" class="section-card">
      <h3>溯源</h3>
      <div v-if="provenance.source" class="prov-line"><span class="prov-label">来源</span>{{ provenance.source }}</div>
      <div v-if="provenance.sourceUrl" class="prov-line">
        <span class="prov-label">来源链接</span>
        <a :href="provenance.sourceUrl" target="_blank" rel="noopener">{{ provenance.sourceUrl }}</a>
      </div>
      <div v-if="provenance.extractorVersion" class="prov-line"><span class="prov-label">提取版本</span>{{ provenance.extractorVersion }}</div>
      <div v-if="provenance.extractedAt" class="prov-line"><span class="prov-label">提取时间</span>{{ provenance.extractedAt }}</div>
    </div>

    <!-- 🔥 厨电联动（A3）：菜谱设备参数 → 我的设备 → 一键下发 -->
    <div v-if="deviceParams.length" class="section-card">
      <h3>🔥 厨电联动 <span class="chip-tip">按步骤匹配设备参数</span></h3>
      <div v-for="p in deviceParams" :key="p.id" class="device-param-block">
        <div class="device-param-row">
          <div class="param-info">
            <b>{{ deviceTypeLabel(p.deviceType) }}</b>
            <span class="param-desc">{{ paramDesc(p) }}</span>
            <span v-if="p.note" class="param-note">{{ p.note }}</span>
          </div>
          <div class="param-action">
            <select v-if="matchDevices(p).length" v-model="selectedDevice[p.id]" class="select">
              <option v-for="d in matchDevices(p)" :key="d.id" :value="d.id">{{ d.nickname || d.vendorDeviceId }}</option>
            </select>
            <button class="btn-primary" :disabled="sending[p.id] || !selectedDevice[p.id]"
                    @click="sendParam(p)">
              {{ sending[p.id] ? '下发中…' : '一键下发' }}
            </button>
          </div>
        </div>
        <div v-if="sendResult[p.id]" class="send-msg">{{ sendResult[p.id] }}</div>
      </div>
      <div v-if="!myDevices.length" class="muted">
        暂无绑定设备，请到「设备控制」页绑定模拟设备后体验一键下发。
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { recipeAPI } from '@/api/recipe'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const recipe = ref(null)
const compat = ref(null)
const compatChecked = ref(false)
const steps = ref([])
const ingredients = ref([])
const nutrition = ref(null)
const provenance = ref(null)
// A3 厨电联动
const deviceParams = ref([])
const myDevices = ref([])
const selectedDevice = ref({})
const sending = ref({})
const sendResult = ref({})

const canManage = computed(() => {
  const u = authStore.user
  if (!u) return false
  if (u.role === 'ADMIN') return true
  const r = recipe.value
  return r && r.userId != null && r.userId === u.id
})

const compatText = computed(() => {
  if (!compat.value) return ''
  const level = compat.value.level
  return level === 'RED'
    ? '❌ 与您的口味/忌口冲突，请谨慎选择'
    : level === 'YELLOW'
      ? '⚠️ 与您的偏好不完全匹配'
      : '✅ 与您的偏好匹配，推荐'
})

function splitList(str) {
  return String(str || '').split(',').map(s => s.trim()).filter(Boolean)
}

function sourceLabel(r) {
  const s = r.source || r.sourceType
  if (s === 'ARTICLE_LINK') return '图文录入'
  if (s === 'USER') return '手动录入'
  if (s === 'AI_CREATE' || r.sourceType === 'ai_creation') return 'AI创作'
  if (s === 'VIDEO_LINK') return '视频菜谱'
  return s || '菜谱'
}
function sourceClass(r) {
  const s = r.source
  if (s === 'ARTICLE_LINK') return 'article'
  if (s === 'USER') return 'user'
  if (s === 'AI_CREATE' || r.sourceType === 'ai_creation') return 'ai'
  return 'video'
}

async function confirmDelete() {
  if (!recipe.value) return
  if (!window.confirm(`确定删除「${recipe.value.title}」吗？删除后不可恢复。`)) return
  try {
    await recipeAPI.deleteRecipe(recipe.value.id)
    router.push('/app/recipes')
  } catch (e) {
    alert(e?.message || '删除失败')
  }
}

// ---- A3 厨电联动 ----
function deviceTypeLabel(t) {
  return ({ oven: '烤箱', steamer: '蒸箱', cooktop: '集成灶', airfryer: '空气炸锅' })[t] || t
}
function paramDesc(p) {
  let s = p.mode || '自动'
  if (p.temperature) s += ' · ' + p.temperature + '℃'
  if (p.durationMin) s += ' · ' + p.durationMin + '分钟'
  return s
}
function matchDevices(p) {
  return myDevices.value.filter(d => d.deviceType === p.deviceType || p.deviceType === 'oven')
}

async function loadDeviceParams() {
  try {
    const { orgAPI } = await import('@/api/org')
    // 设备参数取自菜谱详情响应（后端已按步骤派生并返回 deviceParams），设备列表独立拉取
    const dv = await orgAPI.getDevices({ page: 0, size: 100 })
    deviceParams.value = recipe.value?.deviceParams || []
    myDevices.value = dv.data?.items || []
    // 预选每参数第一台匹配设备
    for (const p of deviceParams.value) {
      const m = matchDevices(p)
      if (m.length) selectedDevice.value[p.id] = m[0].id
    }
  } catch (e) { console.error('[设备参数加载失败]', e) }
}

async function sendParam(p) {
  const deviceId = selectedDevice.value[p.id]
  if (!deviceId) return
  sending.value = { ...sending.value, [p.id]: true }
  sendResult.value = { ...sendResult.value, [p.id]: '' }
  try {
    const { orgAPI } = await import('@/api/org')
    const res = await orgAPI.sendDeviceCommand(deviceId, { paramId: p.id })
    sendResult.value = { ...sendResult.value, [p.id]: '✅ ' + (res.data?.message || '指令已下发') }
  } catch (e) {
    sendResult.value = { ...sendResult.value, [p.id]: '❌ ' + (e.message || '下发失败') }
  } finally {
    sending.value = { ...sending.value, [p.id]: false }
  }
}

async function calcCompat() {
  try {
    const res = await recipeAPI.getCompatibility(route.params.id)
    const data = res.data || {}
    compat.value = data.compatibility || null
    compatChecked.value = true
  } catch (e) {
    alert(e?.message || '计算口味匹配失败')
  }
}

async function loadDetail() {
  try {
    const res = await recipeAPI.getDetail(route.params.id)
    recipe.value = res.data
    steps.value = res.data?.steps || []
    ingredients.value = res.data?.ingredients || []
    nutrition.value = res.data?.nutrition || null
    // 登录用户自动附带兼容性（后端在 list/detail 中已计算）
    if (authStore.isLoggedIn && recipe.value.compatibility) {
      compat.value = recipe.value.compatibility
      compatChecked.value = true
    }
    if (authStore.isLoggedIn) {
      loadDeviceParams()
      loadProvenance()
    }
  } catch (e) {
    console.error('[菜谱详情加载失败]', e)
  }
}

// ---- P2 精确化增强 ----
const upgrading = ref(false)
async function confirmUpgrade() {
  if (!recipe.value) return
  if (!window.confirm(`对「${recipe.value.title}」执行精确化增强？\n将调用 LLM（DeepSeek）精化食材用量并重算评分，会消耗 token（计费）。`)) return
  upgrading.value = true
  try {
    const res = await recipeAPI.upgradeRecipe(recipe.value.id)
    const d = res.data || {}
    const score = d.precisionScore != null ? Math.round(d.precisionScore * 100) + '/100' : '-'
    const type = d.recipeType === 'INDUSTRIAL' ? '工业级' : '家常型'
    alert(`✅ 精确化增强完成\n评分：${score}\n类型：${type}`)
    await loadDetail()
  } catch (e) { alert(e?.message || '精确化增强失败') } finally { upgrading.value = false }
}

// ---- P2 分量换算（按人数 / 按模具） ----
const scaleMode = ref('people')
const scaleServings = ref(2)
const scaleShape = ref('round')
const scaleSize1 = ref(18)
const scaleSize2 = ref(24)
const scaleResult = ref(null)
const scaleLoading = ref(false)
function setScaleMode(m) { scaleMode.value = m; scaleResult.value = null }
function adjustServings(d) { scaleServings.value = Math.max(1, scaleServings.value + d); scaleResult.value = null }
async function applyScale() {
  if (!recipe.value) return
  scaleLoading.value = true
  try {
    const params = scaleMode.value === 'mold'
      ? { mode: 'mold', shape: scaleShape.value, size1: scaleSize1.value, size2: scaleShape.value === 'rect' ? scaleSize2.value : undefined }
      : { servings: scaleServings.value }
    const res = await recipeAPI.getScale(recipe.value.id, params)
    scaleResult.value = res.data || null
  } catch (e) { alert(e?.message || '分量换算失败') } finally { scaleLoading.value = false }
}

onMounted(loadDetail)

async function loadProvenance() {
  try {
    const res = await recipeAPI.getProvenance(route.params.id)
    provenance.value = res.data
  } catch (e) {
    console.error('[溯源加载失败]', e)
  }
}
</script>

<style scoped>
.recipe-detail-page { padding: 24px; max-width: 860px; }
.detail-header { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; margin-bottom: 20px; flex-wrap: wrap; }
.detail-header h2 { margin: 0 0 12px; font-size: 26px; }
.detail-meta { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 10px; }
.meta-chip { font-size: 13px; color: #555; background: #fff; border: 1px solid var(--border-color, #e4e7ed); padding: 4px 12px; border-radius: 16px; }
.detail-badges { display: flex; gap: 8px; flex-wrap: wrap; }
.source-badge, .type-badge, .score-badge { padding: 3px 12px; border-radius: 12px; font-size: 12px; font-weight: 600; }
.source-badge.article { background: #e8f4fd; color: #0c63e4; }
.source-badge.user { background: #e6f7e6; color: #1a7f37; }
.source-badge.ai { background: #f3e8ff; color: #6f42c1; }
.source-badge.video { background: #e2e3e5; color: #495057; }
.type-badge.industrial { background: #d9f2e3; color: #0f7b3d; }
.type-badge.home { background: #f1f3f5; color: #495057; }
.score-badge { background: #fff0e6; color: #e8590c; }
.header-actions { display: flex; gap: 10px; flex-wrap: wrap; }
.btn-primary { padding: 10px 20px; background: var(--primary); color: #fff; border: none; border-radius: var(--radius, 8px); cursor: pointer; font-size: 14px; white-space: nowrap; }
.btn-primary:hover { opacity: 0.9; }
.btn-danger { padding: 10px 18px; background: transparent; border: 1px solid #f5c6cb; color: #c0392b; border-radius: var(--radius, 8px); cursor: pointer; font-size: 14px; white-space: nowrap; }
.btn-danger:hover { background: #fdf2f2; }
.btn-edit { padding: 10px 18px; background: transparent; border: 1px solid var(--border-color, #e4e7ed); color: var(--text-primary, #333); border-radius: var(--radius, 8px); cursor: pointer; font-size: 14px; white-space: nowrap; }
.btn-edit:hover { background: #f5f7fa; }
.compat-card { border-radius: var(--radius, 8px); padding: 14px 18px; margin-bottom: 16px; font-weight: 600; }
.compat-card.green { background: #d4edda; color: #155724; }
.compat-card.yellow { background: #fff3cd; color: #856404; }
.compat-card.red { background: #f8d7da; color: #721c24; }
.compat-card.none { background: #e2e3e5; color: #6c757d; font-weight: 400; }
.compat-reasons { margin: 8px 0 0; padding-left: 18px; font-weight: 400; font-size: 13px; }
.compat-calc { margin-top: 8px; padding: 6px 14px; background: #fff; border: 1px solid currentColor; border-radius: 6px; cursor: pointer; font-size: 13px; color: inherit; }
.allergen-warn { background: #fdf2f2; border: 1px solid #f5c6cb; color: #c0392b; border-radius: var(--radius, 8px); padding: 12px 18px; margin-bottom: 16px; font-size: 14px; }
.section-card { background: #fff; border-radius: var(--radius, 8px); box-shadow: var(--shadow, 0 2px 8px rgba(0,0,0,0.08)); padding: 18px; margin-bottom: 16px; }
.section-card h3 { margin: 0 0 12px; font-size: 16px; color: var(--text-primary, #303133); }
.section-card p { margin: 0; line-height: 1.8; color: #555; }
.chip-tip { font-size: 12px; color: var(--text-secondary, #6b7280); font-weight: 400; margin-left: 8px; }
/* ⚖️ 分量换算 */
.scale-controls { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 12px; }
.scale-mode { display: inline-flex; border: 1px solid var(--border-color, #e4e7ed); border-radius: 8px; overflow: hidden; }
.scale-mode button { padding: 8px 16px; border: none; background: #fff; cursor: pointer; font-size: 14px; }
.scale-mode button.active { background: var(--primary, #409eff); color: #fff; }
.stepper { display: inline-flex; align-items: center; gap: 2px; }
.stepper button { width: 30px; height: 32px; border: 1px solid var(--border-color, #e4e7ed); background: #fff; border-radius: 6px; cursor: pointer; font-size: 16px; }
.stepper button:disabled { opacity: 0.4; cursor: not-allowed; }
.scale-input { width: 70px; padding: 6px 8px; border: 1px solid var(--border-color, #e4e7ed); border-radius: 6px; font-size: 14px; text-align: center; }
.scale-unit { font-size: 13px; color: #6c757d; }
.scale-result { border: 1px solid var(--border-color, #eef0f2); border-radius: 8px; padding: 12px 14px; }
.scale-meta { font-size: 14px; font-weight: 600; color: var(--primary, #409eff); margin-bottom: 8px; }
.scale-nutrition { margin-top: 8px; font-size: 13px; color: #6c757d; }
.ingredient-tags { display: flex; flex-wrap: wrap; gap: 8px; }
.ing-tag { background: #f5f7fa; border: 1px solid var(--border-color, #e4e7ed); border-radius: 6px; padding: 4px 12px; font-size: 13px; color: #555; }
/* 操作步骤 */
.step-block { display: flex; gap: 12px; padding: 12px 0; border-bottom: 1px dashed var(--border-color, #eef0f2); }
.step-block:last-child { border-bottom: none; }
.step-no { width: 28px; height: 28px; border-radius: 50%; background: #fff0e6; color: #e8590c; display: flex; align-items: center; justify-content: center; font-weight: 600; flex-shrink: 0; }
.step-body { flex: 1; }
.step-summary { font-size: 15px; font-weight: 600; color: var(--text-primary, #303133); }
.step-badges { display: flex; gap: 6px; margin: 6px 0; flex-wrap: wrap; }
.step-badge { font-size: 12px; color: #e8590c; background: #fff0e6; border: 1px solid #ffd8a8; padding: 2px 8px; border-radius: 8px; }
.step-detail { font-size: 13px; color: #555; line-height: 1.7; }
/* 食材表 */
.ing-table { width: 100%; border-collapse: collapse; }
.ing-table th, .ing-table td { text-align: left; padding: 8px 10px; border-bottom: 1px solid var(--border-color, #f0f2f4); font-size: 14px; }
.ing-table th { color: #999; font-weight: 500; font-size: 13px; }
.ing-note { color: #999; font-size: 12px; }
.main-tag, .aux-tag { padding: 2px 10px; border-radius: 10px; font-size: 12px; }
.main-tag { background: #fff0e6; color: #e8590c; }
.aux-tag { background: #f1f3f5; color: #6c757d; }
/* 营养 */
.nutri-grid { display: flex; gap: 16px; flex-wrap: wrap; }
.nutri-item { flex: 1; min-width: 100px; text-align: center; background: #f8f9fa; border-radius: 8px; padding: 12px; }
.nutri-item b { display: block; font-size: 20px; color: var(--text-primary, #303133); }
.nutri-item span { font-size: 12px; color: #999; }
/* 溯源 */
.prov-line { display: flex; gap: 12px; padding: 6px 0; font-size: 14px; color: #555; }
.prov-label { color: #999; flex-shrink: 0; width: 80px; }
.prov-line a { color: var(--primary, #409eff); word-break: break-all; }
/* A3 厨电联动 */
.chip-tip { font-size: 12px; color: var(--text-secondary, #6b7280); font-weight: 400; margin-left: 8px; }
.device-param-row { display: flex; justify-content: space-between; align-items: center; gap: 16px; padding: 12px 0; border-bottom: 1px dashed var(--border-color, #eef0f2); }
.device-param-block:last-child .device-param-row { border-bottom: none; }
.param-desc { color: #333; font-size: 14px; margin: 0 8px; }
.param-note { color: var(--text-secondary, #6b7280); font-size: 12px; }
.param-action { display: flex; align-items: center; gap: 8px; }
.select { padding: 8px 10px; border: 1px solid var(--border-color, #e4e7ed); border-radius: 6px; font-size: 13px; }
.send-msg { color: var(--primary, #409eff); font-size: 13px; margin-top: 8px; }
.muted { color: var(--text-secondary, #9ca3af); font-size: 13px; }
</style>
