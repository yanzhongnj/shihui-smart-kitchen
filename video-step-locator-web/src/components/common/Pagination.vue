<template>
  <div class="pagination">
    <button class="page-btn" :disabled="current === 0" @click="go(current - 1)">‹ 上一页</button>
    <span class="page-nums">
      <template v-for="(item, idx) in pageList" :key="idx">
        <span v-if="item === '...'" class="page-ellipsis">…</span>
        <button v-else class="page-btn" :class="{ active: current === item - 1 }" @click="go(item - 1)">{{ item }}</button>
      </template>
    </span>
    <button class="page-btn" :disabled="current >= pages - 1" @click="go(current + 1)">下一页 ›</button>
    <span class="page-total">共 {{ total }} 条</span>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { smartPageList } from '@/utils/pagination'

const props = defineProps({
  current: { type: Number, required: true },
  pages: { type: Number, required: true },
  total: { type: Number, default: 0 },
})
const emit = defineEmits(['change'])
const pageList = computed(() => smartPageList(props.current + 1, props.pages))
function go(p) {
  if (p < 0 || p >= props.pages) return
  emit('change', p)
}
</script>

<style scoped>
.pagination { display: flex; align-items: center; gap: 8px; margin-top: 16px; justify-content: center; }
.page-btn { padding: 6px 12px; border: 1px solid var(--border-color); border-radius: 6px; background: #fff; cursor: pointer; }
.page-btn.active { background: var(--primary, #409eff); color: #fff; border-color: var(--primary, #409eff); }
.page-btn:disabled { opacity: 0.4; cursor: not-allowed; }
.page-total { color: var(--text-secondary); font-size: 13px; }
.page-ellipsis { padding: 6px 2px; color: var(--text-secondary); user-select: none; }
</style>
