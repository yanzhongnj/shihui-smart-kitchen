<template>
  <div class="admin-ai-config">
    <div class="page-actions">
      <button class="btn-primary" @click="healthAll">🩺 全部测试连通</button>
      <button class="btn" @click="load">🔄 刷新</button>
      <button class="btn" @click="allCollapse(false)">▾ 全部展开</button>
      <button class="btn" @click="allCollapse(true)">▸ 全部折叠</button>
      <span class="hint">保存后固化到配置中心（不再依赖环境变量）；未填 API Key = 保持不变；引擎路由支持运行期切换</span>
    </div>

    <!-- 类型 Tab：按引擎类型分页，避免卡片平铺超出屏幕 -->
    <div class="tabs">
      <button v-for="t in tabs" :key="t.key" class="tab"
              :class="{ active: activeTab === t.key }" @click="switchTab(t.key)">
        {{ t.label }}
      </button>
    </div>

    <div v-if="loading" class="loading">加载中...</div>
    <div v-else>
      <template v-if="activeTab !== 'logs'">
      <div v-if="activeGroup" class="cfg-group">
        <h3 class="group-title">{{ activeGroup.title }}</h3>
        <div class="cfg-grid">
          <div v-for="p in activeGroup.items" :key="p.providerKey" class="cfg-card"
               :class="{ 'cfg-off': !(edits[p.providerKey]?.enabled), 'cfg-folded': collapsed[p.providerKey] }">
            <div class="cfg-head">
              <div class="cfg-title" @click="toggleCollapse(p.providerKey)" title="点击展开/折叠">
                <strong>{{ p.displayName }}</strong>
                <span class="mono">{{ p.providerKey }}</span>
              </div>
              <div class="cfg-badges">
                <span :class="['badge', statusClass(p)]">{{ statusLabel(p) }}</span>
                <span v-if="p.inDb" class="badge db">已配置</span>
                <span v-else class="badge env">默认(env)</span>
                <button class="collapse-btn" :title="collapsed[p.providerKey] ? '展开' : '折叠'"
                        @click="toggleCollapse(p.providerKey)">{{ collapsed[p.providerKey] ? '▸' : '▾' }}</button>
              </div>
            </div>

            <div v-if="!collapsed[p.providerKey]" class="cfg-body">
            <div class="cfg-fields">
              <template v-if="!isEngineRoute(p)">
                <label class="field">
                  <span>API Key</span>
                  <input type="password" v-model="edits[p.providerKey].apiKey"
                       :placeholder="p.hasApiKey ? `已配置 ${p.apiKeyMasked}（留空=不变${p.inDb ? '' : '·保存后固化'}）` : '未配置'">
                </label>
                <label class="field">
                  <span>Endpoint</span>
                  <input v-model="edits[p.providerKey].endpoint" placeholder="留空=沿用环境变量">
                </label>
                <label class="field">
                  <span>Model</span>
                  <input v-model="edits[p.providerKey].model" placeholder="留空=沿用环境变量">
                </label>
              </template>

              <label v-for="f in extraFields(p)" :key="f.key" class="field">
                <span>{{ f.label }}</span>
                <select v-if="isEngineRoute(p) && f.key === 'engine'" v-model="edits[p.providerKey].extra['engine']"
                        class="field-select">
                  <option v-for="o in engineOptions(p)" :key="o" :value="o">{{ o }}</option>
                </select>
                <input v-else :type="f.type" v-model="edits[p.providerKey].extra[f.key]"
                       :placeholder="`当前: ${p.extra[f.key] || '-'}`">
              </label>

              <div v-if="isEngineRoute(p)" class="cfg-hint">
                选中 = 主引擎；另一真实引擎自动作为备用，主引擎不可用时接管；全部不可用降级 mock
              </div>

              <label v-if="!isEngineRoute(p)" class="field check">
                <input type="checkbox" v-model="edits[p.providerKey].mockEnabled">
                <span>Mock 开关</span>
              </label>
              <label class="field check">
                <input type="checkbox" v-model="edits[p.providerKey].enabled">
                <span>启用</span>
              </label>
            </div>

            <div class="cfg-actions">
              <button class="btn-primary" :disabled="saving[p.providerKey]" @click="save(p.providerKey)">
                {{ saving[p.providerKey] ? '保存中…' : '💾 保存热生效' }}
              </button>
              <button class="btn" @click="test(p.providerKey)">🔌 测试连通</button>
              <button class="btn" @click="restore(p.providerKey)">↩️ 恢复默认</button>
              <button class="btn" @click="toggle(p.providerKey)">
                {{ edits[p.providerKey]?.enabled ? '停用' : '启用' }}
              </button>
            </div>
            </div>

            <div v-else class="cfg-summary">
              <span class="cfg-summary-text">{{ summary(p) }}</span>
              <button class="btn" @click="toggleCollapse(p.providerKey)">✏️ 编辑</button>
            </div>
          </div>
        </div>
      </div>
      </template>

      <!-- 变更日志（Tab）：el-table 分页 + 筛选 + 排序 -->
      <template v-else>
      <div class="logs-section">
        <div class="logs-actions">
          <button class="btn" @click="loadLogs">🔄 刷新日志</button>
          <el-select v-model="logProviderFilter" placeholder="全部引擎" clearable
                     style="width: 220px" @change="logPage = 0">
            <el-option v-for="k in logProviderOptions" :key="k" :label="k" :value="k" />
          </el-select>
          <el-select v-model="logActionFilter" placeholder="全部动作" clearable
                     style="width: 140px" @change="logPage = 0">
            <el-option v-for="a in logActionOptions" :key="a.value" :label="a.label" :value="a.value" />
          </el-select>
          <span class="hint">共 {{ sortedLogs.length }} 条 · 点表头排序 · 筛选后分页</span>
        </div>

        <el-table v-loading="logsLoading" :data="pagedLogs" border stripe style="width: 100%"
                  empty-text="暂无变更日志"
                  :default-sort="{ prop: 'createdAt', order: 'descending' }" @sort-change="onLogSort">
          <el-table-column prop="createdAt" label="时间" width="180" sortable="custom" />
          <el-table-column prop="providerKey" label="引擎" min-width="170" sortable="custom">
            <template #default="{ row }"><span class="mono">{{ row.providerKey }}</span></template>
          </el-table-column>
          <el-table-column prop="action" label="动作" width="110" sortable="custom">
            <template #default="{ row }">{{ actionLabel(row.action) }}</template>
          </el-table-column>
          <el-table-column prop="testResult" label="结果" min-width="180" sortable="custom">
            <template #default="{ row }">{{ row.testResult || '-' }}</template>
          </el-table-column>
          <el-table-column prop="operatorName" label="操作人" width="140" sortable="custom" />
        </el-table>

        <el-pagination v-if="sortedLogs.length > logPageSize" background
          layout="total, sizes, prev, pager, next"
          :total="sortedLogs.length" :current-page="logPage + 1" :page-size="logPageSize"
          :page-sizes="[10, 20, 50]"
          @current-change="onLogPageChange" @size-change="onLogSizeChange" class="ep-pagination" />
      </div>
      </template>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { adminAPI } from '@/api/admin'

