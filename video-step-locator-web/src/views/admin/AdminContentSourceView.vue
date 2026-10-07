<template>
  <div class="admin-content">
    <!-- ===== 内容源状态 ===== -->
    <section class="section-card">
      <h3>📡 内容源状态</h3>
      <div v-if="loading" class="loading">加载中...</div>
      <div v-else class="src-grid">
        <div class="src-item">
          <span class="label">内容源</span>
          <span class="value mono">{{ status.source }}</span>
        </div>
        <div class="src-item">
          <span class="label">同步状态</span>
          <span :class="['badge', status.running ? 'off' : 'on']">{{ status.running ? '同步中…' : '空闲' }}</span>
        </div>
        <div class="src-item">
          <span class="label">已导入（DB）</span>
          <span class="value">{{ status.imported ?? '-' }}</span>
        </div>
        <div class="src-item">
          <span class="label">上次新增</span>
          <span class="value">{{ status.lastRunImported ?? '-' }}</span>
        </div>
        <div class="src-item">
          <span class="label">上次跳过 / 失败</span>
          <span class="value">{{ status.skipped ?? 0 }} / {{ status.failed ?? 0 }}</span>
        </div>
        <div class="src-item">
          <span class="label">上次运行</span>
          <span class="value">{{ status.lastRunAt || '-' }}</span>
        </div>
      </div>
      <div v-if="status.lastResult" class="result-line">{{ status.lastResult }}</div>
      <div v-if="status.lastError" class="result-line err">{{ status.lastError }}</div>
      <div class="section-actions">
        <button class="btn-primary" :disabled="status.running || busy.sync" @click="runSync">🔄 立即同步</button>
        <button class="btn-plain" @click="loadAll">↻ 刷新状态</button>
      </div>
      <p v-if="busy.sync" class="busy-hint">同步执行中，完成后自动刷新…</p>
    </section>

    <!-- ===== 内容源来源 ===== -->
    <section class="section-card">
      <h3>📦 内容源来源</h3>
      <el-table v-loading="loading" :data="sources" border stripe style="width: 100%" empty-text="暂无内容源">
        <el-table-column prop="source" label="source" min-width="110">
          <template #default="{ row }"><span class="mono">{{ row.source }}</span></template>
        </el-table-column>
        <el-table-column prop="name" label="名称" min-width="120" />
        <el-table-column prop="repo" label="仓库" min-width="200">
          <template #default="{ row }"><span class="mono repo">{{ row.repo }}</span></template>
        </el-table-column>
        <el-table-column prop="license" label="许可证" width="90" />
        <el-table-column prop="extractorVersion" label="解析版本" min-width="110">
          <template #default="{ row }"><span class="mono">{{ row.extractorVersion }}</span></template>
        </el-table-column>
        <el-table-column prop="imported" label="已导入" width="80" />
      </el-table>
    </section>

    <!-- ===== 批量精确化升级 ===== -->
    <section class="section-card">
      <h3>🏭 批量精确化升级 <span class="chip-tip">未达工业型菜谱 → LLM 精化（不计费，耗 token）</span></h3>
      <div class="row-bar">
        <label>来源：</label>
        <select v-model="upgradeSource" class="filter-input">
          <option value="howtocook">HowToCook 导入</option>
          <option value="video">视频菜谱</option>
          <option value="all">全部菜谱</option>
        </select>
        <label>本次处理数 limit：</label>
        <input v-model.number="upgradeLimit" type="number" min="1" max="500" class="filter-input narrow" />
        <button class="btn-primary warn" :disabled="busy.upgrade" @click="runUpgrade">⚡ 开始批量升级</button>
      </div>
      <div v-if="upgradeResult" class="result-line">{{ upgradeResult }}</div>
    </section>

    <!-- ===== 营养回填（两步式） ===== -->
    <section class="section-card">
      <h3>🥗 营养库回填 <span class="chip-tip">收集缺失食材 → AI 估算 → 确认入库（source=ai）</span></h3>
      <div class="row-bar">
        <label>max：</label>
        <input v-model.number="backfillMax" type="number" min="1" class="filter-input narrow" />
        <button class="btn-primary" :disabled="busy.collect" @click="collect">🔎 收集缺失</button>
        <button class="btn-primary" :disabled="busy.dryrun" @click="previewBackfill">👀 估算预览（dryRun）</button>
        <button class="btn-primary warn" :disabled="busy.backfill || !candidates.length" @click="confirmBackfill">✅ 确认入库</button>
      </div>
      <div v-if="collectInfo" class="result-line">{{ collectInfo }}</div>
      <template v-if="candidates.length">
        <div class="table">
          <div class="table-header cand">
            <span>名称</span><span>分类</span><span>热量/100g</span><span>蛋白</span><span>脂肪</span><span>碳水</span>
          </div>
          <div v-for="(c, i) in candidates" :key="i" class="table-row cand">
            <span>{{ c.name }}</span><span>{{ c.category || '-' }}</span>
            <span>{{ fmtNum(c.calories) }}</span><span>{{ fmtNum(c.protein) }}</span>
            <span>{{ fmtNum(c.fat) }}</span><span>{{ fmtNum(c.carbs) }}</span>
          </div>
        </div>
        <div class="page-total center">候选 {{ candidates.length }} 条（AI 估算值，入库前请核对）</div>
      </template>
    </section>

    <!-- ===== 食材标签索引 ===== -->
    <section class="section-card">
      <h3>🏷️ 食材标签索引</h3>
      <div class="row-bar">
        <button class="btn-primary warn" :disabled="busy.rebuild" @click="rebuildTags">♻️ 重建索引</button>
        <span v-if="rebuildResult" class="result-inline">{{ rebuildResult }}</span>
      </div>
    </section>

    <!-- ===== 同义词管理 ===== -->
    <section class="section-card">
      <h3>🔤 同义词管理 <span class="chip-tip">反查归一：别名 → 规范名（food_aliases）</span></h3>
      <div class="row-bar">
        <input v-model="aliasForm.alias" class="filter-input" placeholder="别名（如 里脊肉）" />
        <input v-model="aliasForm.canonical" class="filter-input" placeholder="规范名（如 猪里脊肉）" />
        <button class="btn-primary" :disabled="busy.alias" @click="createAlias">＋ 新增</button>
      </div>
      <el-table :data="aliases" border stripe style="width: 100%" empty-text="暂无同义词">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="alias" label="别名" min-width="120" />
        <el-table-column prop="canonical" label="规范名" min-width="140" />
        <el-table-column prop="source" label="来源" width="110">
          <template #default="{ row }"><span class="mono">{{ row.source }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button link type="danger" size="small" @click="removeAlias(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { contentSourceAPI } from '@/api/admin'

