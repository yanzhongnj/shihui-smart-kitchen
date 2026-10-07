<template>
  <div class="step-timeline">
    <div class="timeline-track">
      <div
        v-for="step in steps"
        :key="step.id"
        class="timeline-marker"
        :class="{ active: step.id === activeStepId }"
        :style="{ left: position(step) + '%' }"
        @click="$emit('seek', step.startTime)"
        :title="step.summary"
      >
        <span class="marker-dot"></span>
      </div>
    </div>
    <div class="timeline-labels">
      <span v-for="step in steps" :key="step.id"
            :class="{ active: step.id === activeStepId }"
            :style="{ left: position(step) + '%' }"
            @click="$emit('seek', step.startTime)">
        {{ step.stepIndex }}
      </span>
    </div>
  </div>
</template>

<script setup>
defineProps({
  steps: { type: Array, default: () => [] },
  activeStepId: { type: Number, default: null },
  duration: { type: Number, default: 0 },
})
defineEmits(['seek'])

function position(step) {
  if (!step.startTime) return 0
  return (step.startTime / (duration || step.endTime + 1)) * 100
}
</script>

<style scoped>
.step-timeline { padding: 8px 0; }
.timeline-track {
  position: relative;
  height: 4px;
  background: #eee;
  border-radius: 2px;
  margin-bottom: 4px;
}
.timeline-marker {
  position: absolute;
  top: -4px;
  transform: translateX(-50%);
  cursor: pointer;
}
.marker-dot {
  display: block;
  width: 12px; height: 12px;
  background: #999;
  border-radius: 50%;
  transition: all 0.2s;
}
.timeline-marker.active .marker-dot { background: #e74c3c; transform: scale(1.3); }
.timeline-labels { position: relative; height: 16px; }
.timeline-labels span {
  position: absolute;
  transform: translateX(-50%);
  font-size: 11px;
  color: #999;
  cursor: pointer;
}
.timeline-labels span.active { color: #e74c3c; font-weight: 600; }
</style>
