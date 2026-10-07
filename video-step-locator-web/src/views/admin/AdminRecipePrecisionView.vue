<template>
  <div class="admin-content">
    <section class="section-card">
      <h3>🎯 精确化评分分布 <span class="chip-tip">阈值校准参考（precision-stats）</span></h3>
      <div v-if="loading" class="loading">加载中...</div>
      <template v-else-if="stats.total">
        <div class="stat-grid">
          <div class="stat-item"><span>判定阈值</span><b>{{ fmt(stats.threshold) }}</b></div>
          <div class="stat-item"><span>菜谱总数</span><b>{{ stats.total }}</b></div>
          <div class="stat-item"><span>工业型</span><b class="ind">{{ stats.industrialCount }}</b></div>
          <div class="stat-item"><span>家常型</span><b class="home">{{ stats.homeCount }}</b></div>
        </div>
        <div class="bands">
          <div v-for="b in stats.bands" :key="b.label" class="band-row">
            <span class="band-label">{{ b.label }}</span>
            <div class="band-bar">
              <div class="band-fill" :style="{ width: pct(b) + '%' }"></div>
            </div>
            <span class="band-count">{{ b.count }}（{{ pct(b) }}%）</span>
          </div>
        </div>
        <div class="hint">
          💡 判定阈值 {{ fmt(stats.threshold) }} 由后端配置项 <code>recipe.precision.threshold</code> 控制（application.yml），修改后重启生效；本页可结合分布用于阈值校准（默认 0.7）。
        </div>
      </template>
      <div v-else class="empty"><p>暂无菜谱数据</p></div>
    </section>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { recipeAPI } from '@/api/recipe'

const stats = ref({ bands: [] })
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const res = await recipeAPI.getPrecisionStats()
    stats.value = res.data || { bands: [] }
  } catch (e) { alert(e?.message || '加载失败') } finally { loading.value = false }
}
function fmt(v) { return v == null ? '-' : Number(v).toFixed(2) }
function pct(b) {
  const t = stats.value.total || 0
  return t ? ((b.count / t) * 100).toFixed(1) : '0'
}

onMounted(load)
</script>

<style scoped>
.admin-content { padding: 24px; }
.section-card { background: #fff; border-radius: 8px; box-shadow: 0 1px 4px rgba(0,0,0,0.08); padding: 20px 24px; }
.section-card h3 { margin: 0 0 16px; font-size: 16px; }
.chip-tip { font-size: 12px; color: var(--text-secondary); font-weight: 400; margin-left: 8px; }
.stat-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(130px, 1fr)); gap: 14px; margin-bottom: 20px; }
.stat-item { background: #f7f9fc; border-radius: 8px; padding: 14px 16px; text-align: center; }
.stat-item span { display: block; font-size: 12px; color: var(--text-secondary); margin-bottom: 6px; }
.stat-item b { font-size: 24px; }
.stat-item b.ind { color: #1a7f37; }
.stat-item b.home { color: #c7741e; }
.bands { display: flex; flex-direction: column; gap: 10px; margin-bottom: 18px; }
.band-row { display: flex; align-items: center; gap: 12px; }
.band-label { width: 70px; font-size: 13px; color: var(--text-secondary); text-align: right; }
.band-bar { flex: 1; height: 18px; background: #f0f2f5; border-radius: 9px; overflow: hidden; }
.band-fill { height: 100%; background: linear-gradient(90deg, #409eff, #1a7f37); border-radius: 9px; transition: width .3s; }
.band-count { width: 90px; font-size: 12px; color: var(--text-secondary); }
.hint { font-size: 12px; color: var(--text-secondary); background: #f5f7fa; border: 1px solid var(--border-color); border-radius: 6px; padding: 10px 14px; line-height: 1.7; }
.hint code { background: #eef1f5; padding: 1px 5px; border-radius: 4px; }
.loading { text-align: center; color: var(--text-secondary); padding: 30px 0; }
.empty { text-align: center; color: var(--text-secondary); padding: 30px 0; }
</style>
