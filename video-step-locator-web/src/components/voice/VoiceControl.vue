<template>
  <div class="voice-control">
    <!-- 麦克风按钮 -->
    <button class="voice-btn" :class="{ listening: isListening }"
            @click="$emit('toggle-voice')"
            :title="isListening ? '点击停止语音控制' : '点击开启语音控制'">
      <span class="mic-icon">{{ isListening ? '🎤' : '🎙️' }}</span>
      <!-- 录音中动画：声波波纹 -->
      <span v-if="isListening" class="wave wave-1"></span>
      <span v-if="isListening" class="wave wave-2"></span>
      <span v-if="isListening" class="wave wave-3"></span>
    </button>

    <!-- 文本命令输入 -->
    <input
      class="voice-input"
      :value="inputText"
      @input="emit('update:inputText', $event.target.value)"
      placeholder="输入命令..."
      @keyup.enter="onTextSubmit"
    />
    <button class="voice-send-btn" @click="onTextSubmit" :disabled="!inputText.trim()">
      发送
    </button>

    <!-- 反馈信息 -->
    <div v-if="feedback" class="voice-feedback" :class="{ error: feedback.startsWith('❌') }">
      {{ feedback }}
    </div>

    <!-- 麦克风权限错误弹窗 -->
    <Teleport to="body">
      <div v-if="showMicError" class="mic-error-overlay" @click.self="emit('close-mic-error')">
        <div class="mic-error-dialog">
          <div class="mic-error-icon">🎙️</div>
          <h3>无法访问麦克风</h3>
          <p>语音控制需要麦克风权限，请在浏览器地址栏左侧点击🔒或⚠️图标，将"麦克风"设置为"允许"，然后刷新页面重试。</p>
          <div class="mic-error-steps">
            <p>📌 <b>Chrome 浏览器：</b>地址栏左侧 🔒 → 网站设置 → 麦克风 → 允许</p>
            <p>📌 <b>Edge 浏览器：</b>地址栏左侧 🔒 → 站点权限 → 麦克风 → 允许</p>
          </div>
          <button class="mic-error-btn" @click="emit('close-mic-error')">我知道了</button>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script setup>
const props = defineProps({
  isListening: { type: Boolean, default: false },
  inputText: { type: String, default: '' },
  feedback: { type: String, default: '' },
  showMicError: { type: Boolean, default: false },
})

const emit = defineEmits(['toggle-voice', 'update:inputText', 'submit-text', 'close-mic-error'])

function onTextSubmit() {
  if (props.inputText.trim()) {
    emit('submit-text')
  }
}
</script>

<style scoped>
.voice-control {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 0;
  position: relative;
}
.voice-btn {
  position: relative;
  width: 36px;
  height: 36px;
  border-radius: 50%;
  border: 2px solid rgba(255,255,255,0.3);
  background: rgba(255,255,255,0.1);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.3s;
  font-size: 18px;
  flex-shrink: 0;
  z-index: 1;
}
.voice-btn:hover {
  border-color: rgba(255,255,255,0.6);
  background: rgba(255,255,255,0.2);
}
.voice-btn.listening {
  background: rgba(231, 76, 60, 0.85);
  border-color: #e74c3c;
  animation: micPulse 1.2s ease-in-out infinite;
}

/* 录音中声波波纹动画 */
.wave {
  position: absolute;
  top: 50%;
  left: 50%;
  width: 36px;
  height: 36px;
  border-radius: 50%;
  border: 2px solid rgba(231, 76, 60, 0.5);
  transform: translate(-50%, -50%);
  pointer-events: none;
  z-index: 0;
}
.wave-1 { animation: waveExpand 1.8s ease-out infinite; }
.wave-2 { animation: waveExpand 1.8s ease-out 0.6s infinite; }
.wave-3 { animation: waveExpand 1.8s ease-out 1.2s infinite; }

@keyframes waveExpand {
  0% { width: 36px; height: 36px; opacity: 0.8; }
  100% { width: 80px; height: 80px; opacity: 0; }
}

@keyframes micPulse {
  0%, 100% { box-shadow: 0 0 0 0 rgba(231, 76, 60, 0.4); }
  50% { box-shadow: 0 0 0 6px rgba(231, 76, 60, 0.15); }
}

.voice-input {
  width: 130px;
  padding: 5px 10px;
  border: 1px solid rgba(255,255,255,0.25);
  border-radius: 14px;
  background: rgba(255,255,255,0.12);
  color: #fff;
  font-size: 12px;
  outline: none;
  transition: border-color 0.2s;
}
.voice-input::placeholder { color: rgba(255,255,255,0.45); }
.voice-input:focus { border-color: rgba(255,255,255,0.5); }
.voice-send-btn {
  padding: 5px 12px;
  border: none;
  border-radius: 12px;
  background: rgba(231, 76, 60, 0.8);
  color: #fff;
  font-size: 12px;
  cursor: pointer;
  white-space: nowrap;
  flex-shrink: 0;
}
.voice-send-btn:hover:not(:disabled) { background: rgba(231, 76, 60, 1); }
.voice-send-btn:disabled { opacity: 0.4; cursor: not-allowed; }
.voice-feedback {
  font-size: 11px;
  color: #27ae60;
  white-space: nowrap;
  flex-shrink: 0;
}
.voice-feedback.error { color: #e74c3c; }
</style>

<!-- 麦克风权限弹窗样式（非 scoped，因 Teleport 到 body） -->
<style>
.mic-error-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0,0,0,0.55);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 9999;
  animation: fadeIn 0.2s ease;
}
.mic-error-dialog {
  background: #fff;
  border-radius: 12px;
  padding: 32px 28px 24px;
  max-width: 420px;
  width: 90%;
  text-align: center;
  box-shadow: 0 8px 32px rgba(0,0,0,0.2);
  animation: slideUp 0.25s ease;
}
.mic-error-icon { font-size: 48px; margin-bottom: 12px; }
.mic-error-dialog h3 {
  font-size: 18px;
  color: #333;
  margin-bottom: 12px;
}
.mic-error-dialog p {
  font-size: 14px;
  color: #666;
  line-height: 1.6;
  margin-bottom: 16px;
}
.mic-error-steps {
  text-align: left;
  background: #f8f9fa;
  border-radius: 8px;
  padding: 12px 16px;
  margin-bottom: 20px;
}
.mic-error-steps p {
  font-size: 13px;
  color: #555;
  margin-bottom: 8px;
}
.mic-error-steps p:last-child { margin-bottom: 0; }
.mic-error-btn {
  padding: 10px 32px;
  border: none;
  border-radius: 8px;
  background: var(--primary, #e74c3c);
  color: #fff;
  font-size: 14px;
  cursor: pointer;
  transition: background 0.2s;
}
.mic-error-btn:hover { background: var(--primary-hover, #c0392b); }

@keyframes fadeIn {
  from { opacity: 0; }
  to { opacity: 1; }
}
@keyframes slideUp {
  from { transform: translateY(20px); opacity: 0; }
  to { transform: translateY(0); opacity: 1; }
}
</style>