const status = ref({})
const sources = ref([])
const aliases = ref([])
const candidates = ref([])
const collectInfo = ref('')
const rebuildResult = ref('')
const upgradeResult = ref('')
const upgradeLimit = ref(50)
const upgradeSource = ref('howtocook')
const backfillMax = ref(50)
const aliasForm = reactive({ alias: '', canonical: '' })
const loading = ref(false)
const busy = reactive({ sync: false, upgrade: false, collect: false, dryrun: false, backfill: false, rebuild: false, alias: false })

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function loadAll() {
  loading.value = true
  try {
    const [s, src] = await Promise.all([contentSourceAPI.getStatus(), contentSourceAPI.getSources()])
    status.value = s.data || {}
    sources.value = src.data || []
  } catch (e) { alert(e?.message || '加载失败') } finally { loading.value = false }
}

async function runSync() {
  if (!confirm('确认手动触发 HowToCook 内容源同步吗？')) return
  busy.sync = true
  try {
    const res = await contentSourceAPI.runSync('howtocook')
    status.value = res.data || {}
    if (status.value.running) {
      for (let i = 0; i < 60; i++) {
        await sleep(5000)
        const r = await contentSourceAPI.getStatus()
        status.value = r.data || {}
        if (!status.value.running) break
      }
    }
  } catch (e) { alert(e?.message || '同步触发失败') } finally { busy.sync = false }
}

