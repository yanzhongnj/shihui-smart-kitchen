<template>
  <div>
    <div class="intro">
      <h3>🔑 开放平台（OpenAPI）</h3>
      <p>创建应用获取 Key/Secret，通过 HMAC-SHA256 签名调用开放接口（菜谱搜索/详情/设备参数/语音文本命令）。调用按量计量。</p>
    </div>

    <!-- 创建应用 -->
    <div class="card">
      <div class="row">
        <input v-model="appName" class="input" placeholder="应用名，如：我的菜谱App" />
        <select v-model="scopes" class="select" multiple size="2">
          <option value="recipe">recipe 菜谱</option>
          <option value="voice">voice 语音命令</option>
        </select>
        <button class="btn primary" :disabled="creating" @click="createApp">{{ creating ? '创建中…' : '创建应用' }}</button>
      </div>
      <div v-if="newSecret" class="secret-box">
        <b>请立即保存 App Secret（仅显示一次）：</b>
        <code class="mono">{{ newSecret }}</code>
      </div>
    </div>

    <!-- 我的应用 -->
    <div class="card">
      <h4>我的应用</h4>
      <el-table :data="clients" border stripe style="width: 100%" empty-text="暂无应用">
        <el-table-column prop="name" label="应用名" min-width="140" />
        <el-table-column prop="appKey" label="App Key" min-width="200">
          <template #default="{ row }"><span class="mono">{{ row.appKey }}</span></template>
        </el-table-column>
        <el-table-column label="App Secret" min-width="160">
          <template #default="{ row }">
            <span class="mono">{{ masked(row.appSecret) }}</span>
            <el-button link type="primary" size="small" @click="reveal(row)">显示</el-button>
          </template>
        </el-table-column>
        <el-table-column prop="scopes" label="范围" min-width="110" />
        <el-table-column label="限流" width="90">
          <template #default="{ row }">{{ row.rateLimit }}/分</template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">{{ row.status === 1 ? '启用' : '禁用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button link type="danger" size="small" @click="revoke(row)">撤销</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 签名示例 -->
    <div class="card">
      <h4>📖 调用示例（GET /api/open/recipes/search）</h4>
      <pre class="code">{{ sigExample }}</pre>
    </div>

    <div v-if="error" class="error-text">{{ error }}</div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { orgAPI } from '@/api/org'

const clients = ref([])
const appName = ref('')
const scopes = ref(['recipe'])
const creating = ref(false)
const newSecret = ref('')
const revealed = ref({})
const error = ref('')

async function loadAll() {
  try {
    const r = await orgAPI.getMyApiClients()
    clients.value = r.data?.items || []
  } catch (e) { error.value = e.message || '加载失败' }
}

async function createApp() {
  creating.value = true
  error.value = ''
  try {
    const r = await orgAPI.createApiClient({ name: appName.value, scopes: scopes.value })
    newSecret.value = r.data?.appSecret || ''
    appName.value = ''
    await loadAll()
  } catch (e) { error.value = e.message || '创建失败' }
  finally { creating.value = false }
}

async function revoke(c) {
  if (!confirm(`确认撤销应用 ${c.name}？`)) return
  try { await orgAPI.revokeApiClient(c.id); loadAll() }
  catch (e) { error.value = e.message || '撤销失败' }
}

function reveal(c) { revealed.value = { ...revealed.value, [c.id]: !revealed.value[c.id] } }
function masked(s) { return s ? s.slice(0, 6) + '****' : '-' }

const sigExample = computed(() => {
  const c = clients.value[0]
  if (!c) return '创建应用后，此处展示 HMAC 签名调用示例'
  return [
    `# 1. 签名：HMAC-SHA256(appSecret, appKey + timestamp + nonce + body)`,
    `appKey    = ${c.appKey}`,
    `appSecret = ${c.appSecret}`,
    `timestamp = <当前毫秒时间戳>   nonce = <随机串>`,
    `signature = HMAC-SHA256 hex`,
    ``,
    `curl -G "${location.origin}/api/open/recipes/search" \\`,
    `  -H "X-App-Key: ${c.appKey}" \\`,
    `  -H "X-Timestamp: <ts>" \\`,
    `  -H "X-Nonce: <nonce>" \\`,
    `  -H "X-Signature: <hex>"`,
  ].join('\n')
})

onMounted(loadAll)
</script>

<style scoped>
.intro { margin-bottom: 14px; }
.intro h3 { margin: 0 0 6px; font-size: 18px; }
.intro p { color: var(--text-secondary, #6b7280); font-size: 13px; margin: 0; }
.card { background: #fff; border: 1px solid var(--border-color, #e5e7eb); border-radius: 10px; padding: 16px; margin-bottom: 14px; }
.card h4 { margin: 0 0 12px; font-size: 15px; }
.row { display: flex; gap: 10px; flex-wrap: wrap; }
.input, .select { padding: 8px 12px; border: 1px solid var(--border-color, #e5e7eb); border-radius: 6px; font-size: 14px; }
.input { flex: 1; min-width: 200px; }
.btn { padding: 8px 14px; border: 1px solid var(--border-color, #e5e7eb); border-radius: 6px; background: #fff; cursor: pointer; font-size: 14px; }
.btn.primary { background: var(--primary, #409eff); border-color: var(--primary, #409eff); color: #fff; }
.btn.mini { padding: 2px 8px; font-size: 12px; }
.btn.danger-ghost { color: #e5484d; }
.table { width: 100%; border-collapse: collapse; }
.table th, .table td { text-align: left; padding: 8px 10px; border-bottom: 1px solid var(--border-color, #f0f0f0); font-size: 13px; }
.table th { color: var(--text-secondary, #6b7280); font-weight: 500; }
.mono { font-family: monospace; font-size: 12px; }
.muted { color: var(--text-secondary, #9ca3af); }
.secret-box { margin-top: 12px; padding: 10px; background: #fff8e6; border-radius: 6px; font-size: 13px; }
.secret-box code { display: block; word-break: break-all; margin-top: 4px; }
.code { background: #f6f8fa; border-radius: 8px; padding: 12px; font-size: 12px; overflow-x: auto; line-height: 1.6; }
.error-text { color: #e5484d; margin-top: 10px; }
</style>
