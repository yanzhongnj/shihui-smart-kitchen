import { ref, onUnmounted } from 'vue'

export function useWebSocket(url) {
  const ws = ref(null)
  const isConnected = ref(false)
  const lastMessage = ref(null)

  function connect(token) {
    const wsUrl = `${url}?token=${token}`
    ws.value = new WebSocket(wsUrl)

    ws.value.onopen = () => {
      isConnected.value = true
    }

    ws.value.onmessage = (event) => {
      try {
        lastMessage.value = JSON.parse(event.data)
      } catch {
        lastMessage.value = event.data
      }
    }

    ws.value.onclose = () => {
      isConnected.value = false
    }

    ws.value.onerror = (err) => {
      console.error('WebSocket error:', err)
      isConnected.value = false
    }
  }

  function send(data) {
    if (ws.value && isConnected.value) {
      ws.value.send(data)
    }
  }

  function sendText(text) {
    send(text)
  }

  function sendBinary(data) {
    send(data)
  }

  function disconnect() {
    if (ws.value) {
      ws.value.close()
      ws.value = null
      isConnected.value = false
    }
  }

  onUnmounted(() => {
    disconnect()
  })

  return {
    ws,
    isConnected,
    lastMessage,
    connect,
    send,
    sendText,
    sendBinary,
    disconnect,
  }
}
