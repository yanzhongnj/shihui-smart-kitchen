<template>
  <div>
    <div class="filter-bar">
      <button class="btn primary" @click="showBind = true">＋ 绑定设备</button>
      <span class="hint">当前引擎：模拟设备（Mock）—— 下发指令后自动模拟「预热完成→工作完成」事件</span>
    </div>

    <!-- 我的设备 -->
    <div class="card">
      <h3>🏠 我的设备</h3>
      <el-table :data="devices" border stripe style="width: 100%" empty-text="暂无设备，点「＋ 绑定设备」添加（模拟）">
        <el-table-column prop="nickname" label="昵称" min-width="140">
          <template #default="{ row }"><b>{{ row.nickname || '未命名设备' }}</b></template>
        </el-table-column>
        <el-table-column prop="deviceType" label="类型" min-width="100">
          <template #default="{ row }">{{ typeLabel(row.deviceType) }}</template>
        </el-table-column>
        <el-table-column prop="vendor" label="厂商" min-width="100">
          <template #default="{ row }">{{ vendorLabel(row.vendor) }}</template>
        </el-table-column>
        <el-table-column prop="vendorDeviceId" label="设备ID" min-width="150">
          <template #default="{ row }"><span class="mono">{{ row.vendorDeviceId }}</span></template>
        </el-table-column>
        <el-table-column prop="online" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.online === 1 ? 'success' : 'info'" size="small">{{ row.online === 1 ? '🟢 在线' : '⚪ 离线' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openCommand(row)">下发指令</el-button>
            <el-button link type="danger" size="small" @click="unbind(row)">解绑</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 指令日志 -->
    <div class="card">
      <h3>📜 指令日志</h3>
      <el-table :data="logs" border stripe style="width: 100%" empty-text="暂无指令">
        <el-table-column prop="createdAt" label="时间" width="170">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column prop="deviceId" label="设备" min-width="140">
          <template #default="{ row }"><span class="mono">{{ row.deviceId }}</span></template>
        </el-table-column>
        <el-table-column prop="command" label="指令" min-width="150">
          <template #default="{ row }">{{ fmtCmd(row.command) }}</template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="logTagType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="errorMessage" label="结果/错误" min-width="160">
          <template #default="{ row }">{{ row.errorMessage || row.result || '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status === 'failed'" link type="primary" size="small" @click="retry(row)">重试</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div v-if="error" class="error-text">{{ error }}</div>

    <!-- 绑定设备 -->
    <div v-if="showBind" class="modal-mask" @click.self="showBind = false">
      <div class="modal">
        <h3>绑定设备</h3>
        <select v-model="bindForm.vendor" class="select">
          <option value="mock">模拟设备（Mock）</option>
          <option value="tuya">涂鸦 IoT（需配置）</option>
          <option value="mijia">米家（预留）</option>
        </select>
        <input v-model="bindForm.nickname" class="input" placeholder="昵称，如：厨房的烤箱" />
        <select v-model="bindForm.deviceType" class="select">
          <option value="oven">烤箱</option><option value="steamer">蒸箱</option>
          <option value="cooktop">集成灶</option><option value="airfryer">空气炸锅</option>
        </select>
        <div class="modal-actions">
          <button class="btn" @click="showBind = false">取消</button>
          <button class="btn primary" :disabled="binding" @click="doBind">绑定</button>
        </div>
      </div>
    </div>

    <!-- 下发指令 -->
    <div v-if="cmdDevice" class="modal-mask" @click.self="cmdDevice = null">
      <div class="modal">
        <h3>下发指令 → {{ cmdDevice.nickname || cmdDevice.vendorDeviceId }}</h3>
        <div class="preset">
          <button v-for="p in presets" :key="p.action" class="btn" @click="applyPreset(p)">{{ p.label }}</button>
        </div>
        <select v-model="cmdForm.action" class="select">
          <option value="preheat">预热 preheat</option>
          <option value="bake">烘焙 bake</option>
          <option value="steam">蒸 steam</option>
          <option value="air_fry">空气炸 air_fry</option>
          <option value="set_timer">定时 set_timer</option>
          <option value="start">开始 start</option>
          <option value="stop">停止 stop</option>
        </select>
        <div class="row2">
          <label class="stepper">
            <span class="stepper-label">温度 ℃</span>
            <span class="stepper-box">
              <button type="button" class="step-btn" :disabled="(parseNum(cmdForm.temperature) ?? 0) <= 0" @click="step('temperature', -5)">−</button>
              <input v-model="cmdForm.temperature" class="input step-input" type="text" inputmode="decimal" autocomplete="off" placeholder="—" />
              <button type="button" class="step-btn" @click="step('temperature', 5)">＋</button>
            </span>
          </label>
          <label class="stepper">
            <span class="stepper-label">时长（分钟）</span>
            <span class="stepper-box">
              <button type="button" class="step-btn" :disabled="(parseNum(cmdForm.durationMin) ?? 0) <= 0" @click="step('durationMin', -1)">−</button>
              <input v-model="cmdForm.durationMin" class="input step-input" type="text" inputmode="decimal" autocomplete="off" placeholder="—" />
              <button type="button" class="step-btn" @click="step('durationMin', 1)">＋</button>
            </span>
          </label>
        </div>
        <div class="modal-actions">
          <button class="btn" @click="cmdDevice = null">取消</button>
          <button class="btn primary" :disabled="sending" @click="sendCmd">下发</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { orgAPI } from '@/api/org'

