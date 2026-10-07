<template>
  <div class="download-page">
    <div class="upload-tabs">
      <router-link to="/app/videos/upload" class="tab" active-class="active">📤 本地上传</router-link>
      <router-link to="/app/videos/download" class="tab" active-class="active">🔗 链接下载</router-link>
    </div>
    <p class="hint">
      粘贴合集 / 播放列表 / 多P 视频页面链接，自动解析全部可下载条目，勾选后批量下载入库
      <router-link to="/downloads" class="history-link">查看下载历史 →</router-link>
    </p>

    <!-- Step1 输入链接 -->
    <div class="url-box">
      <input v-model="url" placeholder="https://..." @keyup.enter="parseUrl" />
      <button class="btn-primary" @click="parseUrl" :disabled="parsing">
        {{ parsing ? '解析中...' : '解析链接' }}
      </button>
    </div>
    <p v-if="parseError" class="error-msg">❌ {{ parseError }}</p>

    <!-- Step2 条目列表确认 -->
    <div v-if="parseResult" class="result-panel">
      <div class="result-header">
        <span class="result-title">
          {{ parseResult.type === 'playlist' ? '📂' : '🎬' }} {{ parseResult.pageTitle || '解析结果' }}
        </span>
        <span class="result-count">共 {{ parseResult.entries.length }} 条</span>
        <div class="result-actions">
          <label class="format-select">
            清晰度
            <select v-model="format">
              <option value="best">最佳画质</option>
              <option value="1080p">1080P</option>
              <option value="720p">720P</option>
              <option value="480p">480P</option>
            </select>
          </label>
          <button class="btn-ghost" @click="toggleSelectAll">全选 / 反选</button>
          <button class="btn-primary" @click="startDownload"
                  :disabled="starting || selectedCount === 0">
            {{ starting ? '创建任务中...' : `开始下载(${selectedCount})` }}
          </button>
        </div>
      </div>

      <div class="entry-list">
        <label v-for="(entry, idx) in parseResult.entries" :key="idx"
               class="entry-item" :class="{ selected: entry.selected }">
          <input type="checkbox" v-model="entry.selected" class="entry-check" />
          <div class="thumb-wrap">
            <img v-if="entry.thumbnail && !entry.thumbFailed"
                 :src="entry.thumbnail" class="thumb" alt="" loading="lazy"
                 @error="entry.thumbFailed = true" />
            <div v-else class="thumb placeholder">🎬</div>
          </div>
          <div class="entry-info">
            <input class="entry-title-edit" v-model="entry.title"
                   placeholder="视频标题（可编辑，不影响去重）" @click.stop />
            <div class="entry-meta">
              <span v-if="entry.uploader">👤 {{ entry.uploader }}</span>
              <span v-if="entry.duration">⏱ {{ formatDuration(entry.duration) }}</span>
            </div>
          </div>
        </label>
      </div>
    </div>

    <!-- Step3 下载任务进度 -->
    <div v-if="tasks.length > 0" class="tasks-panel">
      <h3>下载任务</h3>
      <div v-for="task in tasks" :key="task.id" class="task-item">
        <div class="task-info">
          <span class="task-title">{{ task.title }}</span>
          <span :class="['status-badge', task.status]">{{ statusText(task.status) }}</span>
        </div>
        <div v-if="task.status === 'running' || task.status === 'queued'" class="progress-bar">
          <div class="progress-fill" :style="{ width: task.progress + '%' }"></div>
        </div>
        <div class="task-meta">
          <span v-if="task.status === 'running'" class="task-pct">{{ task.progress }}%</span>
          <span v-if="task.status === 'completed' && task.videoId">
            <a :href="`/videos/${task.videoId}`">查看视频 →</a>
          </span>
          <span v-if="task.errorMessage && task.status === 'failed'" class="task-error">{{ task.errorMessage }}</span>
          <button v-if="task.status === 'failed' || task.status === 'cancelled'"
                  class="btn-ghost" @click="retryTask(task)">重试</button>
          <button v-if="task.status === 'running' || task.status === 'queued'"
                  class="btn-ghost" @click="cancelTask(task)">取消</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { downloadAPI } from '@/api/download'

const url = ref('')
const parsing = ref(false)
const parseError = ref('')
const parseResult = ref(null)
const starting = ref(false)
const format = ref('best')
const tasks = ref([])
const sockets = ref({})

