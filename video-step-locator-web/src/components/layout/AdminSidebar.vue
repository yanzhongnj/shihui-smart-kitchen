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
        <div class="nav-sep"></div>
        <button class="icon-btn" :title="'返回用户端'" @click="goUser()">
          <span class="icon">👤</span>
        </button>
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
        <h2 :title="authStore.appName">🛠️ {{ authStore.appName }}</h2>
      </div>
      <nav class="panel-nav">
        <div class="nav-section">管理端</div>
        <div v-for="(g, gi) in menuGroups" :key="'am' + gi" class="menu-group">
          <button class="group-head" :class="{ open: !isCollapsed('am' + gi) }" @click="toggleGroup('am' + gi)">
            <span class="group-icon">{{ g.icon }}</span>
            <span class="group-title">{{ g.title }}</span>
            <span class="group-arrow">{{ isCollapsed('am' + gi) ? '▸' : '▾' }}</span>
          </button>
          <div v-show="!isCollapsed('am' + gi)" class="group-items">
            <router-link v-for="item in g.items" :key="item.to" :to="item.to"
                         class="nav-item" :class="{ active: isActive(item.to) }">
              <span class="nav-icon">{{ item.icon }}</span>
              <span class="nav-text">{{ item.label }}</span>
            </router-link>
          </div>
        </div>
        <div class="nav-section" style="margin-top: 8px">
          <button class="nav-item user-switch" style="background:none;border:none;width:100%;text-align:left;cursor:pointer" @click="goUser()">
            <span class="nav-icon">👤</span>
            <span class="nav-text">返回用户端</span>
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

// 管理端菜单（按业务域分组；M1 基础 + M2 用户详情 + M3 寻味/酿味/计费）
const items = [
  { to: '/admin/dashboard', label: '管理仪表盘', icon: '📊' },
  { to: '/admin/users', label: '用户列表', icon: '👥' },
  { to: '/admin/videos', label: '全局视频', icon: '🎞️' },
  { to: '/admin/downloads', label: '下载任务', icon: '⬇️' },
  { to: '/admin/content', label: '内容管理', icon: '📚' },
  { to: '/admin/recipe-precision', label: '菜谱精确化', icon: '🎯' },
  { to: '/admin/content-source', label: '内容源与反查', icon: '📡' },
  { to: '/admin/food-library', label: '食物营养库', icon: '🥬' },
  { to: '/admin/xunwei/sessions', label: '寻味会话', icon: '💬' },
  { to: '/admin/xunwei/memory', label: '寻味记忆', icon: '🧠' },
  { to: '/admin/creation/sessions', label: '酿味会话', icon: '🍳' },
  { to: '/admin/creation/creations', label: '酿味创作', icon: '✨' },
  { to: '/admin/usage', label: '用量记录', icon: '📈' },
  { to: '/admin/billing', label: '计费分项', icon: '💰' },
  { to: '/admin/profile', label: '数据画像(偏好)', icon: '🧬' },
  { to: '/admin/recycle', label: '回收站', icon: '🗑️' },
  { to: '/admin/photo-checkins', label: '拍照打卡', icon: '📷' },
  { to: '/admin/safety', label: '安全规则', icon: '🛡️' },
  { to: '/admin/monitor', label: '系统监控', icon: '🖥️' },
  { to: '/admin/ai-config', label: 'AI 引擎配置', icon: '⚙️' },
  { to: '/admin/orgs', label: '企业管理', icon: '🏢' },
  { to: '/admin/vendors', label: '厂商管理', icon: '🏭' },
  { to: '/admin/app-versions', label: 'APP 版本', icon: '📱' },
  { to: '/admin/dish-nutrition', label: '菜品营养库', icon: '🥘' },
]

const menuGroups = [
  { title: '工作台', icon: '🏠', items: [items[0]] },
  { title: '用户管理', icon: '👥', items: [items[1]] },
  { title: '视频管理', icon: '🎬', items: [items[2], items[3]] },
  { title: '菜谱管理', icon: '🍲', items: [items[4], items[5], items[6], items[7]] },
  { title: '寻味管理', icon: '🧭', items: [items[8], items[9]] },
  { title: '酿味管理', icon: '🍳', items: [items[10], items[11]] },
  { title: '计费与用量', icon: '💰', items: [items[12], items[13]] },
  { title: '数据画像', icon: '🧬', items: [items[14]] },
  { title: '饮食健康', icon: '📷', items: [items[16], items[22]] },
  { title: '数据治理', icon: '🗑️', items: [items[15], items[21]] },
  { title: '平台运维', icon: '🛠️', items: [items[17], items[18], items[19], items[20], items[23]] },
]

// 分组折叠状态（localStorage 记忆，与用户端用不同 key 前缀避免串扰）
const collapsedGroups = ref(loadCollapsed())
function loadCollapsed() {
  try {
    const s = localStorage.getItem('adminSidebarCollapsedGroups')
    return s ? new Set(JSON.parse(s)) : new Set()
  } catch (e) { return new Set() }
}
function persist(s) {
  try { localStorage.setItem('adminSidebarCollapsedGroups', JSON.stringify([...s])) } catch (e) {}
}
function isCollapsed(key) { return collapsedGroups.value.has(key) }
function toggleGroup(key) {
  const s = new Set(collapsedGroups.value)
  if (s.has(key)) s.delete(key); else s.add(key)
  collapsedGroups.value = s
  persist(s)
}

function isActive(to) {
  if (route.path === to) return true
  if (!route.path.startsWith(to + '/')) return false
  const all = items.map(i => i.to)
  return !all.some(t => t !== to && (route.path === t || route.path.startsWith(t + '/')))
}

function onIcon(item) {
  authStore.openSidebar()
  if (route.path !== item.to) router.push(item.to)
}

function goUser() {
  authStore.openSidebar()
  router.push('/app')
}
</script>

<style scoped src="./sidebar.css"></style>
<style scoped>
/* 管理端特有覆盖（如需要） */
</style>
