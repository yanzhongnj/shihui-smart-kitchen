<template>
  <div class="step-list">
    <div v-if="steps.length === 0" class="empty">
      <p v-if="loading">⏳ 加载中...</p>
      <p v-else>暂无步骤数据</p>
    </div>
    <StepCard
      v-for="(step, idx) in steps"
      :key="step.id"
      :step="step"
      :is-active="activeIndex === idx"
      :is-looping="step.isLooping"
      :confidence="step.confidence"
      @click="$emit('step-click', idx)"
      @toggle-loop="(id) => $emit('toggle-loop', id)"
    />
  </div>
</template>

<script setup>
import StepCard from './StepCard.vue'
defineProps({
  steps: { type: Array, default: () => [] },
  activeIndex: { type: Number, default: -1 },
  loading: Boolean,
})
defineEmits(['step-click', 'toggle-loop'])
</script>

<style scoped>
.step-list { padding: 8px; }
.empty { text-align: center; padding: 40px; color: #999; }
</style>