const selectedCount = computed(
  () => (parseResult.value?.entries || []).filter(e => e.selected).length
)

onMounted(async () => {
  // 加载历史任务并重连进行中的任务
  try {
    const res = await downloadAPI.list()
    if (res.code === 200) {
      tasks.value = res.data || []
      tasks.value
        .filter(t => t.status === 'running' || t.status === 'queued')
        .forEach(t => connectProgress(t.id))
    }
  } catch {}
})

onUnmounted(() => {
  Object.values(sockets.value).forEach(ws => { try { ws.close() } catch {} })
})

async function parseUrl() {
  parseError.value = ''
  if (!url.value.trim()) { parseError.value = '请输入链接'; return }
  parsing.value = true
  try {
    const res = await downloadAPI.parse(url.value.trim())
    if (res.code !== 200) {
      parseError.value = res.message || '解析失败'
      return
    }
    parseResult.value = res.data
    parseResult.value.entries.forEach(e => { e.selected = true; e.thumbFailed = false })
  } catch (e) {
    parseError.value = e?.message || '解析失败'
  } finally {
    parsing.value = false
  }
}

function toggleSelectAll() {
  const entries = parseResult.value?.entries
  if (!entries) return
  const allSelected = entries.every(e => e.selected)
  entries.forEach(e => { e.selected = !allSelected })
}

async function startDownload() {
  const entries = (parseResult.value?.entries || []).filter(e => e.selected)
  if (!entries.length) return
  starting.value = true
  parseError.value = ''
  try {
    const selected = entries.map(e => ({
      url: e.url, title: e.title, uploader: e.uploader,
      duration: e.duration, thumbnail: e.thumbnail,
    }))
    const res = await downloadAPI.start(url.value.trim(), selected, format.value)
    if (res.code !== 200) {
      parseError.value = res.message || '创建下载任务失败'
      return
    }
    const created = res.data || []
    // 新任务合并到列表顶部
    tasks.value = [...created, ...tasks.value.filter(t => !created.some(c => c.id === t.id))]
    created.forEach(t => connectProgress(t.id))
    parseResult.value = null
    url.value = ''
  } catch (e) {
    parseError.value = e?.message || '创建下载任务失败'
  } finally {
    starting.value = false
  }
}

function connectProgress(taskId) {
  if (sockets.value[taskId]) sockets.value[taskId].close()
  const ws = new WebSocket(downloadAPI.wsUrl(taskId))
  sockets.value[taskId] = ws
  ws.onmessage = (ev) => {
    try {
      const msg = JSON.parse(ev.data)
      if (msg.type === 'PROGRESS') {
        const t = tasks.value.find(x => String(x.id) === msg.taskId)
        if (t) {
          t.status = msg.status
          t.progress = msg.progress
          t.errorMessage = msg.message
          if (msg.status === 'failed' || msg.status === 'cancelled') ws.close()
        }
      }
    } catch {}
  }
  ws.onclose = () => { delete sockets.value[taskId] }
}

async function retryTask(task) {
  try {
    const res = await downloadAPI.retry(task.id)
    if (res.code !== 200) { alert(res.message || '重试失败'); return }
    task.status = 'queued'
    task.progress = 0
    task.errorMessage = ''
    connectProgress(task.id)
  } catch (e) { alert(e?.message || '重试失败') }
}

async function cancelTask(task) {
  try {
    const res = await downloadAPI.cancel(task.id)
    if (res.code !== 200) { alert(res.message || '取消失败'); return }
    task.status = 'cancelled'
  } catch (e) { alert(e?.message || '取消失败') }
}

function statusText(s) {
  const map = { queued: '排队中', running: '下载中', completed: '已完成', failed: '失败', cancelled: '已取消' }
  return map[s] || s
}

function formatDuration(sec) {
  if (!sec && sec !== 0) return ''
  const m = Math.floor(sec / 60)
  const s = Math.floor(sec % 60)
  return `${m}:${String(s).padStart(2, '0')}`
}
</script>

