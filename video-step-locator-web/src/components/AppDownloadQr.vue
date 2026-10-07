<template>
  <div class="app-download-card">
    <div class="adc-head">
      <span class="adc-title">📱 拾味 APP 下载</span>
      <span v-if="info?.available && info?.version" class="adc-version">
        v{{ info.version }}<template v-if="info?.build"> ({{ info.build }})</template>
      </span>
    </div>

    <div class="adc-body">
      <div class="adc-qr-wrap">
        <canvas ref="qrCanvas" class="adc-qr" :width="qrSize" :height="qrSize"></canvas>
        <div v-if="!info?.available" class="adc-qr-placeholder">未发布</div>
      </div>
      <div class="adc-meta">
        <p v-if="changelog" class="adc-log" :title="changelog">{{ changelog }}</p>
        <p v-else class="adc-log muted">最新 Android 安装包</p>
        <p v-if="fileSize" class="adc-size">大小 {{ fileSize }}</p>
        <a v-if="info?.available" :href="downloadUrl" class="adc-btn">直接下载 APK</a>
        <p class="adc-tip">手机扫码即可下载安装</p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import QRCode from 'qrcode'
import { appAPI, buildDownloadUrl } from '@/api/app'

const props = defineProps({
  platform: { type: String, default: 'android' },
})

const info = ref({})
const qrCanvas = ref(null)
const qrSize = 140

const downloadUrl = computed(() =>
  info.value?.available ? buildDownloadUrl(props.platform, info.value?.id || null) : '#'
)

const changelog = computed(() => info.value?.changelog || '')
const fileSize = computed(() => {
  const s = info.value?.size
  if (!s) return ''
  if (s >= 1024 * 1024) return (s / 1024 / 1024).toFixed(1) + ' MB'
  if (s >= 1024) return (s / 1024).toFixed(0) + ' KB'
  return s + ' B'
})

async function load() {
  try {
    const res = await appAPI.getDownloadInfo(props.platform)
    info.value = res?.data || {}
    if (info.value?.available) {
      await renderQr(buildDownloadUrl(props.platform, info.value.id || null))
    }
  } catch (e) {
    // 静默失败：展示"未发布"占位
    info.value = {}
  }
}

async function renderQr(text) {
  if (!qrCanvas.value) return
  await QRCode.toCanvas(qrCanvas.value, text, {
    width: qrSize,
    margin: 1,
    errorCorrectionLevel: 'M',
    color: { dark: '#212121', light: '#ffffff' },
  })
}

onMounted(load)
watch(() => props.platform, load)
</script>

<style scoped>
.app-download-card {
  background: #fff;
  border: 1px solid #eee;
  border-radius: 12px;
  padding: 14px 16px;
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.06);
}
.adc-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}
.adc-title { font-size: 15px; font-weight: 600; color: #212121; }
.adc-version { font-size: 13px; color: #e74c3c; font-weight: 600; }
.adc-body { display: flex; gap: 14px; align-items: flex-start; }
.adc-qr-wrap {
  width: 140px; height: 140px;
  flex: none;
  border: 1px solid #eee;
  border-radius: 8px;
  display: flex; align-items: center; justify-content: center;
  background: #fff;
}
.adc-qr { display: block; }
.adc-qr-placeholder { color: #bbb; font-size: 13px; }
.adc-meta { flex: 1; min-width: 0; }
.adc-log {
  font-size: 12px; color: #616161;
  margin: 0 0 6px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.adc-log.muted { color: #bbb; }
.adc-size { font-size: 12px; color: #9e9e9e; margin: 0 0 8px; }
.adc-btn {
  display: inline-block;
  padding: 7px 16px;
  background: #e74c3c;
  color: #fff;
  border-radius: 6px;
  font-size: 13px;
  text-decoration: none;
}
.adc-btn:hover { background: #d63a2a; }
.adc-tip { font-size: 11px; color: #bbb; margin: 8px 0 0; }
</style>