const loading = ref(false)
const providers = ref([])
const edits = ref({})
const saving = ref({})
const collapsed = ref({})
const logs = ref([])

const GROUP_META = [
  { type: 'llm', title: 'LLM 对话' },
  { type: 'vision', title: '视觉识别' },
  { type: 'video_gen', title: '视频生成' },
  { type: 'image_gen', title: '文生图' },
  { type: 'asr', title: '语音识别' },
  { type: 'tts', title: '语音讲解 TTS' },
]

const EXTRA_LABELS = {
  timeout_ms: '超时(ms)', resolution: '分辨率', poll_interval_ms: '轮询间隔(ms)',
  default_segment_sec: '默认时长(s)', query_endpoint: '查询端点', size: '尺寸',
  engine: '引擎', speech_threshold: '语音阈值', silence_ms: '静音(ms)',
  min_speech_ms: '最短语音(ms)', max_utterance_ms: '最长句(ms)', http_timeout_ms: 'HTTP超时(ms)',
  host: '主机', port: '端口', voice: '音色', format: '格式', sample_rate: '采样率', instruction: '朗读指令',
}
const EXTRA_NUM = ['timeout_ms', 'poll_interval_ms', 'default_segment_sec', 'silence_ms',
  'min_speech_ms', 'max_utterance_ms', 'http_timeout_ms', 'port', 'sample_rate', 'speech_threshold']

