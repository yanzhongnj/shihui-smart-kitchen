<template>
  <div class="upload-progress" v-if="show">
    <div class="progress-header">
      <span>{{ uploading ? '上传中...' : '上传完成' }}</span>
      <span class="percent">{{ progress }}%</span>
    </div>
    <div class="progress-bar">
      <div class="progress-fill" :style="{ width: progress + '%' }"></div>
    </div>
    <p v-if="error" class="error">❌ {{ error }}</p>
    <p v-if="videoId" class="success">
      ✅ 上传成功！
      <router-link :to="`/app/videos/${videoId}`">查看视频</router-link>
    </p>
  </div>
</template>

<script setup>
defineProps({
  show: Boolean,
  progress: { type: Number, default: 0 },
  uploading: Boolean,
  error: String,
  videoId: [Number, null],
})
</script>

<style scoped>
.upload-progress { margin-top: 20px; }
.progress-header { display: flex; justify-content: space-between; margin-bottom: 8px; font-size: 14px; }
.percent { color: #e74c3c; font-weight: 600; }
.progress-bar { height: 8px; background: #eee; border-radius: 4px; overflow: hidden; }
.progress-fill { height: 100%; background: #e74c3c; transition: width 0.3s; border-radius: 4px; }
.error { color: #e74c3c; margin-top: 8px; font-size: 13px; }
.success { color: #27ae60; margin-top: 8px; font-size: 13px; }
.success a { color: #e74c3c; text-decoration: none; }
</style>
