import { ref } from 'vue'
import videojs from 'video.js'

export function useVideoPlayer() {
  const player = ref(null)
  const currentTime = ref(0)
  const duration = ref(0)
  let animationId = null

  function initPlayer(elementId, options = {}) {
    player.value = videojs(elementId, {
      controls: true,
      fluid: true,
      playbackRates: [0.5, 0.75, 1, 1.25, 1.5, 2],
      html5: {
        hls: { overrideNative: true },
        nativeAudioTracks: false,
        nativeVideoTracks: false,
      },
      ...options,
    })

    player.value.ready(() => {
      player.value.on('loadedmetadata', () => {
        duration.value = player.value.duration()
        startTimeUpdate()
      })
    })
  }

  function startTimeUpdate() {
    if (animationId) cancelAnimationFrame(animationId)

    function update() {
      if (player.value) {
        currentTime.value = player.value.currentTime()
      }
      animationId = requestAnimationFrame(update)
    }
    update()
  }

  function seekTo(time) {
    if (!player.value) return
    const safeTime = Math.max(0, Math.min(time, duration.value - 0.1))
    player.value.currentTime(safeTime)
    const promise = player.value.play()
    if (promise && promise.catch) {
      promise.catch(() => {})
    }
  }

  function play() {
    if (player.value) player.value.play()
  }

  function pause() {
    if (player.value) player.value.pause()
  }

  function setSource(src, type = 'video/mp4') {
    if (player.value) {
      player.value.src({ type, src })
      player.value.load()
    }
  }

  function destroy() {
    if (animationId) cancelAnimationFrame(animationId)
    if (player.value) {
      player.value.dispose()
      player.value = null
    }
  }

  return {
    player,
    currentTime,
    duration,
    initPlayer,
    seekTo,
    play,
    pause,
    setSource,
    destroy,
  }
}