async function runUpgrade() {
  const n = upgradeLimit.value || 50
  const src = upgradeSource.value || 'howtocook'
  const label = { howtocook: 'HowToCook 导入', video: '视频菜谱', all: '全部菜谱' }[src] || src
  if (!confirm(`确认对「${label}」中最多 ${n} 道未达工业型的菜谱执行批量精确化吗？\n该操作调用 LLM（DeepSeek）并消耗 token，可能耗时数分钟。`)) return
  busy.upgrade = true
  upgradeResult.value = ''
  try {
    const res = await contentSourceAPI.upgradeIndustrial(n, src)
    const d = res.data || {}
    upgradeResult.value = `[${label}] target=${d.target} processed=${d.processed} upgraded=${d.upgraded}（industrial=${d.industrial}）stillHome=${d.stillHome} failed=${d.failed} remaining=${d.remaining} costMs=${d.costMs}`
  } catch (e) { alert(e?.message || '批量升级失败') } finally { busy.upgrade = false }
}

async function collect() {
  busy.collect = true
  collectInfo.value = ''
  try {
    const res = await contentSourceAPI.collectNutrition()
    const d = res.data || {}
    collectInfo.value = `收集到缺失食材 ${d.total} 个（前 ${d.missing?.length || 0} 个参与估算）`
  } catch (e) { alert(e?.message || '收集失败') } finally { busy.collect = false }
}

async function previewBackfill() {
  busy.dryrun = true
  candidates.value = []
  collectInfo.value = ''
  try {
    const res = await contentSourceAPI.backfillNutrition(true, backfillMax.value)
    const d = res.data || {}
    candidates.value = d.candidates || []
    collectInfo.value = `dryRun 预览：collectedTotal=${d.collectedTotal} 估算 ${d.estimated} 条（未落库）`
  } catch (e) { alert(e?.message || '估算预览失败') } finally { busy.dryrun = false }
}

async function confirmBackfill() {
  if (!confirm(`确认将 ${candidates.value.length} 条 AI 估算食材入库（source=ai）吗？`)) return
  busy.backfill = true
  try {
    const res = await contentSourceAPI.backfillNutrition(false, backfillMax.value)
    const d = res.data || {}
    collectInfo.value = `入库完成：inserted=${d.inserted} skippedExisting=${d.skippedExisting}（collectedTotal=${d.collectedTotal}）`
    candidates.value = []
  } catch (e) { alert(e?.message || '入库失败') } finally { busy.backfill = false }
}

async function rebuildTags() {
  if (!confirm('确认清空并重建食材标签索引吗？（会重新扫描全部菜谱）')) return
  busy.rebuild = true
  rebuildResult.value = ''
  try {
    const res = await contentSourceAPI.rebuildTags()
    rebuildResult.value = `✅ 已重建，共 ${res.data?.tags ?? '-'} 个唯一规范标签`
  } catch (e) { alert(e?.message || '重建失败') } finally { busy.rebuild = false }
}

async function loadAliases() {
  try {
    const res = await contentSourceAPI.getAliases()
    aliases.value = res.data || []
  } catch (e) { /* 守卫已处理 */ }
}

async function createAlias() {
  if (!aliasForm.alias.trim() || !aliasForm.canonical.trim()) { alert('请填写别名与规范名'); return }
  busy.alias = true
  try {
    await contentSourceAPI.createAlias({ alias: aliasForm.alias.trim(), canonical: aliasForm.canonical.trim() })
    aliasForm.alias = ''
    aliasForm.canonical = ''
    await loadAliases()
  } catch (e) { alert(e?.message || '新增失败') } finally { busy.alias = false }
}

