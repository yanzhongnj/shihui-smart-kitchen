<template>
  <div class="detail-page" v-if="video">
    <!-- 未处理/处理中提示：未处理的视频仍可正常播放，仅无步骤信息 -->
    <div v-if="['uploaded','pending','failed'].includes(video.status)" class="status-hint">
      ⏳ 视频尚未生成操作步骤，可正常播放；点击右上角「⚙️ 开始处理」生成步骤。
    </div>
    <div v-else-if="video.status === 'processing'" class="status-hint processing">
      🔄 视频正在处理中，完成后将显示操作步骤。
    </div>

    <!-- 视频区域 - 填满父容器 -->
    <div class="video-wrapper">
      <video id="detail-player" class="video-js vjs-big-play-centered" controls preload="auto">
        <p class="vjs-no-js">您的浏览器不支持视频播放</p>
      </video>

      <!-- 顶部浮动工具栏 -->
      <div class="video-toolbar">
        <div class="toolbar-left">
          <h2 class="video-title">
            {{ video.title }}
            <span v-if="video.reviewStatus" :class="['review-badge', video.reviewStatus]">{{ reviewText(video.reviewStatus) }}</span>
            <span v-if="['public','shared'].includes(video.visibility)" class="lock-hint">🔒 已发布/已共享，内容锁定</span>
          </h2>
        </div>
        <div class="toolbar-right">
          <button v-if="['uploaded','pending','failed'].includes(video.status)"
                  class="tool-btn" @click="retryProcess" title="开始处理视频步骤">
            <span class="tool-btn-icon">⚙️</span>
            <span class="tool-btn-label">开始处理</span>
          </button>
          <button v-if="steps.length > 0"
                  class="tool-btn" :class="{ active: stepsOpen }"
                  @click="stepsOpen = !stepsOpen; $nextTick(updateScrollButtons)" title="步骤列表">
            <span class="tool-btn-icon">📋</span>
            <span class="tool-btn-label">步骤</span>
            <span class="tool-btn-badge">{{ steps.length }}</span>
          </button>
          <button v-if="steps.length > 0" class="tool-btn" @click="openStepEdit" title="编辑步骤内容">
            <span class="tool-btn-icon">✏️</span>
            <span class="tool-btn-label">编辑步骤</span>
          </button>
          <button v-if="video.status === 'completed'" class="tool-btn" @click="showReview = true" title="审核视频与步骤">
            <span class="tool-btn-icon">📝</span>
            <span class="tool-btn-label">审核</span>
            <span v-if="video.reviewStatus === 'pending'" class="review-pending-dot"></span>
          </button>
          <VoiceControl
            :is-listening="voiceControl.isListening.value"
            :input-text="voiceControl.inputText.value"
            :feedback="voiceControl.feedback.value"
            :show-mic-error="voiceControl.showMicError.value"
            @toggle-voice="toggleVoice"
            @update:input-text="(v) => voiceControl.inputText.value = v"
            @submit-text="voiceControl.submitInput()"
            @close-mic-error="voiceControl.closeMicError()"
          />
        </div>
      </div>
    </div>

    <!-- 底部浮动步骤面板 (独立于 video-wrapper 之外) -->
    <div v-if="steps.length > 0" class="steps-bottom" :class="{ open: stepsOpen }">
      <button class="scroll-btn scroll-left" @click="scrollSteps(-1)" :disabled="atStart">‹</button>
      <div class="steps-tabs" ref="stepsContainer">
        <div v-for="(step, index) in steps" :key="step.id"
             :class="['step-tab', { active: activeStepIndex === index, looping: step.looping }]"
             @click="jumpToStep(index)">
          <div class="tab-info">
            <span class="tab-num">{{ step.stepIndex }}</span>
            <div class="tab-text">
              <span class="tab-summary">{{ step.summary }}</span>
              <span class="tab-time">⏱ {{ formatTime(step.startTime) }} - {{ formatTime(step.endTime) }}</span>
            </div>
          </div>
          <button class="tab-loop" :class="{ active: step.looping }"
                  @click.stop="toggleLoop(step.id, index)"
                  :title="step.looping ? '停止循环' : '循环播放'">🔄</button>
        </div>
      </div>
      <button class="scroll-btn scroll-right" @click="scrollSteps(1)" :disabled="atEnd">›</button>
    </div>

    <!-- 步骤编辑 / 视频审核 对话框 -->
    <StepEditDialog v-if="showStepEdit" :video-id="video.id"
                    @close="showStepEdit = false" @saved="refreshSteps" />
    <ReviewVideoDialog v-if="showReview" :video="video"
                       @close="showReview = false" @saved="refreshVideo" />
  </div>
