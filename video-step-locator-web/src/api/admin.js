import request from './request'

// 平台管理接口（仅 ADMIN 角色；后端 /api/admin/** 强制校验角色）
export const adminAPI = {
  // 用户管理
  getUsers: (params = {}) =>
    request.get('/admin/users', { params }),
  setUserStatus: (id, status) =>
    request.put(`/admin/users/${id}/status`, { status }),
  setUserRole: (id, role) =>
    request.put(`/admin/users/${id}/role`, { role }),
  createUser: (data) =>
    request.post('/admin/users', data),
  updateUser: (id, data) =>
    request.put(`/admin/users/${id}`, data),

  // 全局视频
  getVideos: (params = {}) =>
    request.get('/admin/videos', { params }),

  // 全局下载任务
  getDownloads: (params = {}) =>
    request.get('/admin/downloads', { params }),

  // 平台统计 / 系统健康
  getStats: () =>
    request.get('/admin/stats'),
  getHealth: () =>
    request.get('/admin/health'),

  // AI 模型配置中心（v0.3）：引擎配置 / 测试连通 / 恢复默认 / 变更日志
  aiConfig: {
    list: () => request.get('/admin/ai-config/list'),
    detail: (key) => request.get(`/admin/ai-config/${key}`),
    update: (key, data) => request.put(`/admin/ai-config/${key}`, data),
    test: (key) => request.post(`/admin/ai-config/${key}/test`),
    restoreDefault: (key) => request.post(`/admin/ai-config/${key}/restore-default`),
    enable: (key) => request.post(`/admin/ai-config/${key}/enable`),
    disable: (key) => request.post(`/admin/ai-config/${key}/disable`),
    health: () => request.get('/admin/ai-config/health'),
    changeLogs: (params = {}) => request.get('/admin/ai-config/change-logs', { params }),
  },

  // APP 版本发布管理
  getAppVersions: (params = {}) =>
    request.get('/admin/app-versions', { params }),
  createAppVersion: (data) =>
    request.post('/admin/app-versions', data),
  updateAppVersion: (id, data) =>
    request.put(`/admin/app-versions/${id}`, data),
  deleteAppVersion: (id) =>
    request.delete(`/admin/app-versions/${id}`),
  // 上传 APK 安装包（multipart）；安装包可达 138MB+，放宽超时
  uploadAppApk: (id, file) => {
    const fd = new FormData()
    fd.append('file', file)
    return request.post(`/admin/app-versions/${id}/apk`, fd, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 300000,
    })
  },

  // 内容管理
  getShareLinks: (params = {}) =>
    request.get('/admin/share-links', { params }),
  deleteShareLink: (id) =>
    request.delete(`/admin/share-links/${id}`),
  getReviews: (params = {}) =>
    request.get('/admin/reviews', { params }),
  deleteReview: (id) =>
    request.delete(`/admin/reviews/${id}`),
  getRecipes: (params = {}) =>
    request.get('/admin/recipes', { params }),

  // 数据画像管理
  getMemories: (params = {}) =>
    request.get('/admin/memories', { params }),
  deleteMemory: (id) =>
    request.delete(`/admin/memories/${id}`),
  getPreferences: (params = {}) =>
    request.get('/admin/preferences', { params }),
  getUsage: (params = {}) =>
    request.get('/admin/usage', { params }),
  getUsageSummary: () =>
    request.get('/admin/usage/summary'),

  // M2：个人用户管理（用户详情各 Tab）
  getUserDetail: (id) =>
    request.get(`/admin/users/${id}`),
  getUserCheckins: (id, params = {}) =>
    request.get(`/admin/users/${id}/checkins`, { params }),
  deleteCheckin: (id) =>
    request.delete(`/admin/checkins/${id}`),
  getUserHealthProfile: (id) =>
    request.get(`/admin/users/${id}/health-profile`),
  getUserDiet: (id, params = {}) =>
    request.get(`/admin/users/${id}/diet`, { params }),
  deleteDiet: (id) =>
    request.delete(`/admin/diet/${id}`),
  getUserWeightLogs: (id, params = {}) =>
    request.get(`/admin/users/${id}/weight-logs`, { params }),
  getUserPlans: (id) =>
    request.get(`/admin/users/${id}/plans`),
  deletePlan: (id) =>
    request.delete(`/admin/plans/${id}`),
  getUserFavorites: (id) =>
    request.get(`/admin/users/${id}/favorites`),
  getUserPreferences: (id) =>
    request.get(`/admin/users/${id}/preferences`),
  updateUserPreferences: (id, data) =>
    request.put(`/admin/users/${id}/preferences`, data),
  getUserWorks: (id) =>
    request.get(`/admin/users/${id}/works`),
  deleteWork: (id) =>
    request.delete(`/admin/works/${id}`),
  getUserGrowth: (id) =>
    request.get(`/admin/users/${id}/growth`),

  // 全局回收站（软删除记录治理，仅 ADMIN；type ∈ checkins/diet/plans/works/memories）
  getRecycleList: (type, params = {}) =>
    request.get(`/admin/recycle/${type}`, { params }),
  getRecycleStats: () =>
    request.get('/admin/recycle/stats'),
  restoreRecycle: (type, id) =>
    request.post(`/admin/recycle/${type}/${id}/restore`),
  purgeRecycle: (type, id) =>
    request.post(`/admin/recycle/${type}/${id}/purge`),
  // P2：批量 + 审计
  batchRestoreRecycle: (type, ids) =>
    request.post(`/admin/recycle/${type}/batch-restore`, { ids }),
  batchPurgeRecycle: (type, ids) =>
    request.post(`/admin/recycle/${type}/batch-purge`, { ids }),
  getRecycleAudit: (params = {}) =>
    request.get('/admin/recycle/audit', { params }),
  // 超期自动清理：配置 / 启停 / 手动触发
  getRecycleSettings: () =>
    request.get('/admin/recycle/settings'),
  updateRecycleSettings: (data) =>
    request.put('/admin/recycle/settings', data),
  enableRecyclePurge: () =>
    request.post('/admin/recycle/enable'),
  disableRecyclePurge: () =>
    request.post('/admin/recycle/disable'),
  runRecyclePurge: () =>
    request.post('/admin/recycle/run'),

  // 拍照打卡管理（P0，设计文档《拾味_拍照打卡_技术设计文档.md》v0.2 §8）
  getPhotoCheckins: (params = {}) =>
    request.get('/admin/photo-checkins', { params }),
  getPhotoCheckin: (id) =>
    request.get(`/admin/photo-checkins/${id}`),
  getPhotoCheckinStats: () =>
    request.get('/admin/photo-checkins/stats'),
  deletePhotoCheckin: (id) =>
    request.delete(`/admin/photo-checkins/${id}`),

  // 菜品级营养库（拍照打卡 P1：种子 + AI 候选审核）
  getDishNutrition: (params = {}) =>
    request.get('/admin/dish-nutrition', { params }),
  getDishNutritionStats: () =>
    request.get('/admin/dish-nutrition/stats'),
  updateDishNutrition: (id, data) =>
    request.put(`/admin/dish-nutrition/${id}`, data),
  verifyDishNutrition: (id) =>
    request.post(`/admin/dish-nutrition/${id}/verify`),
  deleteDishNutrition: (id) =>
    request.delete(`/admin/dish-nutrition/${id}`),

  // 食物安全运营（D5 采集管道 + 反馈闭环）
  getSafetyCandidates: (params = {}) =>
    request.get('/admin/safety/candidates', { params }),
  approveSafetyCandidate: (id) =>
    request.post(`/admin/safety/candidates/${id}/approve`),
  rejectSafetyCandidate: (id) =>
    request.post(`/admin/safety/candidates/${id}/reject`),
  getSafetyRules: (params = {}) =>
    request.get('/safety/rules', { params }),
  getSafetyFeedback: (params = {}) =>
    request.get('/admin/safety/feedback', { params }),
  getSafetyFeedbackSummary: () =>
    request.get('/admin/safety/feedback/summary'),

  // M3：寻味管理（会话）
  getXunweiSessions: (params = {}) =>
    request.get('/admin/xunwei/sessions', { params }),
  getXunweiSessionMessages: (id) =>
    request.get(`/admin/xunwei/sessions/${id}/messages`),
  deleteXunweiSession: (id) =>
    request.delete(`/admin/xunwei/sessions/${id}`),

  // M3：酿味管理（会话/创作）
  getCreationSessions: (params = {}) =>
    request.get('/admin/creation/sessions', { params }),
  getCreationSessionMessages: (id) =>
    request.get(`/admin/creation/sessions/${id}/messages`),
  deleteCreationSession: (id) =>
    request.delete(`/admin/creation/sessions/${id}`),
  getCreations: (params = {}) =>
    request.get('/admin/creation/creations', { params }),
  getCreationDetail: (id) =>
    request.get(`/admin/creation/creations/${id}`),
  deleteCreation: (id) =>
    request.delete(`/admin/creation/creations/${id}`),

  // M3：计费与用量（分项）
  getBillingGlobal: () =>
    request.get('/admin/billing/global'),
  getBillingUser: (id) =>
    request.get(`/admin/billing/users/${id}`),

  // 企业管理（B 端底座 P0）
  getOrgs: (params = {}) =>
    request.get('/admin/orgs', { params }),
  getOrgStats: (orgId) =>
    request.get(`/admin/orgs/${orgId}/stats`),
  setOrgStatus: (orgId, status) =>
    request.put(`/admin/orgs/${orgId}/status`, { status }),
  setOrgPlan: (orgId, planCode) =>
    request.put(`/admin/orgs/${orgId}/plan`, { planCode }),
  getOrgPlans: () =>
    request.get('/admin/org-plans'),

  // B3：账单管理
  getInvoices: (params = {}) =>
    request.get('/admin/invoices', { params }),
  createInvoice: (orgId, data) =>
    request.post(`/admin/orgs/${orgId}/invoices`, data),
  setInvoiceStatus: (invoiceId, status) =>
    request.put(`/admin/invoices/${invoiceId}/status`, { status }),

  // A1：品牌配置
  setOrgBrand: (orgId, data) =>
    request.put(`/admin/orgs/${orgId}/brand`, data),

  // A2：厂商管理
  // A2：厂商管理
  getVendors: () =>
    request.get('/admin/vendors'),
  getVendorContracts: (params = {}) =>
    request.get('/admin/vendors/contracts', { params }),
  createVendorContract: (data) =>
    request.post('/admin/vendors/contracts', data),
  setVendorContractStatus: (id, status) =>
    request.put(`/admin/vendors/contracts/${id}/status`, { status }),
  deleteVendorContract: (id) =>
    request.delete(`/admin/vendors/contracts/${id}`),
  getVendorSkus: (params = {}) =>
    request.get('/admin/vendors/skus', { params }),
  createVendorSku: (data) =>
    request.post('/admin/vendors/skus', data),
  deleteVendorSku: (id) =>
    request.delete(`/admin/vendors/skus/${id}`),
  generateBindCodes: (skuId, count) =>
    request.post(`/admin/vendors/skus/${skuId}/bind-codes`, { count }),
  getVendorBindings: (params = {}) =>
    request.get('/admin/vendors/bindings', { params }),
  deleteVendorBinding: (id) =>
    request.delete(`/admin/vendors/bindings/${id}`),
  getVendorActivationStats: (params = {}) =>
    request.get('/admin/vendors/activation-stats', { params }),
  getVendorLicenses: (params = {}) =>
    request.get('/admin/vendors/licenses', { params }),
  addVendorLicense: (data) =>
    request.post('/admin/vendors/licenses', data),
  setVendorLicenseStatus: (id, status) =>
    request.put(`/admin/vendors/licenses/${id}/status`, { status }),
  deleteVendorLicense: (id) =>
    request.delete(`/admin/vendors/licenses/${id}`),
}

