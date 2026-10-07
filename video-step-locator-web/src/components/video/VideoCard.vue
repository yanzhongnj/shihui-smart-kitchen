<template>
  <div class="video-card" @click="$emit('click', video.id)">
    <div class="card-thumb">
      <span class="status-badge" :class="video.status">{{ statusText }}</span>
      <span v-if="video.duration" class="duration">{{ formatTime(video.duration) }}</span>
    </div>
    <div class="card-body">
      <h4 class="card-title">{{ video.title }}</h4>
      <p class="card-meta">{{ video.filename }} · {{ formatSize(video.fileSize) }}</p>
    </div>
  </div>
</template>

<script setup>
defineProps({
  video: { type: Object, required: true },
})
defineEmits(['click'])

function statusText() { /* parent handles */ }
function formatTime(seconds) {
  if (!seconds) return ''
  const m = Math.floor(seconds)
  const s = Math.floor((seconds - m) * 60)
  return `${m}:${s.toString().padStart(2, '0')}`
}
function formatSize(bytes) {
  if (!bytes) return ''
  return bytes > 1048576
    ? (bytes / 1048576).toFixed(1) + 'MB'
    : (bytes / 1024).toFixed(1) + 'KB'
}
</script>

<style scoped>
.video-card {
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.08);
  overflow: hidden;
  cursor: pointer;
  transition: transform 0.2s;
}
.video-card:hover { transform: translateY(-2px); }
.card-thumb {
  height: 140px;
  background: linear-gradient(135deg, #667eea, #e74c3c);
  display: flex;
  justify-content: space-between;
  padding: 10px;
  position: relative;
}
.status-badge {
  padding: 2px 8px;
  border-radius: 10px;
  font-size: 11px;
  color: #fff;
  background: rgba(0,0,0,0.35);
}
.duration {
  padding: 2px 6px;
  background: rgba(0,0,0,0.35);
  color: #fff;
  border-radius: 4px;
  font-size: 11px;
}
.card-body { padding: 12px; }
.card-title { font-size: 14px; font-weight: 600; margin-bottom: 4px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.card-meta { font-size: 12px; color: #999; }
</style>
