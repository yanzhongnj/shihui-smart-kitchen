<template>
  <div class="modal-overlay" @click.self="$emit('close')">
    <div class="modal-content step-edit-dialog">
      <div class="modal-header">
        <h2>编辑步骤内容</h2>
        <button class="close-btn" @click="$emit('close')">&times;</button>
      </div>
      <p class="hint">修改「摘要」或「原文」后点击 💾 保存，改动即时生效。</p>

      <div v-if="loading" class="dialog-loading">⏳ 加载步骤中...</div>
      <div v-else-if="steps.length === 0" class="dialog-empty">该视频暂无步骤数据</div>

      <div v-else class="step-edit-list">
        <div v-for="step in steps" :key="step.id" class="step-edit-item">
          <div class="step-head">
            <span class="step-num">{{ step.stepIndex }}</span>
            <span class="step-time">⏱ {{ formatTime(step.startTime) }} - {{ formatTime(step.endTime) }}</span>
            <button class="save-btn" :disabled="savingId === step.id" @click="saveStep(step)">
              {{ savingId === step.id ? '保存中...' : '💾 保存' }}
            </button>
          </div>
          <div class="form-group">
            <label>摘要</label>
            <input v-model="step.summary" placeholder="步骤摘要（显示在步骤条上）" />
          </div>
          <div class="form-group">
            <label>原文（视频解说转写）</label>
            <textarea v-model="step.originalText" rows="3" placeholder="该步骤对应的解说原文"></textarea>
          </div>
          <p v-if="step._saved" class="saved-msg">✅ 已保存</p>
          <p v-if="step._error" class="error-msg">❌ {{ step._error }}</p>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { stepAPI } from '@/api/step'

const props = defineProps({
  videoId: { type: [Number, String], required: true },
})
const emit = defineEmits(['close', 'saved'])

const steps = ref([])
const loading = ref(false)
const savingId = ref(null)

onMounted(loadSteps)

async function loadSteps() {
  loading.value = true
  try {
    const res = await stepAPI.getSteps(props.videoId)
    steps.value = (res.data || []).map(s => ({ ...s, _saved: false, _error: '' }))
  } catch (e) {
    steps.value = []
  } finally {
    loading.value = false
  }
}

async function saveStep(step) {
  savingId.value = step.id
  step._saved = false
  step._error = ''
  try {
    await stepAPI.updateStep(step.id, {
      summary: step.summary,
      originalText: step.originalText,
    })
    step._saved = true
    emit('saved')
    setTimeout(() => { step._saved = false }, 2000)
  } catch (e) {
    step._error = e?.message || '保存失败，请重试'
  } finally {
    savingId.value = null
  }
}

function formatTime(seconds) {
  if (!seconds && seconds !== 0) return '--:--'
  const m = Math.floor(seconds / 60)
  const s = Math.floor(seconds % 60)
  return `${m}:${s.toString().padStart(2, '0')}`
}
</script>

<style scoped>
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0,0,0,0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1100;
}
.modal-content {
  background: #fff;
  border-radius: 12px;
  padding: 24px;
  width: 640px;
  max-width: 94vw;
  max-height: 88vh;
  display: flex;
  flex-direction: column;
}
.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 4px;
}
.modal-header h2 { margin: 0; font-size: 18px; }
.close-btn {
  background: none; border: none; font-size: 24px; line-height: 1;
  cursor: pointer; color: #999; padding: 0 4px;
}
.close-btn:hover { color: #333; }
.hint { color: #888; font-size: 13px; margin: 0 0 12px; }
.dialog-loading, .dialog-empty { text-align: center; padding: 40px; color: #999; }
.step-edit-list {
  overflow-y: auto;
  padding-right: 4px;
}
.step-edit-item {
  border: 1px solid #eee;
  border-radius: 10px;
  padding: 12px 14px;
  margin-bottom: 12px;
}
.step-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}
.step-num {
  flex-shrink: 0;
  width: 26px; height: 26px;
  background: var(--primary);
  color: #fff;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  font-size: 13px;
}
.step-time { font-size: 12px; color: #666; flex: 1; }
.save-btn {
  padding: 6px 14px;
  background: var(--primary);
  color: #fff;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
}
.save-btn:disabled { opacity: 0.6; cursor: default; }
.form-group { margin-bottom: 8px; }
.form-group label { display: block; font-size: 12px; color: #666; margin-bottom: 4px; }
.form-group input,
.form-group textarea {
  width: 100%;
  box-sizing: border-box;
  padding: 8px 10px;
  border: 1px solid #ddd;
  border-radius: 6px;
  font-size: 14px;
  font-family: inherit;
  resize: vertical;
}
.form-group input:focus,
.form-group textarea:focus { outline: none; border-color: var(--primary); }
.saved-msg { margin: 4px 0 0; color: #27ae60; font-size: 12px; }
.error-msg { margin: 4px 0 0; color: #e74c3c; font-size: 12px; }
</style>
