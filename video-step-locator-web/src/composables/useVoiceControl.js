import { ref, onUnmounted } from 'vue'
import { voiceAPI } from '@/api/voice'

/**
 * 语音控制
 *
 * 支持两种模式：
 * 1. 实时语音识别：点击 🎤 按钮 → WebSocket 传输 PCM 音频 → 后端 → FunASR 识别 → 命令执行
 * 2. 文本命令输入：用户直接输入文字命令
 *
 * 使用 Web Audio API 采集 16kHz PCM s16le 音频，FunASR Docker 运行在端口 10096
 */
export function useVoiceControl() {
  const isListening = ref(false)
  const lastCommand = ref(null)
  const inputText = ref('')
  const feedback = ref('')
  const showMicError = ref(false)

  let ws = null
  let audioContext = null
  let scriptNode = null
  let micStream = null

  /* ========== 实时语音（PCM → WebSocket → FunASR） ========== */

  async function startListening() {
    try {
      micStream = await navigator.mediaDevices.getUserMedia({ audio: true })
      const token = localStorage.getItem('token')
      const sessionId = 'voice_' + Date.now()

      // 连接 WebSocket → 后端 VoiceAudioHandler → FunASR
      ws = new WebSocket(`/ws/voice/${sessionId}?token=${token}`)

      ws.onopen = () => {
        feedback.value = '🎤 语音识别已开启'
        setTimeout(() => { if (feedback.value === '🎤 语音识别已开启') feedback.value = '' }, 2000)
      }

      ws.onmessage = (event) => {
        try {
          const data = JSON.parse(event.data)
          if (data.type === 'RESULT' && data.action) {
            lastCommand.value = data
            feedback.value = `✅ ${data.action}`
            setTimeout(() => { if (feedback.value === `✅ ${data.action}`) feedback.value = '' }, 2000)
          }
        } catch (e) { /* ignore parse errors */ }
      }

      ws.onerror = () => {
        feedback.value = '❌ 语音服务连接失败'
        stopListening()
      }

      ws.onclose = () => {
        if (isListening.value) stopListening()
      }

      // 使用 Web Audio API 采集 16kHz PCM s16le 裸音频
      audioContext = new (window.AudioContext || window.webkitAudioContext)({
        sampleRate: 16000 // FunASR 需要 16kHz 采样率
      })

      const source = audioContext.createMediaStreamSource(micStream)

      // ScriptProcessorNode 用于获取原始 PCM 数据
      scriptNode = audioContext.createScriptProcessor(4096, 1, 1)
      scriptNode.onaudioprocess = (event) => {
        if (!ws || ws.readyState !== WebSocket.OPEN) return

        const inputData = event.inputBuffer.getChannelData(0) // Float32 [-1, 1]
        // 转换为 PCM s16le (Int16)
        const pcmData = new Int16Array(inputData.length)
        for (let i = 0; i < inputData.length; i++) {
          const s = Math.max(-1, Math.min(1, inputData[i]))
          pcmData[i] = s < 0 ? s * 0x8000 : s * 0x7FFF
        }
        // 发送 PCM 裸数据（FunASR 直接识别的格式）
        ws.send(pcmData.buffer)
      }

      source.connect(scriptNode)
      scriptNode.connect(audioContext.destination)
      isListening.value = true
    } catch (err) {
      feedback.value = '❌ 麦克风访问被拒绝'
      showMicError.value = true
      isListening.value = false
    }
  }

  function closeMicError() {
    showMicError.value = false
  }

  function stopListening() {
    isListening.value = false
    if (scriptNode) {
      scriptNode.disconnect()
      scriptNode = null
    }
    if (audioContext) {
      audioContext.close()
      audioContext = null
    }
    if (micStream) {
      micStream.getTracks().forEach(t => t.stop())
      micStream = null
    }
    if (ws) {
      ws.close()
      ws = null
    }
  }

  /* ========== 文本命令 ========== */

  async function sendCommand(text) {
    if (!text || !text.trim()) return
    try {
      const res = await voiceAPI.sendCommand(text.trim())
      if (res.data && res.data.action) {
        lastCommand.value = res.data
        feedback.value = `✅ ${res.data.action}`
      } else {
        feedback.value = '❌ 无法识别的命令'
      }
    } catch (err) {
      feedback.value = '❌ 命令发送失败'
    } finally {
      setTimeout(() => { feedback.value = '' }, 2000)
    }
  }

  function submitInput() {
    if (inputText.value.trim()) {
      sendCommand(inputText.value)
      inputText.value = ''
    }
  }

  onUnmounted(() => {
    stopListening()
  })

  return {
    isListening,
    lastCommand,
    inputText,
    feedback,
    showMicError,
    startListening,
    stopListening,
    closeMicError,
    sendCommand,
    submitInput,
  }
}
