import { defineStore } from 'pinia'
import { videoAPI } from '@/api/video'

export const useVideoStore = defineStore('video', {
  state: () => ({
    videos: [],
    currentVideo: null,
    totalPages: 0,
    total: 0,
    loading: false,
  }),

  actions: {
    async fetchVideos(params = {}) {
      this.loading = true
      try {
        const res = await videoAPI.getList(params)
        this.videos = res.data?.items || []
        this.total = res.data?.total || 0
        this.totalPages = Math.max(1, Math.ceil(this.total / (params.size || 20)))
      } finally {
        this.loading = false
      }
    },

    async fetchDetail(id) {
      const res = await videoAPI.getDetail(id)
      this.currentVideo = res.data
      return res.data
    },

    async deleteVideo(id) {
      await videoAPI.delete(id)
      this.videos = this.videos.filter(v => v.id !== id)
    },
  },
})