// ===== 内容源与反查管理（P0；/api/content-sync/** 与 /api/ingredients/**） =====
export const contentSourceAPI = {
  // 内容源状态 / 来源（登录可读）
  getStatus: () => request.get('/content-sync/status'),
  getSources: () => request.get('/content-sync/sources'),
  // 手动触发 HowToCook 同步（ADMIN）
  runSync: (source = 'howtocook') => request.post('/content-sync/run', null, { params: { source } }),
  // 工业型批量升级（ADMIN）：?limit=
  upgradeIndustrial: (limit, source) => request.post('/content-sync/upgrade-industrial', null, { params: { limit, source } }),
  // 营养回填（ADMIN）：收集 → dryRun 预览 → 确认入库
  collectNutrition: () => request.post('/content-sync/nutrition/collect'),
  backfillNutrition: (dryRun, max) => request.post('/content-sync/nutrition/backfill', null, { params: { dryRun, max } }),
  // 食材标签索引重建（ADMIN）
  rebuildTags: () => request.post('/ingredients/tags/rebuild'),
  // 同义词管理（ADMIN）
  getAliases: () => request.get('/ingredients/aliases'),
  createAlias: (data) => request.post('/ingredients/aliases', data),
  deleteAlias: (id) => request.delete(`/ingredients/aliases/${id}`),
}

// ===== 食物营养库治理（P1；/api/health/foods/**） =====
export const foodAPI = {
  searchFoods: (params = {}) => request.get('/health/foods/search', { params }),
  getCategories: () => request.get('/health/foods/categories'),
  updateFood: (id, data) => request.put(`/health/foods/${id}`, data),
  deleteFood: (id) => request.delete(`/health/foods/${id}`),
  confirmAiFoods: (foods) => request.post('/health/foods/confirm-ai', { foods }),
  getCalorieLevels: () => request.get('/health/calorie-levels'),
}