</template>

<script setup>
import { ref, watch, onMounted, onUnmounted, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import { videoAPI } from '@/api/video'
import { stepAPI } from '@/api/step'
import { taskAPI } from '@/api/task'
import { useVoiceControl } from '@/composables/useVoiceControl'
import VoiceControl from '@/components/voice/VoiceControl.vue'
import StepEditDialog from '@/components/step/StepEditDialog.vue'
import ReviewVideoDialog from '@/components/video/ReviewVideoDialog.vue'
import videojs from 'video.js'

const route = useRoute()
const video = ref(null)
const steps = ref([])
const activeStepIndex = ref(-1)
let player = null
let animationId = null

const voiceControl = useVoiceControl()
const stepsOpen = ref(false)
const stepsContainer = ref(null)
const atStart = ref(true)
const atEnd = ref(false)
const showStepEdit = ref(false)
const showReview = ref(false)

function scrollSteps(dir) {
  const el = stepsContainer.value
  if (!el) return
  const scrollAmount = el.clientWidth * 0.6
  el.scrollBy({ left: dir * scrollAmount, behavior: 'smooth' })
  setTimeout(updateScrollButtons, 350)
}
function updateScrollButtons() {
  const el = stepsContainer.value
  if (!el) return
  atStart.value = el.scrollLeft <= 2
  atEnd.value = el.scrollLeft + el.clientWidth >= el.scrollWidth - 2
}

onMounted(async () => {
  const id = route.params.id
  try {
    const res = await videoAPI.getDetail(id)
    // 去除视频文件扩展名，如 "cooking.mp4" → "cooking"
    if (res.data && res.data.title) {
      res.data.title = res.data.title.replace(/\.(mp4|webm|avi|mov|mkv)$/i, '')
    }
    video.value = res.data

    const stepsRes = await stepAPI.getSteps(id)
    steps.value = stepsRes.data
    // 有步骤时自动打开步骤面板
    if (steps.value.length > 0) {
      stepsOpen.value = true
    }

    await nextTick()
    initPlayer()
    // Steps scroll listener
    nextTick(() => {
      const el = document.querySelector('.steps-tabs')
      if (el) {
        el.addEventListener('scroll', updateScrollButtons)
        updateScrollButtons()
      }
    })
  } catch (e) {
    console.error('Failed to load video:', e)
  }
})

function initPlayer() {
  player = videojs('detail-player', {
    controls: true,
    // 不用 fluid：尺寸完全由 CSS 控制（.video-wrapper 16:9 + max-height），
    // fluid 的 padding-top:56.25% 会与 max-height 冲突导致播放器溢出容器
    playbackRates: [0.5, 0.75, 1, 1.25, 1.5, 2],
    html5: {
      hls: { overrideNative: true },
      nativeAudioTracks: false,
      nativeVideoTracks: false,
    },
  })

  player.ready(() => {
    const streamUrl = videoAPI.getStreamUrl(video.value.id)
    player.src({ type: 'video/mp4', src: streamUrl })
    player.load()

    player.on('loadedmetadata', () => {
      startHighlightLoop()
    })
  })
}

function startHighlightLoop() {
  if (animationId) cancelAnimationFrame(animationId)

  function update() {
    if (!player || steps.value.length === 0) {
      animationId = requestAnimationFrame(update)
      return
    }

    const currentTime = player.currentTime()
    let found = -1
    for (let i = 0; i < steps.value.length; i++) {
      const s = steps.value[i]
      if (currentTime >= s.startTime && currentTime <= s.endTime) {
        found = i
        break
      }
    }

    if (found !== activeStepIndex.value) {
      activeStepIndex.value = found
      if (found >= 0) {
        const container = document.querySelector('.steps-tabs')
        const el = container?.children[found]
        if (el && container) {
          el.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'center' })
          setTimeout(updateScrollButtons, 350)
        }
      }
    }

    // 循环播放检测
    steps.value.forEach((step, idx) => {
      if (step.looping && currentTime >= step.endTime) {
        player.currentTime(step.startTime)
        player.play()
      }
    })

    animationId = requestAnimationFrame(update)
  }
  update()
}

