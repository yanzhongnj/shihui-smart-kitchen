<template>
  <header class="app-header">
    <h1 class="page-title">{{ pageTitle }}</h1>
  </header>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const authStore = useAuthStore()

const pageTitles = {
  'AdminDashboard': '管理仪表盘',
  'AdminUsers': '用户管理',
  'AdminUserDetail': '用户详情',
  'AdminVideos': '全局视频',
  'AdminDownloads': '下载任务',
  'AdminAppVersions': 'APP 版本',
  'AdminContent': '内容管理',
  'AdminProfile': '数据画像',
  'AdminContentSource': '内容源与反查',
  'AdminFoodLibrary': '食物营养库',
  'AdminRecipePrecision': '菜谱精确化',
  'AdminOrgs': '企业管理',
  'AdminVendors': '厂商管理',
  'AdminMonitor': '系统监控',
  'AdminAiConfig': 'AI 引擎配置',
  'AdminXunweiSessions': '寻味会话',
  'AdminXunweiMemory': '寻味记忆',
  'AdminCreationSessions': '酿味会话',
  'AdminCreations': '酿味创作',
  'AdminUsage': '用量记录',
  'AdminBilling': '计费分项',
}

const pageTitle = computed(() => pageTitles[route.name] || '管理端')
</script>

<style scoped>
.app-header {
  height: var(--header-height);
  display: flex;
  align-items: center;
  padding: 0 24px;
  background: #fff;
  border-bottom: 1px solid #eee;
  flex-shrink: 0;
}
.page-title { font-size: 20px; font-weight: 600; white-space: nowrap; }
</style>
