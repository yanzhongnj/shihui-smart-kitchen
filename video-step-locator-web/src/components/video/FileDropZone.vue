<template>
  <div
    class="drop-zone"
    :class="{ dragging }"
    @drop.prevent="onDrop"
    @dragover.prevent
    @dragenter="dragging = true"
    @dragleave="dragging = false"
  >
    <div v-if="!file" class="drop-content">
      <span class="icon">📁</span>
      <p>拖拽视频到此处，或点击选择</p>
      <button class="select-btn" @click="$refs.input.click()">选择文件</button>
      <input ref="input" type="file" accept="video/*" hidden @change="onSelect" />
    </div>
    <div v-else class="file-info">
      <span class="icon">📹</span>
      <p>{{ file.name }}</p>
      <p class="size">{{ formatSize(file.size) }}</p>
      <button class="change-btn" @click="remove">更换文件</button>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'

const emit = defineEmits(['file'])
const file = ref(null)
const dragging = ref(false)

function onDrop(e) {
  dragging.value = false
  const f = e.dataTransfer.files[0]
  if (f && f.type.startsWith('video/')) setFile(f)
}

function onSelect(e) {
  const f = e.target.files[0]
  if (f) setFile(f)
}

function setFile(f) {
  if (f.size > 1073741824) { alert('文件不能超过1GB'); return }
  file.value = f
  emit('file', f)
}

function remove() {
  file.value = null
  emit('file', null)
}

function formatSize(bytes) {
  if (!bytes) return ''
  return bytes > 1048576
    ? (bytes / 1048576).toFixed(1) + 'MB'
    : (bytes / 1024).toFixed(1) + 'KB'
}
</script>

<style scoped>
.drop-zone {
  border: 2px dashed #ddd;
  border-radius: 12px;
  padding: 40px;
  text-align: center;
  cursor: pointer;
  transition: all 0.2s;
  background: #fff;
}
.drop-zone.dragging { border-color: #e74c3c; background: #fff5f5; }
.drop-content .icon { font-size: 48px; }
.drop-content p { margin: 12px 0; color: #666; }
.select-btn {
  padding: 8px 20px;
  background: #e74c3c;
  color: #fff;
  border: none;
  border-radius: 6px;
  cursor: pointer;
}
.file-info { line-height: 1.8; }
.file-info .icon { font-size: 32px; }
.file-info .size { color: #999; font-size: 13px; }
.change-btn {
  margin-top: 8px;
  padding: 4px 12px;
  background: none;
  border: 1px solid #ddd;
  border-radius: 4px;
  cursor: pointer;
  font-size: 12px;
}
</style>
