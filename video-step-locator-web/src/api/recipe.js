import request from './request'

// 菜谱助手 API（游客可只读；录入/解析/删除需登录）
export const recipeAPI = {
  // 菜谱列表（可选按菜系/口味筛选）
  getList: (params = {}) =>
    request.get('/recipes', { params }),

  // 菜谱详情
  getDetail: (id) =>
    request.get(`/recipes/${id}`),

  // 口味/忌口兼容性（需登录）
  getCompatibility: (id) =>
    request.get(`/recipes/${id}/compatibility`),

  // 图文链接解析 → 菜谱草稿（需登录；draft 可回填录入表单）
  parseArticle: (url) =>
    request.post('/recipes/parse-article', { url }),

  // 保存菜谱（需登录；industrial=true 走工业型强校验；source=ARTICLE_LINK 保存图文草稿）
  createRecipe: (body) =>
    request.post('/recipes', body),

  // 修改菜谱（创建者或管理员）
  updateRecipe: (id, body) =>
    request.put(`/recipes/${id}`, body),

  // 删除菜谱（创建者或管理员）
  deleteRecipe: (id) =>
    request.delete(`/recipes/${id}`),

  // 溯源详情（来源 + 历史流水）
  getProvenance: (id) =>
    request.get(`/recipes/${id}/provenance`),

  // 精确化评分分布（游客可读，阈值校准参考）
  getPrecisionStats: () =>
    request.get('/recipes/precision-stats'),

  // 精确化增强（需登录 + 计费；LLM 精化后重算评分/类型）
  upgradeRecipe: (id) =>
    request.post(`/recipes/${id}/upgrade`),

  // 分量换算（按人数 servings / 按模具 mode=mold + shape + size1 + size2）
  getScale: (id, params = {}) =>
    request.get(`/recipes/${id}/scale`, { params }),
}
