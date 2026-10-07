import request from './request'

export const shareAPI = {
  share: (videoId, data) =>
    request.post(`/videos/${videoId}/share`, data),

  getShares: (videoId) =>
    request.get(`/videos/${videoId}/shares`),

  removeShare: (videoId, userId) =>
    request.delete(`/videos/${videoId}/share/${userId}`),

  getSharedWithMe: () =>
    request.get('/videos/shared-with-me'),
}
