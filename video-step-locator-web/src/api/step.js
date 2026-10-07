import request from './request'

export const stepAPI = {
  getSteps: (videoId) =>
    request.get(`/videos/${videoId}/steps`),

  updateStep: (stepId, data) =>
    request.put(`/steps/${stepId}`, data),

  toggleLoop: (stepId) =>
    request.post(`/steps/${stepId}/loop`),
}
