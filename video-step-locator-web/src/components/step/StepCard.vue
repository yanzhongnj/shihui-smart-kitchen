<template>
  <div class="step-card" :class="{ active: isActive, 'conf-low': confidence === 'low' }"
       @click="$emit('click')">
    <div class="step-number">{{ step.stepIndex }}</div>
    <div class="step-content">
      <div class="step-summary">{{ step.summary }}</div>
      <div class="step-time">⏱ {{ formatTime(step.startTime) }} - {{ formatTime(step.endTime) }}</div>
      <div v-if="step.originalText" class="step-text">{{ step.originalText }}</div>
    </div>
    <div class="step-actions">
      <button class="loop-btn" :class="{ active: isLooping }"
              @click.stop="$emit('toggle-loop', step.id)"
              :title="isLooping ? '停止循环' : '循环播放'">
        🔄
      </button>
    </div>
  </div>
</template>

<script setup>
defineProps({
  step: { type: Object, required: true },
  isActive: Boolean,
  isLooping: Boolean,
  confidence: { type: String, default: 'low' },
})
defineEmits(['click', 'toggle-loop'])

function formatTime(seconds) {
  if (!seconds && seconds !== 0) return '--:--'
  const m = Math.floor(seconds)
  const s = Math.floor((seconds - m) * 60)
  return `${m}:${s.toString().padStart(2, '0')}`
}
</script>

<style scoped>
.step-card {
  display: flex;
  padding: 12px;
  border: 2px solid transparent;
  border-radius: 8px;
  margin-bottom: 8px;
  cursor: pointer;
  transition: all 0.2s;
  background: #fff;
}
.step-card:hover { background: #f9f9f9; }
.step-card.active { border-color: #e74c3c; background: #fff5f5; }
.step-card.conf-low { opacity: 0.75; }
.step-number {
  width: 32px; height: 32px;
  background: #e74c3c; color: #fff;
  border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
  font-weight: 700; font-size: 14px; flex-shrink: 0; margin-right: 12px;
}
.step-content { flex: 1; min-width: 0; }
.step-summary { font-size: 14px; font-weight: 500; margin-bottom: 4px; }
.step-time { font-size: 12px; color: #666; margin-bottom: 2px; }
.step-text { font-size: 12px; color: #999; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.loop-btn {
  background: none; border: 1px solid #ddd; border-radius: 6px;
  padding: 6px 10px; cursor: pointer; font-size: 16px;
  transition: all 0.2s;
}
.loop-btn.active { background: #e74c3c; color: #fff; border-color: #e74c3c; }
</style>
