<template>
  <div class="upload-page">
    <div class="upload-tabs">
      <router-link to="/app/videos/upload" class="tab" active-class="active">📤 本地上传</router-link>
      <router-link to="/app/videos/download" class="tab" active-class="active">🔗 链接下载</router-link>
    </div>
    <p class="hint">支持 mp4/webm/avi/mov 格式，最大 1GB</p>

    <div class="drop-zone" @drop.prevent="handleDrop" @dragover.prevent
         :class="{ dragging: isDragging }"
         @dragenter="isDragging = true" @dragleave="isDragging = false">
      <div v-if="!selectedFile" class="drop-content">
        <span class="icon">📁</span>
        <p>拖拽视频到此处，或点击选择文件</p>
        <input type="file" ref="fileInput" accept="video/*" @change="handleFileSelect" hidden />
        <button class="select-btn" @click="$refs.fileInput.click()">选择文件</button>
      </div>
      <div v-else class="file-info">
        <p>📹 {{ selectedFile.name }}</p>
        <p class="size">{{ formatSize(selectedFile.size) }}</p>
      </div>
    </div>

    <div v-if="selectedFile" class="upload-form">
      <div class="form-group">
        <label>视频标题</label>
        <input v-model="title" placeholder="输入视频标题（可选，默认使用文件名）" />
      </div>
      <button class="btn-primary" @click="startUpload" :disabled="uploading">
        {{ uploading ? `上传中 ${uploadProgress}%` : '开始上传' }}
      </button>

      <div v-if="uploading" class="progress-bar">
        <div class="progress-fill" :style="{ width: uploadProgress + '%' }"></div>
      </div>

      <p v-if="uploadResult" class="success-msg">
        ✅ 上传成功！视频已入库，可手动开始处理
        <router-link :to="`/videos/${uploadResult}`">查看视频</router-link>
      </p>
      <p v-if="uploadError" class="error-msg">❌ {{ uploadError }}</p>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { videoAPI } from '@/api/video'
import SparkMD5 from 'spark-md5'

const selectedFile = ref(null)
const isDragging = ref(false)
const title = ref('')
const uploading = ref(false)
const uploadProgress = ref(0)
const uploadResult = ref(null)
const uploadError = ref('')
const CHUNK_SIZE = 5 * 1024 * 1024

function handleDrop(e) {
  isDragging.value = false
  const file = e.dataTransfer.files[0]
  if (file) selectFile(file)
}

function handleFileSelect(e) {
  const file = e.target.files[0]
  if (file) selectFile(file)
}

function selectFile(file) {
  if (!file.type.startsWith('video/')) {
    uploadError.value = '请选择视频文件'
    return
  }
  if (file.size > 1073741824) {
    uploadError.value = '文件大小不能超过1GB'
    return
  }
  selectedFile.value = file
  title.value = file.name.replace(/\.[^.]+$/, '')
  uploadError.value = ''
}

async function startUpload() {
  uploading.value = true
  uploadProgress.value = 0
  uploadError.value = ''
  uploadResult.value = null

  try {
    const file = selectedFile.value
    const totalChunks = Math.ceil(file.size / CHUNK_SIZE)

    // 1. 初始化上传
    const md5 = await calculateMd5(file)
    const initRes = await videoAPI.initUpload({
      filename: file.name,
      fileSize: file.size,
      md5,
      totalChunks,
    })
    const { uploadId } = initRes.data

    // 2. 上传分片
    for (let i = 0; i < totalChunks; i++) {
      const start = i * CHUNK_SIZE
      const end = Math.min(start + CHUNK_SIZE, file.size)
      const chunk = file.slice(start, end)

      const formData = new FormData()
      formData.append('uploadId', uploadId)
      formData.append('chunkIndex', i)
      formData.append('file', chunk)

      await videoAPI.uploadChunk(formData)
      uploadProgress.value = Math.round(((i + 1) / totalChunks) * 90)
    }

    // 3. 合并
    const mergeRes = await videoAPI.mergeChunks(uploadId)
    uploadProgress.value = 100
    uploadResult.value = mergeRes.data.videoId

  } catch (e) {
    uploadError.value = e?.message || '上传失败'
  } finally {
    uploading.value = false
  }
}

async function calculateMd5(file) {
  // 使用 spark-md5 增量计算大文件 MD5，避免一次性加载到内存
  return new Promise((resolve, reject) => {
    const spark = new SparkMD5.ArrayBuffer()
    const reader = new FileReader()
    const chunkSize = 2 * 1024 * 1024 // 2MB 分片读取
    let offset = 0

    reader.onerror = () => reject(new Error('文件读取失败'))

    function loadNext() {
      const slice = file.slice(offset, offset + chunkSize)
      reader.readAsArrayBuffer(slice)
    }

    reader.onload = (e) => {
      spark.append(e.target.result)
      offset += chunkSize
      if (offset < file.size) {
        loadNext()
      } else {
        resolve(spark.end())
      }
    }

    loadNext()
  })
}

function formatSize(bytes) {
  return bytes > 1048576 ? (bytes / 1048576).toFixed(1) + 'MB' : (bytes / 1024).toFixed(1) + 'KB'
}
</script>

<style scoped>
.upload-page { padding: 24px; max-width: 600px; }
.upload-tabs { display: flex; gap: 8px; margin-bottom: 20px; border-bottom: 1px solid #eee; padding-bottom: 12px; }
.upload-tabs .tab {
  padding: 8px 16px; border-radius: 8px; color: #666;
  text-decoration: none; font-size: 14px; font-weight: 500;
  border: 1px solid transparent;
}
.upload-tabs .tab:hover { background: #f5f5f5; }
.upload-tabs .tab.active { background: var(--primary); color: #fff; }
.hint { color: #999; font-size: 14px; margin: 8px 0 24px; }
.drop-zone {
  border: 2px dashed #ddd;
  border-radius: var(--radius);
  padding: 40px;
  text-align: center;
  cursor: pointer;
  transition: all 0.2s;
  background: #fff;
}
.drop-zone.dragging { border-color: var(--primary); background: #fff5f5; }
.drop-content .icon { font-size: 48px; }
.drop-content p { margin: 12px 0; color: #666; }
.select-btn {
  padding: 8px 20px;
  background: var(--primary);
  color: #fff;
  border: none;
  border-radius: 6px;
  cursor: pointer;
}
.file-info p { margin: 4px 0; }
.file-info .size { color: #999; font-size: 13px; }
.upload-form { margin-top: 24px; }
.form-group { margin-bottom: 16px; }
.form-group label { display: block; margin-bottom: 6px; font-weight: 500; }
.form-group input {
  width: 100%;
  padding: 10px 14px;
  border: 1px solid #ddd;
  border-radius: 8px;
  font-size: 14px;
}
.btn-primary {
  padding: 12px 24px;
  background: var(--primary);
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 16px;
  cursor: pointer;
}
.btn-primary:disabled { opacity: 0.6; }
.progress-bar {
  height: 8px;
  background: #eee;
  border-radius: 4px;
  margin-top: 16px;
  overflow: hidden;
}
.progress-fill {
  height: 100%;
  background: var(--primary);
  transition: width 0.3s;
}
.success-msg { color: #27ae60; margin-top: 12px; }
.success-msg a { color: var(--primary); }
.error-msg { color: #e74c3c; margin-top: 12px; }
</style>
