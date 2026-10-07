import request from './request'

export const voiceAPI = {
  sendCommand: (text) =>
    request.post('/voice/command', { text }),
}