async function removeAlias(a) {
  if (!confirm(`确认删除同义词「${a.alias} → ${a.canonical}」吗？`)) return
  try {
    await contentSourceAPI.deleteAlias(a.id)
    await loadAliases()
  } catch (e) { alert(e?.message || '删除失败') }
}

function fmtNum(v) { return v == null ? '-' : Number(v).toFixed(1) }

onMounted(() => { loadAll(); loadAliases() })
</script>

<style scoped>
.admin-content { padding: 24px; }
.section-card { background: #fff; border-radius: 8px; box-shadow: 0 1px 4px rgba(0,0,0,0.08); padding: 18px 20px; margin-bottom: 20px; }
.section-card h3 { margin: 0 0 14px; font-size: 16px; }
.chip-tip { font-size: 12px; color: var(--text-secondary); font-weight: 400; margin-left: 8px; }
.src-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 12px; margin-bottom: 12px; }
.src-item { background: #f7f9fc; border-radius: 6px; padding: 10px 12px; }
.src-item .label { display: block; font-size: 12px; color: var(--text-secondary); margin-bottom: 4px; }
.src-item .value { font-size: 15px; font-weight: 600; }
.section-actions, .row-bar { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; margin-top: 10px; }
.result-line { margin-top: 10px; font-size: 13px; color: #2c6; background: #f0fff4; border: 1px solid #c6f6d5; border-radius: 6px; padding: 8px 12px; }
.result-line.err { color: #c0392b; background: #fdf0ef; border-color: #f5c6c2; }
.result-inline { margin-left: 10px; font-size: 13px; color: #2c6; }
.busy-hint { font-size: 12px; color: #e67e22; margin: 8px 0 0; }
.btn-primary { padding: 8px 16px; border: none; border-radius: 6px; background: var(--primary, #409eff); color: #fff; cursor: pointer; }
.btn-primary.warn { background: #e67e22; }
.btn-primary:disabled { opacity: 0.5; cursor: not-allowed; }
.btn-plain { padding: 8px 16px; border: 1px solid var(--border-color); border-radius: 6px; background: #fff; cursor: pointer; }
.filter-input { padding: 8px 12px; border: 1px solid var(--border-color); border-radius: 6px; font-size: 14px; min-width: 200px; }
.filter-input.narrow { min-width: 80px; }
.badge { padding: 3px 10px; border-radius: 12px; font-size: 12px; }
.badge.on { background: #e6f7ed; color: #1a7f37; }
.badge.off { background: #fdecec; color: #c0392b; }
.table { background: #fff; border-radius: 8px; box-shadow: 0 1px 4px rgba(0,0,0,0.06); overflow: hidden; margin-top: 10px; }
.table-header, .table-row { display: grid; gap: 8px; padding: 10px 14px; align-items: center; font-size: 13px; }
.table-header { background: #fafafa; font-weight: 600; color: var(--text-regular); }
.table-row { border-top: 1px solid #f0f0f0; }
.table-row:hover { background: #fafcff; }
.table-header.src, .table-row.src { grid-template-columns: 90px 1.2fr 2fr 70px 1fr 70px; }
.table-header.cand, .table-row.cand { grid-template-columns: 1.4fr 1fr 1fr 1fr 1fr 1fr; }
.table-header.alias, .table-row.alias { grid-template-columns: 60px 1.2fr 1.2fr 90px 120px; }
.mono { font-family: Consolas, monospace; font-size: 12px; }
.repo { word-break: break-all; }
.empty { text-align: center; color: var(--text-secondary); padding: 24px 0; }
.page-total.center { text-align: center; margin-top: 10px; font-size: 13px; color: var(--text-secondary); }
.action-btn { padding: 4px 10px; border-radius: 6px; border: 1px solid var(--border-color); background: #fff; cursor: pointer; font-size: 12px; }
.action-btn.delete { color: #c0392b; border-color: #e5b8b4; }
.col-actions { display: flex; gap: 6px; }
</style>