function syncStepHighlight() {
  if (!player || steps.value.length === 0) return
  const currentTime = player.currentTime()
  let found = -1
  for (let i = 0; i < steps.value.length; i++) {
    const s = steps.value[i]
    if (currentTime >= s.startTime && currentTime <= s.endTime) {
      found = i
      break
    }
  }
  if (found !== activeStepIndex.value) {
    activeStepIndex.value = found
    if (found >= 0) {
      nextTick(() => {
        const container = document.querySelector('.steps-tabs')
        const el = container?.children[found]
        if (el && container) {
          el.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'center' })
          setTimeout(updateScrollButtons, 350)
        }
      })
    }
  }
}

/**
 * 异步 seek 到指定时间，seek 完成后同步 UI（进度条 + 步骤高亮）。
 * Video.js currentTime() 是异步的，必须等 'seeked' 事件触发后再刷新 UI。
 */
function seekTo(time) {
  if (!player) return
  const dur = player.duration()
  if (dur && isFinite(dur) && dur > 0) {
    time = Math.max(0, Math.min(dur, time))
  } else {
    time = Math.max(0, time)
  }
  // Video.js 7 中 currentTime + play 组合保证 seek 生效
  player.one('seeked', () => {
    player.trigger('timeupdate')    // 刷新进度条
    syncStepHighlight()             // 刷新步骤高亮
  })
  player.currentTime(time)
}

function jumpToStep(index) {
  const step = steps.value[index]
  if (!step || !player) return

  const trySeek = () => {
    player.one('seeked', () => {
      const promise = player.play()
      if (promise && typeof promise.catch === 'function') {
        promise.catch(err => console.warn('Play blocked:', err?.message))
      }
    })
    player.currentTime(step.startTime)
  }

  // If metadata not loaded yet, wait for it
  const dur = player.duration()
  if (!dur || dur <= 0 || !isFinite(dur)) {
    player.one('loadedmetadata', trySeek)
    return
  }

  trySeek()
}

async function toggleLoop(stepId, index) {
  try {
    const res = await stepAPI.toggleLoop(stepId)
    steps.value[index].looping = res.data.isLooping
  } catch (e) {
    console.error('Toggle loop failed:', e)
  }
}

async function retryProcess() {
  await taskAPI.triggerProcess(video.value.id)
  video.value.status = 'processing'
}

function toggleVoice() {
  if (voiceControl.isListening.value) {
    voiceControl.stopListening()
  } else {
    voiceControl.startListening()
  }
}