// Tab 分页：六类引擎 + 变更日志
const activeTab = ref(GROUP_META[0].type)
const tabs = computed(() => [
  ...GROUP_META.map((g) => ({ key: g.type, label: g.title })),
  { key: 'logs', label: '变更日志' },
])
const activeGroup = computed(() => {
  const g = GROUP_META.find((x) => x.type === activeTab.value)
  if (!g) return null
  return { type: g.type, title: g.title, items: providers.value.filter((p) => p.providerType === g.type) }
})
function switchTab(key) { activeTab.value = key }

onMounted(() => { load(); loadLogs() })

async function load() {
  loading.value = true
  try {
    const res = await adminAPI.aiConfig.list()
    providers.value = res.data?.items || []
    for (const p of providers.value) {
      edits.value[p.providerKey] = {
        endpoint: p.endpoint || '',
        apiKey: '',
        model: p.model || '',
        extra: { ...(p.extra || {}) },
        mockEnabled: !!p.mockEnabled,
        enabled: p.enabled !== false,
      }
      collapsed.value[p.providerKey] = true
    }
  } catch (e) {
    alert(e?.message || '加载失败')
  } finally {
    loading.value = false
  }
}

function extraFields(p) {
  return Object.keys(p.extra || {}).map((k) => ({
    key: k,
    label: EXTRA_LABELS[k] || k,
    type: EXTRA_NUM.includes(k) ? 'number' : 'text',
  }))
}

// 引擎路由卡片：仅「引擎」选择，不配置 API Key/Endpoint/Model/Mock
function isEngineRoute(p) {
  return (p.providerKey || '').endsWith('.engine')
}
const ENGINE_OPTIONS = {
  video_gen: ['wan', 'seedance', 'mock', 'auto'],
  image_gen: ['wan_image', 'mock', 'auto'],
  asr: ['funasr', 'aliyun', 'auto'],
}
function engineOptions(p) {
  return ENGINE_OPTIONS[p.providerType] || []
}

// 卡片折叠/展开
function toggleCollapse(key) { collapsed.value[key] = !collapsed.value[key] }
function allCollapse(flag) { for (const p of providers.value) collapsed.value[p.providerKey] = flag }
function summary(p) {
  const parts = []
  if (isEngineRoute(p)) parts.push(`引擎: ${p.extra?.engine || '-'}`)
  if (p.model) parts.push(`模型: ${p.model}`)
  if (p.hasApiKey) parts.push('Key: 已配置')
  else parts.push('Key: 未配置')
  if (p.mockEnabled) parts.push('Mock: 开')
  return parts.join(' · ')
}

async function save(key) {
  const e = edits.value[key]
  const payload = {
    displayName: null,
    endpoint: e.endpoint || null,
    apiKey: e.apiKey || null,
    model: e.model || null,
    extra: { ...e.extra },
    mockEnabled: e.mockEnabled,
    enabled: e.enabled,
    // 未填 key 时采纳环境变量 key 固化到配置中心（后端仅在 DB 无 key 时采纳）
    adoptEnvKey: !e.apiKey,
  }
  saving.value[key] = true
  try {
    await adminAPI.aiConfig.update(key, payload)
    await load()
    await loadLogs()
  } catch (err) {
    alert(err?.message || '保存失败')
  } finally {
    saving.value[key] = false
  }
}

