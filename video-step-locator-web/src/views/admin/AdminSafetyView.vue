<template>
  <div class="safety-page">
    <div class="page-head">
      <h2>🛡️ 安全规则运营（食物安全提醒知识库）</h2>
      <p class="tip">采集管道：原文/URL → LLM 提取候选 → 审核通过入库；反馈闭环：用户「无用/纠错」→ 无用率高自动降级（每日 03:30），纠错进候选队列。</p>
    </div>

    <el-tabs v-model="tab">
      <!-- ===== ① 候选审核 ===== -->
      <el-tab-pane label="候选审核" name="candidates">
        <div class="filter-bar">
          <select v-model="candQuery.status" class="filter-input narrow">
            <option value="">全部状态</option>
            <option value="pending">待审核</option>
            <option value="approved">已通过</option>
            <option value="rejected">已驳回</option>
          </select>
          <button class="btn-filter" @click="loadCandidates">查询</button>
          <button class="btn-filter ghost" @click="resetCand">重置</button>
          <span class="sel-tip">共 {{ candTotal }} 条候选</span>
        </div>
        <el-table v-loading="candLoading" :data="candidates" border stripe empty-text="暂无候选" style="width: 100%">
          <el-table-column prop="id" label="ID" width="60" />
          <el-table-column prop="category" label="类别" width="80">
            <template #default="{ row }"><el-tag size="small">{{ row.category }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="riskLevel" label="等级" width="80">
            <template #default="{ row }">
              <el-tag size="small" :type="row.riskLevel === 'RED' ? 'danger' : row.riskLevel === 'YELLOW' ? 'warning' : 'success'">{{ row.riskLevel }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="title" label="标题" min-width="160" />
          <el-table-column prop="triggerValue" label="触发值" min-width="120" />
          <el-table-column prop="relatedFood" label="关联" width="110">
            <template #default="{ row }">{{ row.relatedFood || '-' }}</template>
          </el-table-column>
          <el-table-column prop="source" label="出处" width="130" />
          <el-table-column prop="status" label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'approved' ? 'success' : row.status === 'rejected' ? 'info' : 'warning'">
                {{ row.status === 'approved' ? '已通过' : row.status === 'rejected' ? '已驳回' : '待审核' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <template v-if="row.status === 'pending'">
                <button class="btn-filter" @click="approve(row)">通过</button>
                <button class="btn-filter danger" @click="reject(row)">驳回</button>
              </template>
              <span v-else class="muted">已处理</span>
            </template>
          </el-table-column>
        </el-table>
        <div class="pager">
          <button class="btn-filter ghost" :disabled="candPage <= 0" @click="candPage--; loadCandidates()">上一页</button>
          <span>第 {{ candPage + 1 }} 页</span>
          <button class="btn-filter ghost" :disabled="(candPage + 1) * candSize >= candTotal" @click="candPage++; loadCandidates()">下一页</button>
        </div>
      </el-tab-pane>

      <!-- ===== ② 规则列表 ===== -->
      <el-tab-pane label="规则列表" name="rules">
        <div class="filter-bar">
          <select v-model="ruleQuery.category" class="filter-input narrow">
            <option value="">全部分类</option>
            <option value="MED">MED</option>
            <option value="PROC">PROC</option>
            <option value="IND">IND</option>
            <option value="ABS">ABS</option>
            <option value="MYTH">MYTH</option>
          </select>
          <button class="btn-filter" @click="loadRules">查询</button>
          <span class="sel-tip">共 {{ rules.length }} 条启用规则</span>
        </div>
        <el-table v-loading="ruleLoading" :data="rules" border stripe empty-text="暂无规则" style="width: 100%">
          <el-table-column prop="id" label="ID" width="60" />
          <el-table-column prop="category" label="类别" width="80" />
          <el-table-column prop="level" label="等级" width="80">
            <template #default="{ row }">
              <el-tag size="small" :type="row.level === 'RED' ? 'danger' : row.level === 'YELLOW' ? 'warning' : 'success'">{{ row.level }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="title" label="标题" min-width="180" />
          <el-table-column prop="triggerValue" label="触发值" min-width="130" />
          <el-table-column prop="source" label="出处" width="150" />
          <el-table-column prop="confidence" label="置信度" width="80" />
        </el-table>
      </el-tab-pane>

      <!-- ===== ③ 反馈 ===== -->
      <el-tab-pane label="反馈与降级" name="feedback">
        <h3 class="sub-head">各规则无用率统计（降序）</h3>
        <el-table v-loading="fbLoading" :data="fbSummary" border stripe empty-text="暂无反馈" style="width: 100%">
          <el-table-column prop="ruleId" label="规则ID" width="80" />
          <el-table-column prop="title" label="规则标题" min-width="180" />
          <el-table-column prop="category" label="类别" width="80" />
          <el-table-column prop="level" label="等级" width="80" />
          <el-table-column prop="total" label="反馈数" width="80" />
          <el-table-column prop="useless" label="无用数" width="80" />
          <el-table-column label="无用率" width="120">
            <template #default="{ row }">
              <el-tag size="small" :type="row.uselessRate >= 50 ? 'danger' : row.uselessRate >= 30 ? 'warning' : 'success'">{{ row.uselessRate }}%</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="enabled" label="状态" width="80">
            <template #default="{ row }">{{ row.enabled ? '启用' : '已停用' }}</template>
          </el-table-column>
        </el-table>
        <h3 class="sub-head">最近反馈明细</h3>
        <el-table v-loading="fbDetailLoading" :data="fbDetail" border stripe empty-text="暂无反馈明细" style="width: 100%">
          <el-table-column prop="id" label="ID" width="60" />
          <el-table-column prop="ruleId" label="规则ID" width="80" />
          <el-table-column prop="userId" label="用户" width="80" />
          <el-table-column prop="useful" label="评价" width="80">
            <template #default="{ row }">{{ row.useful === 1 ? '👍 有用' : row.useful === 0 ? '👎 无用' : '-' }}</template>
          </el-table-column>
          <el-table-column prop="comment" label="纠错/建议" min-width="200" />
          <el-table-column prop="createdAt" label="时间" width="160" />
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminAPI } from '@/api/admin'

const tab = ref('candidates')

// ---- 候选审核 ----
const candLoading = ref(false)
const candidates = ref([])
const candTotal = ref(0)
const candPage = ref(0)
const candSize = 10
const candQuery = ref({ status: 'pending' })

async function loadCandidates() {
  candLoading.value = true
  try {
    const res = await adminAPI.getSafetyCandidates({ ...candQuery.value, page: candPage.value, size: candSize })
    candidates.value = res.data?.items ?? []
    candTotal.value = res.data?.total ?? 0
  } finally {
    candLoading.value = false
  }
}
function resetCand() {
  candQuery.value = { status: 'pending' }
  candPage.value = 0
  loadCandidates()
}

async function approve(row) {
  try {
    await ElMessageBox.confirm(`通过候选「${row.title}」并入库为正式规则？`, '确认通过', { type: 'warning' })
    const res = await adminAPI.approveSafetyCandidate(row.id)
    ElMessage.success(`已入库，规则 ID=${res.data?.ruleId}`)
    loadCandidates()
  } catch (e) { /* 取消或错误 */ }
}
async function reject(row) {
  try {
    await ElMessageBox.confirm(`驳回候选「${row.title}」？`, '确认驳回', { type: 'warning' })
    await adminAPI.rejectSafetyCandidate(row.id)
    ElMessage.success('已驳回')
    loadCandidates()
  } catch (e) { /* 取消 */ }
}

// ---- 规则列表 ----
const ruleLoading = ref(false)
const rules = ref([])
const ruleQuery = ref({ category: '' })
async function loadRules() {
  ruleLoading.value = true
  try {
    const res = await adminAPI.getSafetyRules({ category: ruleQuery.value.category || undefined })
    rules.value = res.data ?? []
  } finally {
    ruleLoading.value = false
  }
}

// ---- 反馈 ----
const fbLoading = ref(false)
const fbDetailLoading = ref(false)
const fbSummary = ref([])
const fbDetail = ref([])
async function loadFeedback() {
  fbLoading.value = true
  fbDetailLoading.value = true
  try {
    const s = await adminAPI.getSafetyFeedbackSummary()
    fbSummary.value = s.data ?? []
    const d = await adminAPI.getSafetyFeedback({ page: 0, size: 20 })
    fbDetail.value = d.data?.items ?? []
  } finally {
    fbLoading.value = false
    fbDetailLoading.value = false
  }
}

onMounted(() => {
  loadCandidates()
  loadRules()
  loadFeedback()
})
</script>

<style scoped>
.page-head { margin-bottom: 12px; }
.page-head h2 { margin: 0 0 4px; }
.tip { color: #888; font-size: 12px; margin: 0; }
.sub-head { margin: 14px 0 8px; font-size: 14px; }
.filter-bar { display: flex; gap: 8px; align-items: center; margin-bottom: 10px; flex-wrap: wrap; }
.filter-input { padding: 6px 8px; border: 1px solid #d0d7de; border-radius: 6px; font-size: 13px; }
.filter-input.narrow { width: 130px; }
.btn-filter { padding: 6px 14px; border: 1px solid #1668dc; color: #1668dc; background: #fff; border-radius: 6px; cursor: pointer; font-size: 13px; }
.btn-filter:hover { background: #f0f6ff; }
.btn-filter.danger { border-color: #e5484d; color: #e5484d; }
.btn-filter.danger:hover { background: #fff0f0; }
.btn-filter.ghost { border-color: #d0d7de; color: #57606a; }
.btn-filter:disabled { opacity: .5; cursor: not-allowed; }
.sel-tip { color: #57606a; font-size: 12px; }
.pager { display: flex; gap: 10px; align-items: center; margin-top: 10px; }
.muted { color: #999; font-size: 12px; }
</style>
