import request from './request'

export const downloadAPI = {
  // 解析链接 → 条目列表
  parse: (url) =>
    request.post('/download/parse', { url }),

  // 批量创建下载任务（format: best|1080p|720p|480p）
  start: (pageUrl, entries, format) =>
    request.post('/download/start', { pageUrl, entries, format }),

  // 任务查询
  list: (params = {}) =>
    request.get('/download/tasks', { params }),

  get: (id) =>
    request.get(`/download/tasks/${id}`),

  // 重试 / 取消
  retry: (id) =>
    request.post(`/download/tasks/${id}/retry`),

  cancel: (id) =>
    request.post(`/download/tasks/${id}/cancel`),

  // 下载进度 WebSocket（Vite 代理 /ws → 8080）
  wsUrl: (taskId) => {
    const token = localStorage.getItem('token')
    return `/ws/download/${taskId}?token=${encodeURIComponent(token || '')}`
  },
}
