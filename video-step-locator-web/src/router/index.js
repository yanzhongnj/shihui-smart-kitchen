import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/LoginView.vue'),
    meta: { requiresAuth: false },
  },
  {
    path: '/register',
    name: 'Register',
    component: () => import('@/views/RegisterView.vue'),
    meta: { requiresAuth: false },
  },
  // 根路径：按角色落地（ADMIN→/admin，其他→/app）
  {
    path: '/',
    name: 'HomeRedirect',
    component: () => import('@/views/HomeRedirect.vue'),
  },
  // ===== 用户壳 /app（登录可见） =====
  {
    path: '/app',
    component: () => import('@/components/layout/AppLayout.vue'),
    meta: { requiresAuth: true },
    children: [
      { path: '', name: 'Dashboard', component: () => import('@/views/DashboardView.vue') },
      { path: 'videos', name: 'VideoList', component: () => import('@/views/VideoListView.vue') },
      { path: 'videos/upload', name: 'VideoUpload', component: () => import('@/views/VideoUploadView.vue') },
      { path: 'videos/download', name: 'VideoDownload', component: () => import('@/views/VideoDownloadView.vue') },
      { path: 'downloads', name: 'DownloadHistory', component: () => import('@/views/DownloadTaskListView.vue') },
      // 共享视图：管理端「查看」复用
      { path: 'videos/:id', name: 'VideoDetail', component: () => import('@/views/VideoDetailView.vue') },
      { path: 'tasks', name: 'TaskList', component: () => import('@/views/TaskListView.vue') },
      // B 端：企业管理
      { path: 'org', name: 'OrgManage', component: () => import('@/views/org/OrgManageView.vue') },
      // A3：智能厨电中控
      { path: 'devices', name: 'DeviceManage', component: () => import('@/views/device/DeviceManageView.vue') },
      // C：开放平台开发者中心
      { path: 'dev', name: 'ApiCenter', component: () => import('@/views/dev/ApiCenterView.vue') },
      // 菜谱
      { path: 'recipes', name: 'RecipeList', component: () => import('@/views/RecipeListView.vue') },
      { path: 'recipes/new', name: 'RecipeCreate', component: () => import('@/views/RecipeCreateView.vue') },
      { path: 'recipes/:id/edit', name: 'RecipeEdit', component: () => import('@/views/RecipeCreateView.vue') },
      { path: 'recipes/:id', name: 'RecipeDetail', component: () => import('@/views/RecipeDetailView.vue') },
    ],
  },
  // ===== 管理壳 /admin（仅 ADMIN） =====
  {
    path: '/admin',
    component: () => import('@/components/layout/AdminLayout.vue'),
    meta: { requiresAuth: true, requiresAdmin: true },
    children: [
      { path: '', redirect: 'dashboard' },
      { path: 'dashboard', name: 'AdminDashboard', component: () => import('@/views/admin/AdminDashboardView.vue') },
      // 用户管理（个人用户详情 M2 补全）
      { path: 'users', name: 'AdminUsers', component: () => import('@/views/admin/AdminUserManageView.vue') },
      { path: 'users/:id', name: 'AdminUserDetail', component: () => import('@/views/admin/AdminUserDetailView.vue') },
      // 视频管理
      { path: 'videos', name: 'AdminVideos', component: () => import('@/views/admin/AdminGlobalVideoView.vue') },
      { path: 'downloads', name: 'AdminDownloads', component: () => import('@/views/admin/AdminDownloadTaskView.vue') },
      // 菜谱管理
      { path: 'content', name: 'AdminContent', component: () => import('@/views/admin/AdminContentView.vue') },
      { path: 'recipe-precision', name: 'AdminRecipePrecision', component: () => import('@/views/admin/AdminRecipePrecisionView.vue') },
      { path: 'content-source', name: 'AdminContentSource', component: () => import('@/views/admin/AdminContentSourceView.vue') },
      { path: 'food-library', name: 'AdminFoodLibrary', component: () => import('@/views/admin/AdminFoodLibraryView.vue') },
      // 寻味管理（M3）
      { path: 'xunwei/sessions', name: 'AdminXunweiSessions', component: () => import('@/views/admin/AdminXunweiSessionsView.vue') },
      { path: 'xunwei/memory', name: 'AdminXunweiMemory', component: () => import('@/views/admin/AdminXunweiMemoryView.vue') },
      // 酿味管理（M3）
      { path: 'creation/sessions', name: 'AdminCreationSessions', component: () => import('@/views/admin/AdminCreationSessionsView.vue') },
      { path: 'creation/creations', name: 'AdminCreations', component: () => import('@/views/admin/AdminCreationsView.vue') },
      // 计费与用量（M3）
      { path: 'usage', name: 'AdminUsage', component: () => import('@/views/admin/AdminUsageView.vue') },
      { path: 'billing', name: 'AdminBilling', component: () => import('@/views/admin/AdminBillingView.vue') },
      // 数据画像（用户偏好等）
      { path: 'profile', name: 'AdminProfile', component: () => import('@/views/admin/AdminProfileView.vue') },
      // 回收站（软删除记录治理：恢复 / 归档并彻底删除）
      { path: 'recycle', name: 'AdminRecycle', component: () => import('@/views/admin/AdminRecycleView.vue') },
      { path: 'photo-checkins', name: 'AdminPhotoCheckins', component: () => import('@/views/admin/AdminPhotoCheckinView.vue') },
      { path: 'dish-nutrition', name: 'AdminDishNutrition', component: () => import('@/views/admin/AdminDishNutritionView.vue') },
      { path: 'safety', name: 'AdminSafety', component: () => import('@/views/admin/AdminSafetyView.vue') },
      // 平台运维
      { path: 'monitor', name: 'AdminMonitor', component: () => import('@/views/admin/AdminSystemMonitorView.vue') },
      { path: 'ai-config', name: 'AdminAiConfig', component: () => import('@/views/admin/AdminAiConfigView.vue') },
      { path: 'orgs', name: 'AdminOrgs', component: () => import('@/views/admin/AdminOrgView.vue') },
      { path: 'vendors', name: 'AdminVendors', component: () => import('@/views/admin/AdminVendorView.vue') },
      { path: 'app-versions', name: 'AdminAppVersions', component: () => import('@/views/admin/AdminAppVersionView.vue') },
    ],
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach((to, from, next) => {
  const authStore = useAuthStore()
  if (to.meta.requiresAuth && !authStore.isLoggedIn) {
    next({ name: 'Login', query: { redirect: to.fullPath } })
  } else if (to.meta.requiresAdmin && !authStore.isAdmin) {
    // 非管理员访问管理端 → 回到角色主页（用户端 /app）
    next(authStore.homePath)
  } else if (!to.meta.requiresAuth && authStore.isLoggedIn && to.name === 'Login') {
    next(authStore.homePath)
  } else {
    next()
  }
})

export default router
