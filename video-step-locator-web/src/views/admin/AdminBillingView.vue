<template>
  <div class="billing-view">
    <div class="filter-bar">
      <input v-model.number="userId" class="filter-input narrow" type="number" placeholder="用户ID（可选，全局）" @keyup.enter="apply" />
      <button class="btn-filter" @click="apply">查询</button>
      <button v-if="userId" class="btn-clear" @click="clear">✕ 全局</button>
      <span class="muted">计费公式待定价决策（待办 #11/#16）；本页展示按动作类型分项用量。</span>
    </div>

    <div v-loading="loading" class="report">
      <div v-if="report" class="report-cards">
        <div class="usage-card">
          <div class="usage-num">{{ report.totalRecords ?? 0 }}</div>
          <div class="usage-label">用量记录总数</div>
        </div>
        <div class="usage-card">
          <div class="usage-num">{{ report.userId ? `#${report.userId}` : '全平台' }}</div>
          <div class="usage-label">{{ report.username ? `用户 ${report.username}` : '范围' }}</div>
        </div>
      </div>

      <el-table v-if="rows.length" :data="rows" border stripe style="width: 100%">
        <el-table-column label="动作类型" width="140">
          <template #default="{ row }"><el-tag size="small">{{ row.type }}</el-tag></template>
        </el-table-column>
        <el-table-column label="语义" min-width="180">
          <template #default="{ row }">{{ row.semantic }}</template>
        </el-table-column>
        <el-table-column label="条数" width="90">
          <template #default="{ row }">{{ row.count }}</template>
        </el-table-column>
        <el-table-column label="数量" width="140">
          <template #default="{ row }">{{ row.amountLabel }}</template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && !rows.length" description="暂无用量数据" />
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { adminAPI } from '@/api/admin'

const report = ref(null)
const loading = ref(false)
const userId = ref(null)

onMounted(load)

const rows = computed(() => {
  if (!report.value) return []
  const byType = report.value.byType || {}
  const amount = report.value.amountByType || {}
  const semantics = report.value.semantics || {}
  return Object.keys(byType).map(t => ({
    type: t,
    semantic: semantics[t] || '—',
    count: byType[t],
    amountLabel: fmtAmount(t, amount[t] ?? 0),
  }))
})

async function load() {
  loading.value = true
  try {
    report.value = userId.value
      ? (await adminAPI.getBillingUser(userId.value)).data
      : (await adminAPI.getBillingGlobal()).data
  } catch (e) { alert(e?.message || '加载失败') } finally { loading.value = false }
}
function apply() { load() }
function clear() { userId.value = null; load() }

function fmtAmount(type, v) {
  if (type === 'videogen') return `${v} 秒`
  if (type === 'tts') return `${v} 字符`
  return formatSize(v)
}
function formatSize(b) {
  if (b == null) return '-'
  if (b < 1024) return `${b} B`
  if (b < 1024 * 1024) return `${(b / 1024).toFixed(1)} KB`
  if (b < 1024 * 1024 * 1024) return `${(b / 1024 / 1024).toFixed(1)} MB`
  return `${(b / 1024 / 1024 / 1024).toFixed(2)} GB`
}
</script>

<style scoped>
.filter-bar { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.filter-input { border: 1px solid #dcdfe6; border-radius: 6px; padding: 6px 10px; font-size: 13px; }
.filter-input.narrow { width: 200px; }
.btn-filter, .btn-clear { border: 1px solid #dcdfe6; background: #fff; border-radius: 6px; padding: 6px 12px; cursor: pointer; }
.btn-filter:hover { color: var(--primary, #409eff); border-color: var(--primary, #409eff); }
.muted { font-size: 12px; color: var(--text-secondary); }
.report-cards { display: flex; gap: 12px; margin-bottom: 14px; }
.usage-card { background: #fff; border-radius: 8px; box-shadow: 0 1px 4px rgba(0,0,0,0.08); padding: 14px 20px; text-align: center; min-width: 140px; }
.usage-num { font-size: 22px; font-weight: 700; color: var(--primary, #409eff); }
.usage-label { font-size: 12px; color: var(--text-secondary); margin-top: 4px; }
</style>
