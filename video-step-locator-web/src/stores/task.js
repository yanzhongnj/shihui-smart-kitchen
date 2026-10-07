import { defineStore } from 'pinia'
import { taskAPI } from '@/api/task'

export const useTaskStore = defineStore('task', {
  state: () => ({
    tasks: [],
    total: 0,
    totalPages: 0,
    loading: false,
    lastParams: {},
  }),

  getters: {
    // 是否存在需要持续刷新的活跃任务（运行中/排队中；暂停与停止的不算，避免停止后仍在刷新）
    hasActive: (state) => state.tasks.some(t =>
      t.status === 'running' || t.status === 'queued'),
  },

  actions: {
    // silent=true 用于后台轮询：不切换 loading，避免表格被「加载中…」顶替造成抖动
    async fetchTasks(params = {}, silent = false) {
      this.lastParams = { page: 0, size: 20, ...params }
      if (!silent) this.loading = true
      try {
        const res = await taskAPI.getList(params)
        this.tasks = res.data?.items || []
        this.total = res.data?.total || 0
        this.totalPages = Math.max(1, Math.ceil(this.total / (params.size || 20)))
      }
      finally { if (!silent) this.loading = false }
    },
    async triggerProcess(videoId) { return await taskAPI.triggerProcess(videoId) },
    async startTask(id) { await taskAPI.start(id); await this.fetchTasks(this.lastParams, true) },
    async pauseTask(id) { await taskAPI.pause(id); await this.fetchTasks(this.lastParams, true) },
    async resumeTask(id) { await taskAPI.start(id); await this.fetchTasks(this.lastParams, true) },
    async deleteTask(id) { await taskAPI.delete(id); await this.fetchTasks(this.lastParams, true) },
    async cancelTask(id) { await taskAPI.cancel(id); await this.fetchTasks(this.lastParams, true) },
  },
})
