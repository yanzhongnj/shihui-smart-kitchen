<template>
  <div v-if="task" class="task-progress">
    <div class="tp-header">
      <span class="tp-title">智能处理任务 #{{ task.id }}</span>
      <span class="tp-status" :class="task.status">{{ statusText }}</span>
    </div>
    <div v-if="showProgress" class="tp-bar">
      <div class="tp-fill" :style="{ width: task.progress + '%' }"></div>
    </div>
    <div class="tp-info">
      <span v-if="task.currentPhase" class="tp-phase">{{ task.currentPhase }}</span>
      <span v-if="showProgress" class="tp-percent">{{ task.progress }}%</span>
    </div>
    <p v-if="task.errorMessage" class="tp-error">❌ {{ task.errorMessage }}</p>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  task: { type: Object, default: null },
})

const showProgress = computed(() =>
  props.task && (props.task.status === 'running' || props.task.status === 'queued')
)

const statusText = computed(() => {
  if (!props.task) return ''
  const map = { queued: '排队中', running: '处理中', completed: '已完成', failed: '失败' }
  return map[props.task.status] || props.task.status
})
</script>

<style scoped>
.task-progress {
  background: #fff;
  border-radius: 8px;
  padding: 16px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.08);
}
.tp-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
.tp-title { font-size: 14px; font-weight: 600; }
.tp-status { padding: 2px 8px; border-radius: 10px; font-size: 11px; color: #fff; }
.tp-status.running { background: #f39c12; }
.tp-status.completed { background: #27ae60; }
.tp-status.failed { background: #e74c3c; }
.tp-bar { height: 6px; background: #eee; border-radius: 3px; overflow: hidden; margin-bottom: 6px; }
.tp-fill { height: 100%; background: #e74c3c; border-radius: 3px; transition: width 0.3s; }
.tp-info { display: flex; justify-content: space-between; font-size: 12px; color: #666; }
.tp-error { color: #e74c3c; font-size: 12px; margin-top: 8px; }
</style>
