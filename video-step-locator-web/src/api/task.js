import request from './request'

export const taskAPI = {
  triggerProcess: (videoId) =>
    request.post(`/videos/${videoId}/process`),

  getList: (params) =>
    request.get('/tasks', { params }),

  getDetail: (id) =>
    request.get(`/tasks/${id}`),

  getVideoTasks: (videoId) =>
    request.get(`/videos/${videoId}/tasks`),

  getLogs: (id) =>
    request.get(`/tasks/${id}/logs`),

  start: (id) =>
    request.post(`/tasks/${id}/start`),

  pause: (id) =>
    request.post(`/tasks/${id}/pause`),

  resume: (id) =>
    request.post(`/tasks/${id}/start`),

  retry: (id) =>
    request.post(`/tasks/${id}/retry`),

  cancel: (id) =>
    request.post(`/tasks/${id}/cancel`),

  delete: (id) =>
    request.delete(`/tasks/${id}`),
}