function handleVoiceCommand(command) {
  switch (command.action) {
    case 'NEXT_STEP':
      if (activeStepIndex.value < steps.value.length - 1)
        jumpToStep(activeStepIndex.value + 1)
      break
    case 'PREV_STEP':
      if (activeStepIndex.value > 0)
        jumpToStep(activeStepIndex.value - 1)
      break
    case 'LOOP_STEP':
      const idx = command.params?.stepIndex - 1
      if (idx >= 0 && idx < steps.value.length) {
        toggleLoop(steps.value[idx].id, idx)
        jumpToStep(idx)
      }
      break
    case 'STOP_LOOP':
      steps.value.forEach(s => { if (s.looping) toggleLoop(s.id, steps.value.indexOf(s)) })
      break
    case 'LOOP_CURRENT':
      if (activeStepIndex.value >= 0) {
        const currentStep = steps.value[activeStepIndex.value]
        if (!currentStep.looping) {
          toggleLoop(currentStep.id, activeStepIndex.value)
        }
      }
      break
    case 'FORWARD':
      if (player) {
        const sec = command.params?.seconds || 10
        const cur = player.currentTime() || 0
        const dur = player.duration() || 0
        seekTo(dur > 0 ? Math.min(dur, cur + sec) : cur + sec)
      }
      break
    case 'BACKWARD':
      if (player) {
        const sec = command.params?.seconds || 10
        const cur = player.currentTime() || 0
        seekTo(Math.max(0, cur - sec))
      }
      break
    case 'SPEED':
      if (player) {
        const speed = command.params?.speed || 1.0
        player.playbackRate(speed)
        // 强制刷新控制栏倍速显示
        try {
          const btn = player.controlBar?.playbackRateMenuButton
          if (btn && btn.menuButtonContent) {
            btn.menuButtonContent.updateLabel()
          }
          // Video.js 8 兼容
          if (btn && btn.labelEl_) {
            btn.labelEl_.textContent = speed + 'x'
          }
        } catch (e) { /* 忽略控制栏兼容错误 */ }
      }
      break
    case 'PLAY': player?.play(); break
    case 'PAUSE': player?.pause(); break
    case 'RESTART':
      if (player) {
        player.one('seeked', () => {
          player.play()
          player.trigger('timeupdate')
          syncStepHighlight()
        })
        player.currentTime(0)
      }
      break
  }
}

// 监听语音控制识别的命令
watch(() => voiceControl.lastCommand.value, (cmd) => {
  if (cmd && cmd.action) {
    handleVoiceCommand(cmd)
  }
})

async function refreshSteps() {
  try {
    const res = await stepAPI.getSteps(video.value.id)
    steps.value = res.data
  } catch (e) { console.error('refresh steps failed', e) }
}

function reviewText(s) {
  const map = { pending: '待审核', approved: '已通过', rejected: '已驳回' }
  return map[s] || (s || '未审核')
}

function openStepEdit() {
  // 已发布/已共享 → 内容锁定；后端亦强制（含分享链接锁定）
  if (video.value.visibility === 'public' || video.value.visibility === 'shared') {
    alert('该视频已发布或共享，内容已锁定；请先取消发布/共享后再编辑')
    return
  }
  showStepEdit.value = true
}

async function refreshVideo() {
  showReview.value = false
  try {
    const res = await videoAPI.getDetail(video.value.id)
    if (res.data && res.data.title) {
      res.data.title = res.data.title.replace(/\.(mp4|webm|avi|mov|mkv)$/i, '')
    }
    video.value = res.data
  } catch (e) { console.error('refresh video failed', e) }
}

function formatTime(seconds) {
  if (!seconds && seconds !== 0) return '--:--'
  const m = Math.floor(seconds / 60)
  const s = Math.floor(seconds % 60)
  return `${m}:${s.toString().padStart(2, '0')}`
}

onUnmounted(() => {
  if (animationId) cancelAnimationFrame(animationId)
  if (player) { player.dispose(); player = null }
})
</script>

<style scoped>
.detail-page {
  min-height: calc(100vh - var(--header-height, 56px));
  background: #000;
  display: flex;
  flex-direction: column;
}