<style scoped>
.download-page { padding: 24px; max-width: 900px; }
.hint { color: #999; font-size: 14px; margin: 8px 0 20px; }
.history-link { color: var(--primary); text-decoration: none; margin-left: 8px; font-size: 13px; }
.history-link:hover { text-decoration: underline; }

.upload-tabs { display: flex; gap: 8px; margin-bottom: 20px; border-bottom: 1px solid #eee; padding-bottom: 12px; }
.upload-tabs .tab {
  padding: 8px 16px; border-radius: 8px; color: #666;
  text-decoration: none; font-size: 14px; font-weight: 500;
  border: 1px solid transparent;
}
.upload-tabs .tab:hover { background: #f5f5f5; }
.upload-tabs .tab.active { background: var(--primary); color: #fff; }

.url-box { display: flex; gap: 10px; margin-bottom: 8px; }
.url-box input {
  flex: 1; padding: 12px 14px; border: 1px solid #ddd;
  border-radius: 8px; font-size: 14px;
}
.btn-primary {
  padding: 12px 20px; background: var(--primary); color: #fff;
  border: none; border-radius: 8px; font-size: 14px; cursor: pointer; white-space: nowrap;
}
.btn-primary:disabled { opacity: 0.6; cursor: not-allowed; }
.btn-ghost {
  padding: 6px 14px; border: 1px solid #ddd; background: #fff;
  border-radius: 6px; font-size: 13px; cursor: pointer; color: #555;
}
.btn-ghost:hover { border-color: var(--primary); color: var(--primary); }
.error-msg { color: #e74c3c; font-size: 14px; margin-top: 8px; }

.result-panel { margin-top: 24px; background: #fff; border-radius: var(--radius); box-shadow: var(--shadow); overflow: hidden; }
.result-header {
  display: flex; align-items: center; gap: 12px; flex-wrap: wrap;
  padding: 16px 20px; background: #fafafa; border-bottom: 1px solid #eee;
}
.result-title { font-weight: 600; font-size: 15px; }
.result-count { color: #999; font-size: 13px; }
.result-actions { margin-left: auto; display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.format-select { display: flex; align-items: center; gap: 6px; font-size: 13px; color: #555; }
.format-select select { padding: 6px 8px; border: 1px solid #ddd; border-radius: 6px; font-size: 13px; }

.entry-list { max-height: 480px; overflow-y: auto; }
.entry-item {
  display: flex; align-items: center; gap: 14px;
  padding: 12px 20px; cursor: pointer; border-bottom: 1px solid #f5f5f5;
  transition: background 0.15s;
}
.entry-item:hover { background: #fafafa; }
.entry-item.selected { background: #fff5f5; }
.entry-check { width: 18px; height: 18px; flex-shrink: 0; }
.thumb-wrap { width: 112px; height: 63px; flex-shrink: 0; border-radius: 6px; overflow: hidden; background: #eee; }
.thumb { width: 100%; height: 100%; object-fit: cover; display: block; }
.thumb.placeholder { display: flex; align-items: center; justify-content: center; font-size: 24px; color: #bbb; }
.entry-info { flex: 1; min-width: 0; }
.entry-title { font-size: 14px; color: #333; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.entry-title-edit {
  width: 100%;
  font-size: 14px;
  color: #333;
  border: 1px dashed #ccc;
  border-radius: 4px;
  padding: 3px 6px;
  background: #fff;
  box-sizing: border-box;
}
.entry-meta { margin-top: 4px; color: #999; font-size: 12px; display: flex; gap: 12px; }

.tasks-panel { margin-top: 32px; }
.tasks-panel h3 { margin-bottom: 12px; font-size: 16px; }
.task-item {
  background: #fff; border-radius: var(--radius); box-shadow: var(--shadow);
  padding: 14px 18px; margin-bottom: 12px;
}
.task-info { display: flex; align-items: center; gap: 10px; }
.task-title { font-size: 14px; font-weight: 500; flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.status-badge { padding: 2px 10px; border-radius: 12px; font-size: 12px; flex-shrink: 0; }
.status-badge.queued { background: #e2e3e5; color: #383d41; }
.status-badge.running { background: #fff3cd; color: #856404; }
.status-badge.completed { background: #d4edda; color: #155724; }
.status-badge.failed { background: #f8d7da; color: #721c24; }
.status-badge.cancelled { background: #e2e3e5; color: #555; }
.progress-bar { height: 6px; background: #eee; border-radius: 3px; margin-top: 10px; overflow: hidden; }
.progress-fill { height: 100%; background: var(--primary); transition: width 0.3s; }
.task-meta { margin-top: 8px; display: flex; align-items: center; gap: 12px; font-size: 13px; }
.task-meta a { color: var(--primary); text-decoration: none; }
.task-pct { color: #666; }
.task-error { color: #e74c3c; flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
</style>
