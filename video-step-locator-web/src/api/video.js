import request from './request'

export const videoAPI = {
  // 分片上传
  initUpload: (data) =>
    request.post('/videos/upload/init', data),

  uploadChunk: (formData, options = {}) =>
    request.post('/videos/upload/chunk', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
      ...options,
    }),

  mergeChunks: (uploadId) =>
    request.post('/videos/upload/merge', { uploadId }),

  getUploadStatus: (fileMd5) =>
    request.get('/videos/upload/status', { params: { fileMd5 } }),

  // 视频 CRUD
  getList: (params) =>
    request.get('/videos', { params }),

  getStats: () =>
    request.get('/videos/stats'),

  getDetail: (id) =>
    request.get(`/videos/${id}`),

  delete: (id) =>
    request.delete(`/videos/${id}`),

  update: (id, data) =>
    request.put(`/videos/${id}`, data),

  // 管理员审查：通过（可共享未公开）/ 驳回（不合格+意见）
  review: (id, approved, comment) =>
    request.post(`/videos/${id}/review-result`, { approved, comment }),
  // 发布到体验 / 下架（属主或管理员）
  publish: (id) => request.post(`/videos/${id}/publish`),
  unpublish: (id) => request.post(`/videos/${id}/unpublish`),

  getStreamUrl: (id) => {
    const token = localStorage.getItem('token')
    return `/api/videos/${id}/stream?token=${encodeURIComponent(token || '')}`
  },
}