const devices = ref([])
const logs = ref([])
const error = ref('')
const showBind = ref(false)
const binding = ref(false)
const bindForm = ref({ vendor: 'mock', nickname: '', deviceType: 'oven' })
const cmdDevice = ref(null)
const sending = ref(false)
const cmdForm = ref({ action: 'preheat', temperature: 200, durationMin: null })
const presets = [
  { label: '预热200℃', action: 'preheat', temperature: 200, durationMin: null },
  { label: '烘焙200℃/25分', action: 'bake', temperature: 200, durationMin: 25 },
  { label: '蒸/15分', action: 'steam', temperature: null, durationMin: 15 },
  { label: '停止', action: 'stop', temperature: null, durationMin: null },
]

async function loadAll() {
  try {
    const [d, l] = await Promise.all([
      orgAPI.getDevices({ page: 0, size: 100 }),
      orgAPI.getDeviceCommands({ page: 0, size: 30 }),
    ])
    devices.value = d.data?.items || []
    logs.value = l.data?.items || []
  } catch (e) { error.value = e.message || '加载失败' }
}

async function doBind() {
  binding.value = true
  error.value = ''
  try {
    await orgAPI.bindDevice({ ...bindForm.value })
    showBind.value = false
    bindForm.value = { vendor: 'mock', nickname: '', deviceType: 'oven' }
    loadAll()
  } catch (e) { error.value = e.message || '绑定失败' }
  finally { binding.value = false }
}

async function unbind(d) {
  if (!confirm(`确认解绑 ${d.nickname || d.vendorDeviceId}？`)) return
  try {
    await orgAPI.unbindDevice(d.id)
    loadAll()
  } catch (e) { error.value = e.message || '解绑失败' }
}

function openCommand(d) {
  cmdDevice.value = d
  cmdForm.value = { action: 'preheat', temperature: 200, durationMin: null }
}
function applyPreset(p) {
  cmdForm.value = { action: p.action, temperature: p.temperature, durationMin: p.durationMin }
}
function parseNum(v) {
  if (v == null || v === '') return null
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}
function step(key, delta) {
  const cur = parseNum(cmdForm.value[key]) ?? 0
  cmdForm.value = { ...cmdForm.value, [key]: Math.max(0, cur + delta) }
}
async function sendCmd() {
  sending.value = true
  error.value = ''
  try {
    await orgAPI.sendDeviceCommand(cmdDevice.value.id, {
      command: {
        action: cmdForm.value.action,
        temperature: parseNum(cmdForm.value.temperature),
        durationMin: parseNum(cmdForm.value.durationMin),
      },
    })
    cmdDevice.value = null
    loadAll()
  } catch (e) { error.value = e.message || '下发失败' }
  finally { sending.value = false }
}

async function retry(l) {
  try { await orgAPI.retryDeviceCommand(l.id); loadAll() }
  catch (e) { error.value = e.message || '重试失败' }
}

