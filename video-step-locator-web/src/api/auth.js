import request from './request'

export const authAPI = {
  login: (username, password) =>
    request.post('/auth/login', { username, password }),

  register: (data) =>
    request.post('/auth/register', data),

  refresh: () =>
    request.post('/auth/refresh'),

  getProfile: () =>
    request.get('/auth/profile'),

  updateProfile: (data) =>
    request.put('/auth/profile', data),
}