async function test(key) {
  try {
    const res = await adminAPI.aiConfig.test(key)
    alert(`连通性测试：${res.data?.result || (res.data?.ok ? 'ok' : 'fail')}`)
    await load()
  } catch (err) {
    alert(err?.message || '测试失败')
  }
}

async function restore(key) {
  if (!confirm('确认恢复该引擎为环境变量默认配置？')) return
  try {
    await adminAPI.aiConfig.restoreDefault(key)
    await load()
    await loadLogs()
  } catch (err) {
    alert(err?.message || '恢复失败')
  }
}

async function toggle(key) {
  const on = edits.value[key]?.enabled
  try {
    if (on) await adminAPI.aiConfig.disable(key)
    else await adminAPI.aiConfig.enable(key)
    await load()
  } catch (err) {
    alert(err?.message || '操作失败')
  }
}

async function healthAll() {
  if (!confirm('对全部引擎执行连通性测试？')) return
  try {
    await adminAPI.aiConfig.health()
    alert('已全部测试，见各引擎状态')
    await load()
  } catch (err) {
    alert(err?.message || '测试失败')
  }
}

// 变更日志：筛选 / 排序 / 分页
const logsLoading = ref(false)
const logProviderFilter = ref('')
const logActionFilter = ref('')
const logSortKey = ref('createdAt')
const logSortOrder = ref('descending')
const logPage = ref(0)
const logPageSize = ref(10)

const LOG_ACTIONS = [
  { value: 'create', label: '新建' },
  { value: 'update', label: '更新' },
  { value: 'enable', label: '启用' },
  { value: 'disable', label: '停用' },
  { value: 'restore_default', label: '恢复默认' },
  { value: 'test', label: '测试' },
]

const logProviderOptions = computed(() => [...new Set(logs.value.map((l) => l.providerKey))].sort())
const logActionOptions = computed(() => LOG_ACTIONS.filter((a) => logs.value.some((l) => l.action === a.value)))

const filteredLogs = computed(() => logs.value.filter((l) =>
  (!logProviderFilter.value || l.providerKey === logProviderFilter.value) &&
  (!logActionFilter.value || l.action === logActionFilter.value)))

const sortedLogs = computed(() => {
  const arr = [...filteredLogs.value]
  const key = logSortKey.value
  const dir = logSortOrder.value === 'descending' ? -1 : 1
  arr.sort((a, b) => {
    const av = a[key]
    const bv = b[key]
    if (av == null && bv == null) return 0
    if (av == null) return 1
    if (bv == null) return -1
    return String(av).localeCompare(String(bv), 'zh-Hans-CN') * dir
  })
  return arr
})

const pagedLogs = computed(() => {
  const start = logPage.value * logPageSize.value
  return sortedLogs.value.slice(start, start + logPageSize.value)
})

function onLogSort({ prop, order }) {
  logSortKey.value = prop || 'createdAt'
  logSortOrder.value = order || 'descending'
  logPage.value = 0
}
function onLogPageChange(p) { logPage.value = p - 1 }
function onLogSizeChange(s) { logPageSize.value = s; logPage.value = 0 }

async function loadLogs() {
  logsLoading.value = true
  try {
    const res = await adminAPI.aiConfig.changeLogs()
    logs.value = res.data || []
  } catch (e) {
    logs.value = []
  } finally {
    logsLoading.value = false
  }
}

function statusLabel(p) {
  return ({ healthy: '健康', unhealthy: '异常', untested: '未测试' })[p.status] || p.status || '未测试'
}
function statusClass(p) {
  return p.status === 'healthy' ? 'ok' : (p.status === 'unhealthy' ? 'down' : '')
}
function actionLabel(a) {
  return ({ create: '新建', update: '更新', enable: '启用', disable: '停用', restore_default: '恢复默认', test: '测试' })[a] || a
}
</script>