/* ===== 审核状态徽标 / 待审核红点 ===== */
.review-badge { display: inline-block; margin-left: 8px; padding: 2px 8px; border-radius: 10px; font-size: 11px; vertical-align: middle; }
.review-badge.pending { background: #fff3cd; color: #856404; }
.review-badge.approved { background: #d1ecf1; color: #0c5460; }
.review-badge.rejected { background: #f8d7da; color: #721c24; }
.lock-hint { margin-left: 8px; font-size: 12px; color: #e6a23c; font-weight: 600; vertical-align: middle; }
.review-pending-dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%; background: #e67e22; margin-left: 6px; }

/* ===== 未处理/处理中提示条 ===== */
.status-hint {
  flex-shrink: 0;
  padding: 10px 16px;
  background: #fff8e1;
  color: #8a6d3b;
  font-size: 13px;
  border-bottom: 1px solid #eee;
}
.status-hint.processing {
  background: #e8f4fd;
  color: #0c5460;
}

/* ===== 视频容器 ===== */
.video-wrapper {
  position: relative;
  background: #000;
  width: 100%;
  max-width: 960px;
  margin: 0 auto;
  aspect-ratio: 16 / 9;
  max-height: 58vh;
  flex-shrink: 0;
}
.video-wrapper > :deep(.video-js) {
  position: absolute;
  top: 0;
  left: 0;
  width: 100% !important;
  height: 100% !important;
}
.video-wrapper :deep(.vjs-tech) {
  object-fit: contain;
}

/* ===== 顶部浮动工具栏 (左对齐，防止小屏幕溢出) ===== */
.video-toolbar {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  padding: 10px 14px;
  background: linear-gradient(to bottom, rgba(0,0,0,0.92) 0%, rgba(0,0,0,0.6) 50%, rgba(0,0,0,0.15) 80%, transparent 100%);
  z-index: 10;
  pointer-events: none;
  min-height: 56px;
}
.video-toolbar > * { pointer-events: auto; }
.video-title {
  font-size: 16px;
  color: #fff;
  margin: 0;
  text-shadow: 0 1px 4px rgba(0,0,0,0.5);
  flex-shrink: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.toolbar-right {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

/* ===== 工具栏按钮 ===== */
.tool-btn {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 8px 14px;
  background: rgba(255,255,255,0.15);
  backdrop-filter: blur(8px);
  border: 1px solid rgba(255,255,255,0.25);
  border-radius: 20px;
  color: #fff;
  cursor: pointer;
  font-size: 14px;
  transition: all 0.2s;
}
.tool-btn:hover { background: rgba(255,255,255,0.25); }
.tool-btn.active { background: var(--primary); border-color: var(--primary); }
.tool-btn-icon { font-size: 16px; }
.tool-btn-badge {
  background: rgba(255,255,255,0.2);
  padding: 0 6px;
  border-radius: 10px;
  font-size: 11px;
  line-height: 18px;
}

/* Voice control */
.toolbar-right :deep(.voice-control) { padding: 0; gap: 8px; }
.toolbar-right :deep(.voice-btn) {
  width: 40px; height: 40px;
  background: rgba(255,255,255,0.15);
  backdrop-filter: blur(8px);
  border: 1px solid rgba(255,255,255,0.25);
  border-radius: 50%;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  transition: all 0.2s;
}
.toolbar-right :deep(.voice-btn:hover) { background: rgba(255,255,255,0.25); }
.toolbar-right :deep(.voice-btn.listening) {
  background: var(--primary, #e74c3c);
  border-color: var(--primary, #e74c3c);
  animation: pulse 1.5s infinite;
}
@keyframes pulse {
  0%, 100% { box-shadow: 0 0 0 0 rgba(231,76,60,0.4); }
  50% { box-shadow: 0 0 0 12px rgba(231,76,60,0); }
}
.toolbar-right :deep(.voice-status),
.toolbar-right :deep(.voice-command) { display: none; }

/* ===== 底部步骤面板 ===== */
.steps-bottom {
  display: flex;
  align-items: stretch;
  overflow: hidden;
  min-width: 0;
  height: 0;
  transition: height 0.3s cubic-bezier(0.4, 0, 0.2, 1);
  border-top: 4px solid transparent;
  background-clip: padding-box;
}
.steps-bottom.open {
  height: 88px;
}

.scroll-btn {
  pointer-events: auto;
  flex-shrink: 0;
  width: 32px;
  border: none;
  background: rgba(0,0,0,0.6);
  backdrop-filter: blur(8px);
  color: rgba(255,255,255,0.8);
  font-size: 22px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s;
  z-index: 2;
}
.scroll-btn:hover { background: rgba(0,0,0,0.85); color: #fff; }
.scroll-btn:disabled { opacity: 0.15; cursor: default; }
.scroll-left { border-radius: 0 8px 0 0; margin-right: 1px; }
.scroll-right { border-radius: 8px 0 0 0; margin-left: 1px; }

.steps-tabs {
  pointer-events: auto;
  flex: 1;
  min-width: 0;
  display: flex;
  gap: 8px;
  padding: 8px;
  overflow-x: auto;
  overflow-y: hidden;
  background: rgba(0,0,0,0.8);
  backdrop-filter: blur(12px);
  scrollbar-width: thin;
  scrollbar-color: rgba(255,255,255,0.2) transparent;
  align-items: center;
}
.steps-tabs::-webkit-scrollbar { height: 2px; }
.steps-tabs::-webkit-scrollbar-thumb { background: rgba(255,255,255,0.2); border-radius: 2px; }

.step-tab {
  flex: 0 0 240px;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  background: rgba(255,255,255,0.08);
  border: 1px solid rgba(255,255,255,0.1);
  border-radius: 10px;
  cursor: pointer;
  transition: all 0.2s;
  user-select: none;
}
.step-tab:hover { background: rgba(255,255,255,0.15); border-color: rgba(255,255,255,0.25); }
.step-tab.active {
  background: rgba(231,76,60,0.25);
  border-color: var(--primary);
}
.step-tab.looping { border-color: #27ae60; }
.tab-info {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  flex: 1;
}
.tab-num {
  flex-shrink: 0;
  width: 26px; height: 26px;
  background: var(--primary);
  color: #fff;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  font-size: 12px;
}
.tab-text {
  min-width: 0;
  flex: 1;
}
.tab-summary {
  display: block;
  font-size: 13px;
  color: rgba(255,255,255,0.9);
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  line-height: 1.3;
}
.tab-time {
  display: block;
  font-size: 11px;
  color: rgba(255,255,255,0.5);
  margin-top: 1px;
}
.tab-loop {
  flex-shrink: 0;
  width: 28px; height: 28px;
  background: rgba(255,255,255,0.08);
  border: 1px solid rgba(255,255,255,0.15);
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s;
}
.tab-loop:hover { background: rgba(255,255,255,0.2); }
.tab-loop.active { background: #27ae60; border-color: #27ae60; }

/* ===== 手机端适配 ===== */
@media (max-width: 768px) {
  .detail-page {
    top: 0;
    z-index: 100;
  }
  .video-toolbar {
    padding: 8px 10px;
    min-height: 48px;
    gap: 4px;
    background: linear-gradient(to bottom, rgba(0,0,0,0.95) 0%, rgba(0,0,0,0.7) 60%, rgba(0,0,0,0.2) 100%);
  }
  .video-title {
    font-size: 14px;
    max-width: 120px;
  }
  .toolbar-right { gap: 4px; }
  .steps-bottom {
    padding: 6px 4px;
    gap: 2px;
  }
  .step-tab { flex: 0 0 180px; padding: 4px 8px; }
  .tab-text { display: none; }
  .step-tab.active .tab-text,
  .step-tab.looping .tab-text { display: block; font-size: 11px; }
}
</style>
