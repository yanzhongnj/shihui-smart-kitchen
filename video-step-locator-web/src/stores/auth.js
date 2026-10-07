import { defineStore } from 'pinia'
import { authAPI } from '@/api/auth'
import router from '@/router'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: null,
    user: null,
    brand: null, // A1 白标：所属企业品牌配置 {appName,logoUrl,splashText,...}
    sidebarCollapsed: localStorage.getItem('sidebarCollapsed') === 'true',
  }),

  getters: {
    // 双壳分离：支持 localStorage 兜底，路由守卫在 restoreSession 前也能正确判断
    isLoggedIn: (state) => !!state.token || !!localStorage.getItem('token'),
    isAdmin: (state) => {
      if (state.user?.role === 'ADMIN') return true
      try {
        return JSON.parse(localStorage.getItem('user') || 'null')?.role === 'ADMIN'
      } catch (e) { return false }
    },
    // 角色主页：ADMIN→管理端，其他→用户端（登录落地/401/登出/HomeRedirect 统一使用）
    homePath: (state) => {
      const admin = state.user?.role === 'ADMIN'
        || (() => { try { return JSON.parse(localStorage.getItem('user') || 'null')?.role === 'ADMIN' } catch (e) { return false } })()
      return admin ? '/admin/dashboard' : '/app'
    },
    username: (state) => state.user?.nickname || state.user?.username || '',
    appName: (state) => state.brand?.appName
      ? `${state.brand.appName} 管理平台`
      : '拾味管理平台',
    sidebarWidth: (state) => state.sidebarCollapsed ? '60px' : '220px',
  },

  actions: {
    async login(username, password) {
      const res = await authAPI.login(username, password)
      this.token = res.data.token
      this.user = res.data.user
      localStorage.setItem('token', res.data.token)
      localStorage.setItem('user', JSON.stringify(res.data.user))
      await this.loadBrand()
      return res
    },

    async register(data) {
      const res = await authAPI.register(data)
      this.token = res.data.token
      this.user = res.data.user
      localStorage.setItem('token', res.data.token)
      localStorage.setItem('user', JSON.stringify(res.data.user))
      await this.loadBrand()
      return res
    },

    async loadBrand() {
      // 登录用户拉取所属企业品牌（A1 白标）；无企业则回退默认「拾味管理平台」
      try {
        const { orgAPI } = await import('@/api/org')
        const res = await orgAPI.getMyOrg()
        const org = res.data
        if (org && org.id) {
          const request = (await import('@/api/request')).default
          const b = await request.get(`/brand/${org.id}`)
          this.brand = b.data || null
        } else {
          this.brand = null
        }
      } catch (e) {
        this.brand = null
      }
    },

    restoreSession() {
      const token = localStorage.getItem('token')
      const userStr = localStorage.getItem('user')
      if (token && userStr) {
        try {
          this.token = token
          this.user = JSON.parse(userStr)
          this.loadBrand()
        } catch (e) {
          localStorage.removeItem('token')
          localStorage.removeItem('user')
        }
      }
    },

    logout() {
      this.token = null
      this.user = null
      localStorage.removeItem('token')
      localStorage.removeItem('user')
      router.push('/login')
    },

    toggleSidebar() {
      this.sidebarCollapsed = !this.sidebarCollapsed
      localStorage.setItem('sidebarCollapsed', this.sidebarCollapsed)
    },
    openSidebar() {
      this.sidebarCollapsed = false
      localStorage.setItem('sidebarCollapsed', 'false')
    },
    closeSidebar() {
      this.sidebarCollapsed = true
      localStorage.setItem('sidebarCollapsed', 'true')
    },
  },
})
