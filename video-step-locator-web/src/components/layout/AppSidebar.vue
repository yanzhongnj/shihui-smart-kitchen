<template>
  <aside class="sidebar" :class="{ collapsed: authStore.sidebarCollapsed }">
    <!-- 折叠态：仅图标栏 -->
    <template v-if="authStore.sidebarCollapsed">
      <nav class="icon-nav">
        <button v-for="item in items" :key="item.to" class="icon-btn"
                :class="{ active: isActive(item.to) }" :title="item.label"
                @click="onIcon(item)">
          <span class="icon">{{ item.icon }}</span>
        </button>
        <template v-if="authStore.isAdmin">
          <div class="nav-sep"></div>
          <button class="icon-btn" :title="'进入管理端'" @click="goAdmin()">
            <span class="icon">🛠️</span>
          </button>
        </template>
      </nav>
      <div class="icon-footer">
        <button class="icon-btn logout" :title="`${authStore.username} · 退出`" @click="authStore.logout()">
          <span class="icon">⎋</span>
        </button>
      </div>
    </template>
    <!-- 展开态：标签侧栏面板 -->
    <template v-else>
      <div class="panel-header">
        <button class="collapse-btn" @click="authStore.closeSidebar()" title="收起为图标栏">◀</button>
        <h2 :title="authStore.appName">🍳 {{ authStore.appName }}</h2>
      </div>
      <nav class="panel-nav">
        <div v-for="(g, gi) in menuGroups" :key="'m' + gi" class="menu-group">
          <button class="group-head" :class="{ open: !isCollapsed('m' + gi) }" @click="toggleGroup('m' + gi)">
            <span class="group-icon">{{ g.icon }}</span>
            <span class="group-title">{{ g.title }}</span>
            <span class="group-arrow">{{ isCollapsed('m' + gi) ? '▸' : '▾' }}</span>
          </button>
          <div v-show="!isCollapsed('m' + gi)" class="group-items">
            <router-link v-for="item in g.items" :key="item.to" :to="item.to"
                         class="nav-item" :class="{ active: isActive(item.to) }">
              <span class="nav-icon">{{ item.icon }}</span>
              <span class="nav-text">{{ item.label }}</span>
            </router-link>
          </div>
        </div>
        <!-- 管理员：进入管理端（双壳切换） -->
        <div v-if="authStore.isAdmin" class="nav-section" style="margin-top: 8px">
          <button class="nav-item user-switch" style="background:none;border:none;width:100%;text-align:left;cursor:pointer" @click="goAdmin()">
            <span class="nav-icon">🛠️</span>
            <span class="nav-text">进入管理端</span>
          </button>
        </div>
      </nav>
      <div class="panel-footer">
        <span class="user-name">{{ authStore.username }}</span>
        <button class="logout-btn" @click="authStore.logout()">退出</button>
      </div>
    </template>
  </aside>
</template>

<script setup>
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

// 用户端菜单（按业务域分组，路径统一 /app 前缀）
const items = [
  { to: '/app', label: '仪表盘', icon: '📊' },
  { to: '/app/videos', label: '我的视频', icon: '🎬' },
  { to: '/app/videos/download', label: '链接下载', icon: '🔗' },
  { to: '/app/downloads', label: '下载历史', icon: '📥' },
  { to: '/app/tasks', label: '智能处理任务', icon: '📋' },
  { to: '/app/recipes', label: '菜谱库', icon: '🍲' },
  { to: '/app/org', label: '我的企业', icon: '🏢' },
  { to: '/app/dev', label: '开发者中心', icon: '🔑' },
  { to: '/app/devices', label: '设备控制', icon: '🔥' },
]

const menuGroups = [
  { title: '工作台', icon: '🏠', items: [items[0]] },
  { title: '视频管理', icon: '🎬', items: [items[1], items[2], items[3], items[4]] },
  { title: '菜谱', icon: '🍲', items: [items[5]] },
  { title: '其他', icon: '📦', items: [items[6], items[7], items[8]] },
]

// 分组折叠状态（默认全部展开，localStorage 记忆）
const collapsedGroups = ref(loadCollapsed())
function loadCollapsed() {
  try {
    const s = localStorage.getItem('sidebarCollapsedGroups')
    return s ? new Set(JSON.parse(s)) : new Set()
  } catch (e) { return new Set() }
}
function persist(s) {
  try { localStorage.setItem('sidebarCollapsedGroups', JSON.stringify([...s])) } catch (e) {}
}
function isCollapsed(key) {
  return collapsedGroups.value.has(key)
}
function toggleGroup(key) {
  const s = new Set(collapsedGroups.value)
  if (s.has(key)) s.delete(key); else s.add(key)
  collapsedGroups.value = s
  persist(s)
}

function isActive(to) {
  if (route.path === to) return true
  if (!route.path.startsWith(to + '/')) return false
  // 子路径匹配时，若当前路径命中另一个更具体的菜单项（如 /app/videos/download 是「链接下载」），
  // 则不点亮父项，避免「链接下载」时「我的视频」同时高亮
  const all = items.map(i => i.to)
  const hitMoreSpecific = all.some(t => t !== to && (route.path === t || route.path.startsWith(t + '/')))
  return !hitMoreSpecific
}

// 折叠态点图标：展开面板并跳转
function onIcon(item) {
  authStore.openSidebar()
  if (route.path !== item.to) router.push(item.to)
}

// 管理员：跳转管理端
function goAdmin() {
  authStore.openSidebar()
  router.push('/admin/dashboard')
}
</script>

<style scoped src="./sidebar.css"></style>
<style scoped>
/* 用户端特有覆盖（如需要） */
</style>
