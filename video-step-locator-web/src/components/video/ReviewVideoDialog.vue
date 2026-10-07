<template>
  <div class="modal-overlay" @click.self="$emit('close')">
    <div class="modal-content review-dialog">
      <div class="modal-header">
        <h3>审核视频</h3>
        <button class="close-btn" @click="$emit('close')">&times;</button>
      </div>
      <p class="review-title">{{ video.title }}</p>
      <p class="hint">审核状态：{{ reviewText(video.reviewStatus) }} ｜ 可见性：{{ video.visibility === 'public' ? '公开（体验中）' : '私密' }}</p>
      <label class="review-label">审核意见（驳回时建议填写）</label>
      <textarea v-model="comment" rows="2" placeholder="通过可留空；驳回请说明原因"></textarea>
      <p v-if="errorMsg" class="error-msg">❌ {{ errorMsg }}</p>
      <div class="modal-actions">
        <button class="btn" @click="$emit('close')">取消</button>
        <button v-if="video.visibility === 'public'" class="btn danger" @click="unpublish" :disabled="busy">📥 下架</button>
        <button v-else class="btn" @click="publish" :disabled="busy || video.reviewStatus !== 'approved'">🚀 发布到体验</button>
        <button class="btn danger" @click="review(false)" :disabled="busy">❌ 驳回</button>
        <button class="btn primary" @click="review(true)" :disabled="busy">✅ 通过</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { videoAPI } from '@/api/video'

const props = defineProps({
  video: { type: Object, required: true },
})
const emit = defineEmits(['close', 'saved'])

const comment = ref('')
const busy = ref(false)
const errorMsg = ref('')

function reviewText(s) {
  const map = { pending: '待审核', approved: '已通过', rejected: '已驳回' }
  return map[s] || (s || '未审核')
}

async function review(approved) {
  busy.value = true
  errorMsg.value = ''
  try {
    await videoAPI.review(props.video.id, approved, comment.value)
    emit('saved')
  } catch (e) {
    errorMsg.value = e?.message || '操作失败，请重试'
  } finally {
    busy.value = false
  }
}

async function publish() {
  busy.value = true
  errorMsg.value = ''
  try {
    await videoAPI.publish(props.video.id)
    emit('saved')
  } catch (e) {
    errorMsg.value = e?.message || '操作失败，请重试'
  } finally {
    busy.value = false
  }
}

async function unpublish() {
  busy.value = true
  errorMsg.value = ''
  try {
    await videoAPI.unpublish(props.video.id)
    emit('saved')
  } catch (e) {
    errorMsg.value = e?.message || '操作失败，请重试'
  } finally {
    busy.value = false
  }
}
</script>

<style scoped>
.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.45); display: flex; align-items: center; justify-content: center; z-index: 1000; }
.modal-content { background: #fff; border-radius: 12px; padding: 20px 24px; width: 440px; max-width: 92vw; box-shadow: 0 12px 40px rgba(0,0,0,0.25); }
.modal-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.modal-header h3 { margin: 0; font-size: 18px; }
.close-btn { background: none; border: none; font-size: 22px; cursor: pointer; color: #999; line-height: 1; }
.review-title { font-size: 15px; font-weight: 600; margin: 4px 0 8px; color: #333; }
.hint { color: #777; font-size: 13px; margin: 0 0 12px; }
.review-label { display: block; font-size: 13px; color: #555; margin-bottom: 6px; }
textarea { width: 100%; padding: 8px 10px; border: 1px solid #ddd; border-radius: 6px; font-size: 13px; resize: vertical; box-sizing: border-box; }
.error-msg { color: #e74c3c; font-size: 13px; margin: 8px 0 0; }
.modal-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 16px; flex-wrap: wrap; }
.btn { padding: 8px 16px; border: 1px solid #ddd; background: #fff; border-radius: 6px; cursor: pointer; font-size: 13px; }
.btn.primary { background: var(--primary); color: #fff; border-color: var(--primary); }
.btn.danger { background: #e74c3c; color: #fff; border-color: #e74c3c; }
.btn:disabled { opacity: 0.5; cursor: not-allowed; }
</style>
