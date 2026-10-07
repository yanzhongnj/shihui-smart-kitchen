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
  'Dashboard': '仪表盘',
  'VideoList': '我的视频',
  'VideoUpload': '上传视频',
  'VideoDownload': '链接下载',
  'DownloadHistory': '下载任务',
  'VideoDetail': '视频详情',
  'TaskList': '智能处理任务',
  'RecipeList': '菜谱',
  'RecipeCreate': '录入菜谱',
  'RecipeEdit': '编辑菜谱',
  'RecipeDetail': '菜谱详情',
  'AdminUsers': '用户管理',
  'AdminMonitor': '系统监控',
  'AdminVideos': '全局视频',
  'AdminDownloads': '下载任务',
  'AdminAppVersions': 'APP 版本',
  'AdminContent': '内容管理',
  'AdminProfile': '数据画像',
  'AdminContentSource': '内容源与反查',
  'AdminFoodLibrary': '食物营养库',
  'AdminRecipePrecision': '菜谱精确化',
  'OrgManage': '我的企业',
  'AdminOrgs': '企业管理',
  'AdminVendors': '厂商管理',
  'DeviceManage': '设备控制',
  'ApiCenter': '开发者中心',
}

const pageTitle = computed(() => pageTitles[route.name] || authStore.appName)
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
