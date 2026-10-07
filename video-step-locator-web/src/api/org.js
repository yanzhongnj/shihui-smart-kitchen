import request from './request'

// B 端企业接口（方向 B1：企业账号 / 成员协作 / 计费）
export const orgAPI = {
  // 企业
  createOrg: (name) => request.post('/orgs', { name }),
  getMyOrg: () => request.get('/orgs/mine'),
  updateOrg: (orgId, data) => request.put(`/orgs/${orgId}`, data),

  // 成员
  getMembers: (orgId, params = {}) => request.get(`/orgs/${orgId}/members`, { params }),
  addMember: (orgId, data) => request.post(`/orgs/${orgId}/members`, data),
  updateMemberRole: (memberId, role) => request.put(`/orgs/members/${memberId}/role`, { role }),
  removeMember: (memberId) => request.delete(`/orgs/members/${memberId}`),

  // 统计 / 计费 / 订阅
  getOrgStats: (orgId) => request.get(`/orgs/${orgId}/stats`),
  getOrgBilling: (orgId) => request.get(`/orgs/${orgId}/billing`),
  getInvoices: (orgId, params = {}) => request.get(`/orgs/${orgId}/invoices`, { params }),
  subscribe: (orgId, planCode) => request.post(`/orgs/${orgId}/subscribe`, { planCode }),
  getPlans: () => request.get('/orgs/plans'),

  // B2：企业内容工作台
  getOrgRecipes: (orgId, params = {}) => request.get(`/orgs/${orgId}/recipes`, { params }),
  getOrgTasks: (orgId) => request.get(`/orgs/${orgId}/tasks`),
  getOrgReport: (orgId, month) => request.get(`/orgs/${orgId}/reports/monthly`, { params: { month } }),

  // A1：白标品牌配置
  updateBrand: (orgId, data) => request.put(`/orgs/${orgId}/brand`, data),

  // A2：厂商设备（厂商成员查看）
  getVendorActivationStats: (orgId) => request.get(`/orgs/${orgId}/vendor/activation-stats`),
  getVendorBindings: (orgId, params = {}) => request.get(`/orgs/${orgId}/vendor/bindings`, { params }),
  getVendorSkus: (orgId) => request.get(`/orgs/${orgId}/vendor/skus`),
  getVendorLicenses: (orgId) => request.get(`/orgs/${orgId}/vendor/licenses`),

  // A3：智能厨电中控（个人/企业设备）
  getDevices: (params = {}) => request.get('/devices', { params }),
  bindDevice: (data) => request.post('/devices/bind', data),
  unbindDevice: (id) => request.post(`/devices/${id}/unbind`),
  sendDeviceCommand: (id, data) => request.post(`/devices/${id}/command`, data),
  getDeviceCommands: (params = {}) => request.get('/device-commands', { params }),
  retryDeviceCommand: (id) => request.post(`/device-commands/${id}/retry`),
  getRecipeDeviceParams: (recipeId) => request.get(`/recipes/${recipeId}/device-params`),

  // B3：支付（模拟）
  createPayment: (invoiceId) => request.post('/payments', { invoiceId }),
  mockPay: (paymentId) => request.post(`/payments/${paymentId}/pay`),
  getPayments: (orgId, params = {}) => request.get('/payments', { params: { orgId, ...params } }),

  // C：开放平台（开发者）
  createApiClient: (data) => request.post('/open-platform/clients', data),
  getMyApiClients: () => request.get('/open-platform/clients'),
  revokeApiClient: (id) => request.delete(`/open-platform/clients/${id}`),
}