<style scoped>
.admin-ai-config { padding: 4px 0 24px; }
.page-actions { display: flex; align-items: center; gap: 10px; margin-bottom: 16px; flex-wrap: wrap; }
.hint { color: #888; font-size: 12px; }
.tabs { display: flex; gap: 4px; border-bottom: 1px solid #e4e7ed; margin-bottom: 16px; flex-wrap: wrap; }
.tab { padding: 10px 16px; border: none; background: none; cursor: pointer; font-size: 14px; color: #666; border-bottom: 2px solid transparent; }
.tab.active { color: #409eff; border-bottom-color: #409eff; font-weight: 600; }
.loading { color: #888; padding: 24px; }
.group-title { margin: 18px 0 10px; font-size: 15px; color: #444; border-left: 3px solid #409eff; padding-left: 8px; }
.cfg-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(360px, 1fr)); gap: 14px; }
.cfg-card { border: 1px solid #e5e7eb; border-radius: 10px; padding: 12px 14px; background: #fff; box-shadow: 0 1px 2px rgba(0,0,0,.04); }
.cfg-card.cfg-off { opacity: .72; }
.cfg-card.cfg-folded { border-style: dashed; background: #fcfcfd; }
.cfg-title { cursor: pointer; }
.collapse-btn { border: none; background: none; cursor: pointer; font-size: 13px; color: #999; padding: 0 4px; }
.cfg-summary { display: flex; align-items: center; justify-content: space-between; gap: 10px; font-size: 12px; color: #666; padding: 6px 2px; }
.cfg-summary-text { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.cfg-head { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 10px; gap: 8px; }
.cfg-title { display: flex; flex-direction: column; gap: 2px; }
.cfg-title strong { font-size: 14px; }
.cfg-title .mono { font-size: 11px; color: #999; font-family: monospace; }
.cfg-badges { display: flex; gap: 6px; align-items: center; }
.badge { font-size: 11px; padding: 2px 8px; border-radius: 10px; background: #f3f4f6; color: #666; }
.badge.ok { background: #e8f7ee; color: #16a34a; }
.badge.down { background: #fde8e8; color: #dc2626; }
.badge.db { background: #eef2ff; color: #4f46e5; }
.badge.env { background: #fef3c7; color: #b45309; }
.cfg-fields { display: flex; flex-direction: column; gap: 8px; }
.field { display: flex; flex-direction: column; gap: 3px; font-size: 12px; color: #666; }
.field.check { flex-direction: row; align-items: center; gap: 6px; }
.field input[type="text"], .field input[type="password"], .field input[type="number"], .field-select {
  border: 1px solid #e5e7eb; border-radius: 6px; padding: 6px 8px; font-size: 13px; width: 100%;
}
.cfg-hint { font-size: 11px; color: #b45309; background: #fef3c7; border-radius: 6px; padding: 6px 8px; line-height: 1.5; }
.cfg-actions { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 12px; }
.btn, .btn-primary { border: 1px solid #e5e7eb; border-radius: 6px; padding: 6px 12px; font-size: 13px; cursor: pointer; background: #fff; }
.btn-primary { background: #409eff; border-color: #409eff; color: #fff; }
.btn-primary:disabled { opacity: .6; cursor: default; }
.logs-section { margin-top: 4px; }
.logs-section h3 { font-size: 15px; color: #444; margin-bottom: 10px; }
.logs-actions { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 12px; }
.ep-pagination { margin-top: 12px; justify-content: flex-end; }
.table { width: 100%; border-collapse: collapse; font-size: 13px; }
.table th, .table td { border: 1px solid #eee; padding: 7px 10px; text-align: left; }
.table th { background: #f9fafb; color: #555; }
.mono { font-family: monospace; }
.empty { color: #999; padding: 16px; text-align: center; }
</style>