function typeLabel(t) { return ({ oven: '烤箱', steamer: '蒸箱', cooktop: '集成灶', airfryer: '空气炸锅' })[t] || t }
function vendorLabel(v) { return ({ mock: '模拟', tuya: '涂鸦', mijia: '米家' })[v] || v }
function statusLabel(s) { return ({ queued: '排队中', sent: '已发送', success: '成功', failed: '失败' })[s] || s }
function logTagType(s) { return ({ success: 'success', failed: 'danger', sent: 'warning', queued: 'warning' })[s] || 'info' }
function fmtTime(t) { return t ? String(t).replace('T', ' ').slice(0, 16) : '-' }
function fmtCmd(c) {
  if (!c) return '-'
  let s = c.action || ''
  if (c.mode) s += '·' + c.mode
  if (c.temperature) s += '·' + c.temperature + '℃'
  if (c.durationMin) s += '·' + c.durationMin + 'min'
  return s
}

onMounted(loadAll)
</script>

<style scoped>
.filter-bar { display: flex; gap: 12px; align-items: center; margin-bottom: 14px; flex-wrap: wrap; }
.hint { color: var(--text-secondary, #6b7280); font-size: 13px; }
.card { background: #fff; border: 1px solid var(--border-color, #e5e7eb); border-radius: 10px; padding: 16px; margin-bottom: 14px; }
.card h3 { margin: 0 0 12px; font-size: 16px; }
.table { width: 100%; border-collapse: collapse; }
.table th, .table td { text-align: left; padding: 8px 10px; border-bottom: 1px solid var(--border-color, #f0f0f0); font-size: 14px; }
.table th { color: var(--text-secondary, #6b7280); font-weight: 500; }
.mono { font-family: monospace; font-size: 13px; }
.muted { color: var(--text-secondary, #9ca3af); }
.btn { padding: 8px 14px; border: 1px solid var(--border-color, #e5e7eb); border-radius: 6px; background: #fff; cursor: pointer; font-size: 14px; }
.btn.primary { background: var(--primary, #409eff); border-color: var(--primary, #409eff); color: #fff; }
.btn.danger-ghost { color: #e5484d; margin-left: 6px; }
.status { padding: 2px 10px; border-radius: 20px; font-size: 12px; }
.status.success { background: #e8f7ee; color: #1a7f37; }
.status.failed { background: #fdeaea; color: #c0392b; }
.status.sent, .status.queued { background: #fff3e0; color: #b26a00; }
.error-text { color: #e5484d; margin-top: 10px; }
.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,0.4); display: flex; align-items: center; justify-content: center; z-index: 1000; }
.modal { background: #fff; border-radius: 10px; padding: 20px; width: 400px; display: flex; flex-direction: column; gap: 12px; }
.modal h3 { margin: 0; }
.input, .select { padding: 8px 12px; border: 1px solid var(--border-color, #e5e7eb); border-radius: 6px; font-size: 14px; }
.row2 { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 10px; }
/* 上下调节步进器（替代原生 number 上下按钮，布局统一） */
.stepper { display: flex; flex-direction: column; gap: 4px; min-width: 0; }
.stepper-label { font-size: 12px; color: var(--text-secondary, #6b7280); }
.stepper-box { display: flex; align-items: stretch; min-width: 0; overflow: hidden; border: 1px solid var(--border-color, #e5e7eb); border-radius: 6px; background: #fff; }
.step-btn { width: 34px; border: none; background: #f5f7fa; cursor: pointer; font-size: 17px; line-height: 1; color: #333; flex-shrink: 0; transition: background .15s, color .15s; }
.step-btn:hover:not(:disabled) { background: var(--primary, #409eff); color: #fff; }
.step-btn:disabled { color: #c0c4cc; cursor: not-allowed; }
.step-input { flex: 1; min-width: 0; padding: 8px 2px; border: none; outline: none; text-align: center; -moz-appearance: textfield; }
.preset { display: flex; flex-wrap: wrap; gap: 6px; }
.preset .btn { font-size: 13px; }
.modal-actions { display: flex; justify-content: flex-end; gap: 8px; }
</style>
