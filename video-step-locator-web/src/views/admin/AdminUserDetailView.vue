<template>
  <div class="user-detail">
    <div class="detail-head">
      <button class="btn-back" @click="goBack">← 返回用户列表</button>
      <h2 class="detail-title">
        {{ detail?.nickname || detail?.username || '用户' }}
        <el-tag :type="detail?.role === 'ADMIN' ? 'danger' : 'info'" size="small" class="ml8">
          {{ detail?.role === 'ADMIN' ? '系统管理员' : '用户' }}
        </el-tag>
        <el-tag :type="detail?.status === 1 ? 'success' : 'info'" size="small" class="ml8">
          {{ detail?.status === 1 ? '启用' : '禁用' }}
        </el-tag>
      </h2>
      <div v-if="detail" class="meta-line">
        <span>ID: {{ detail.id }}</span>
        <span>用户名: {{ detail.username }}</span>
        <span v-if="detail.email">邮箱: {{ detail.email }}</span>
        <span>注册: {{ fmt(detail.createdAt) }}</span>
      </div>
    </div>

    <el-tabs v-model="activeTab" class="detail-tabs">
      <!-- ===== 概览 ===== -->
      <el-tab-pane label="概览" name="overview">
        <div v-if="loading" class="loading">加载中...</div>
        <div v-else-if="detail" class="overview-grid">
          <div class="card">
            <h4>👤 基本信息</h4>
            <el-descriptions :column="2" border size="small">
              <el-descriptions-item label="用户名">{{ detail.username }}</el-descriptions-item>
              <el-descriptions-item label="昵称">{{ detail.nickname || '-' }}</el-descriptions-item>
              <el-descriptions-item label="邮箱">{{ detail.email || '-' }}</el-descriptions-item>
              <el-descriptions-item label="角色">{{ detail.role }}</el-descriptions-item>
              <el-descriptions-item label="状态">{{ detail.status === 1 ? '启用' : '禁用' }}</el-descriptions-item>
              <el-descriptions-item label="注册时间">{{ fmt(detail.createdAt) }}</el-descriptions-item>
            </el-descriptions>
          </div>
          <div class="card">
            <h4>🏅 成长等级</h4>
            <div v-if="detail.growth" class="level-block">
              <div class="level-num">{{ detail.growth.level }} <span class="lvl">{{ detail.growth.levelName }}</span></div>
              <el-progress :percentage="detail.growth.progress || 0" :stroke-width="10" />
              <div class="exp-line">EXP {{ detail.growth.totalExp }} / 距下一级 {{ detail.growth.nextThreshold - detail.growth.totalExp }}</div>
            </div>
            <h4 class="mt12">🎖️ 徽章（{{ (detail.badges || []).filter(b => b.obtained).length }}/{{ (detail.badges || []).length }}）</h4>
            <div class="badge-row">
              <span v-for="b in detail.badges" :key="b.code" class="badge" :title="b.desc"
                    :class="{ dim: !b.obtained }">{{ b.icon }} {{ b.name }}</span>
            </div>
          </div>
          <div class="card">
            <h4>📊 活跃计数</h4>
            <el-descriptions :column="3" border size="small">
              <el-descriptions-item label="打卡">{{ detail.checkinCount ?? 0 }}</el-descriptions-item>
              <el-descriptions-item label="作品">{{ detail.workCount ?? 0 }}</el-descriptions-item>
              <el-descriptions-item label="收藏">{{ detail.favoriteCount ?? 0 }}</el-descriptions-item>
              <el-descriptions-item label="计划">{{ detail.planCount ?? 0 }}</el-descriptions-item>
              <el-descriptions-item label="饮食">{{ detail.dietCount ?? 0 }}</el-descriptions-item>
              <el-descriptions-item label="体重">{{ detail.weightCount ?? 0 }}</el-descriptions-item>
            </el-descriptions>
          </div>
        </div>
      </el-tab-pane>

      <!-- ===== 打卡记录 ===== -->
      <el-tab-pane label="打卡记录" name="checkins">
        <div class="filter-bar">
          <input v-model="checkinMonth" class="filter-input narrow" placeholder="yyyy-MM" @keyup.enter="loadCheckins" />
          <button class="btn-filter" @click="loadCheckins">查询</button>
        </div>
        <el-table v-loading="loading" :data="checkins" border stripe empty-text="暂无打卡记录" style="width: 100%">
          <el-table-column prop="date" label="日期" width="110" />
          <el-table-column prop="recipeTitle" label="菜名" min-width="140" />
          <el-table-column prop="mealType" label="餐次" width="90" />
          <el-table-column prop="calories" label="热量" width="80">
            <template #default="{ row }">{{ row.calories ?? '-' }}</template>
          </el-table-column>
          <el-table-column prop="mood" label="心情" width="90" />
          <el-table-column prop="note" label="备注" min-width="140">
            <template #default="{ row }">{{ row.note || '-' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="90" fixed="right">
            <template #default="{ row }">
              <el-button link type="danger" size="small" @click="removeCheckin(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- ===== 健康档案 ===== -->
      <el-tab-pane label="健康档案" name="health">
        <div v-loading="loading" class="card">
          <template v-if="healthProfile?.exists">
            <el-descriptions :column="3" border size="small">
              <el-descriptions-item label="身高(cm)">{{ healthProfile.height ?? '-' }}</el-descriptions-item>
              <el-descriptions-item label="体重(kg)">{{ healthProfile.weight ?? '-' }}</el-descriptions-item>
              <el-descriptions-item label="目标体重">{{ healthProfile.targetWeight ?? '-' }}</el-descriptions-item>
              <el-descriptions-item label="年龄">{{ healthProfile.age ?? '-' }}</el-descriptions-item>
              <el-descriptions-item label="性别">{{ healthProfile.gender || '-' }}</el-descriptions-item>
              <el-descriptions-item label="运动量">{{ healthProfile.activityLevel || '-' }}</el-descriptions-item>
              <el-descriptions-item label="BMR">{{ healthProfile.bmr ?? '-' }}</el-descriptions-item>
              <el-descriptions-item label="TDEE">{{ healthProfile.tdee ?? '-' }}</el-descriptions-item>
              <el-descriptions-item label="热量预算">{{ healthProfile.targetCalories ?? '-' }}</el-descriptions-item>
              <el-descriptions-item label="蛋白质">{{ healthProfile.targetProtein ?? '-' }}</el-descriptions-item>
              <el-descriptions-item label="脂肪">{{ healthProfile.targetFat ?? '-' }}</el-descriptions-item>
              <el-descriptions-item label="碳水">{{ healthProfile.targetCarbs ?? '-' }}</el-descriptions-item>
            </el-descriptions>
          </template>
          <el-empty v-else description="该用户未建立健康档案" />
        </div>
      </el-tab-pane>

      <!-- ===== 饮食记录 ===== -->
      <el-tab-pane label="饮食记录" name="diet">
        <div class="filter-bar">
          <input v-model="dietDate" class="filter-input narrow" placeholder="yyyy-MM-dd（可选）" @keyup.enter="loadDiet" />
          <button class="btn-filter" @click="loadDiet">查询</button>
        </div>
        <el-table v-loading="loading" :data="dietList" border stripe empty-text="暂无饮食记录" style="width: 100%">
          <el-table-column prop="date" label="日期" width="110" />
          <el-table-column prop="mealType" label="餐次" width="90" />
          <el-table-column prop="foodName" label="食物" min-width="140">
            <template #default="{ row }">{{ row.foodName || row.recipeTitle || '-' }}</template>
          </el-table-column>
          <el-table-column prop="calories" label="热量" width="90">
            <template #default="{ row }">{{ row.calories ?? '-' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="90" fixed="right">
            <template #default="{ row }">
              <el-button link type="danger" size="small" @click="removeDiet(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination v-if="dietTotal > dietPageSize" background layout="prev, pager, next" class="mt12"
          :total="dietTotal" :page-size="dietPageSize" :current-page="dietPage"
          @current-change="p => { dietPage = p; loadDiet() }" />
      </el-tab-pane>

      <!-- ===== 体重跟踪 ===== -->
      <el-tab-pane label="体重跟踪" name="weight">
        <div v-loading="loading" class="card">
          <div v-if="weightData?.items?.length" class="weight-line">
            <span>最新: <b>{{ weightData.latestWeight ?? '-' }} kg</b></span>
            <span>建档: {{ weightData.startWeight ?? '-' }}</span>
            <span>目标: {{ weightData.targetWeight ?? '-' }}</span>
            <span>连续: {{ weightData.streak ?? 0 }} 天</span>
            <span>进度: {{ weightData.progress ?? '-' }}%</span>
          </div>
          <el-table v-if="weightData?.items?.length" :data="weightData.items" border stripe empty-text="暂无体重记录" style="width: 100%" class="mt12">
            <el-table-column prop="date" label="日期" width="140" />
            <el-table-column prop="weight" label="体重(kg)" min-width="140" />
          </el-table>
          <el-empty v-else description="暂无体重记录" />
        </div>
      </el-tab-pane>

      <!-- ===== 做饭计划 ===== -->
      <el-tab-pane label="做饭计划" name="plans">
        <div v-loading="loading">
          <el-card v-for="p in plans" :key="p.id" class="plan-card">
            <template #header>
              <div class="plan-head">
                <span>{{ p.title }}（{{ p.startDate }}）</span>
                <span class="muted">完成 {{ p.doneItems }}/{{ p.totalItems }}</span>
                <el-button link type="danger" size="small" @click="removePlan(p)">删除计划</el-button>
              </div>
            </template>
            <el-table :data="p.items || []" border stripe size="small" empty-text="无条目">
              <el-table-column prop="recipeTitle" label="菜名" min-width="140" />
              <el-table-column prop="mealType" label="餐次" width="90" />
              <el-table-column label="周几" width="80">
                <template #default="{ row }">周{{ ['','一','二','三','四','五','六','日'][(row.dayOffset ?? 0) % 7 + 1] }}</template>
              </el-table-column>
              <el-table-column label="状态" width="80">
                <template #default="{ row }">
                  <el-tag :type="row.done === 1 ? 'success' : 'info'" size="small">{{ row.done === 1 ? '完成' : '待做' }}</el-tag>
                </template>
              </el-table-column>
            </el-table>
          </el-card>
          <el-empty v-if="!plans.length && !loading" description="暂无做饭计划" />
        </div>
      </el-tab-pane>

      <!-- ===== 收藏 ===== -->
      <el-tab-pane label="收藏" name="favorites">
        <el-table v-loading="loading" :data="favorites" border stripe empty-text="暂无收藏" style="width: 100%">
          <el-table-column prop="title" label="标题" min-width="180">
            <template #default="{ row }">{{ row.title || '-' }}</template>
          </el-table-column>
          <el-table-column label="类型" width="90">
            <template #default="{ row }">
              <el-tag :type="row.type === 'recipe' ? 'warning' : 'primary'" size="small">{{ row.type === 'recipe' ? '菜谱' : '视频' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="引用" width="110">
            <template #default="{ row }">{{ row.type === 'recipe' ? `菜谱#${row.recipeId}` : `视频#${row.videoId}` }}</template>
          </el-table-column>
          <el-table-column prop="createdAt" label="收藏时间" width="170">
            <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- ===== 口味/忌口（可编辑） ===== -->
      <el-tab-pane label="口味/忌口" name="preferences">
        <div v-loading="loading" class="card prefs-card">
          <div class="form-row">
            <span class="form-label">辣度</span>
            <el-select v-model="prefForm.spiceLevel" style="width: 160px">
              <el-option :value="0" label="不辣" />
              <el-option :value="1" label="微辣" />
              <el-option :value="2" label="中辣" />
              <el-option :value="3" label="重辣" />
            </el-select>
          </div>
          <div class="form-row"><span class="form-label">过敏原</span><input v-model="prefForm.tabooAllergens" class="filter-input w60" placeholder="逗号分隔" /></div>
          <div class="form-row"><span class="form-label">忌口食材</span><input v-model="prefForm.tabooIngredients" class="filter-input w60" placeholder="逗号分隔" /></div>
          <div class="form-row"><span class="form-label">口味偏好</span><input v-model="prefForm.preferredTastes" class="filter-input w60" placeholder="逗号分隔" /></div>
          <div class="form-row"><span class="form-label">菜系偏好</span><input v-model="prefForm.preferredCuisines" class="filter-input w60" placeholder="逗号分隔" /></div>
          <div class="form-row"><span class="form-label">热量上限</span><input v-model.number="prefForm.maxCalories" class="filter-input narrow" type="number" /></div>
          <div class="form-row"><span class="form-label">备注</span><input v-model="prefForm.notes" class="filter-input w60" placeholder="备注" /></div>
          <div class="form-row">
            <button class="btn-save" :disabled="savingPrefs" @click="savePrefs">{{ savingPrefs ? '保存中…' : '保存偏好' }}</button>
          </div>
        </div>
      </el-tab-pane>

      <!-- ===== 作品 ===== -->
      <el-tab-pane label="作品" name="works">
        <el-table v-loading="loading" :data="works" border stripe empty-text="暂无作品" style="width: 100%">
          <el-table-column prop="title" label="标题" min-width="160">
            <template #default="{ row }">{{ row.title || row.recipeTitle || '-' }}</template>
          </el-table-column>
          <el-table-column prop="recipeTitle" label="关联菜" min-width="120">
            <template #default="{ row }">{{ row.recipeTitle || '-' }}</template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="90" />
          <el-table-column prop="createdAt" label="时间" width="170">
            <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="90" fixed="right">
            <template #default="{ row }">
              <el-button link type="danger" size="small" @click="removeWork(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- ===== 成长 ===== -->
      <el-tab-pane label="成长" name="growth">
        <div v-loading="loading">
          <div v-if="growth?.levelInfo" class="card">
            <div class="level-block">
              <div class="level-num">{{ growth.levelInfo.level }} <span class="lvl">{{ growth.levelInfo.levelName }}</span></div>
              <el-progress :percentage="growth.levelInfo.progress || 0" :stroke-width="10" />
              <div class="exp-line">EXP {{ growth.levelInfo.totalExp }} / 距离 {{ growth.levelInfo.nextThreshold }}</div>
            </div>
            <h4 class="mt12">近期经验</h4>
            <el-table :data="growth.levelInfo.recentLogs || []" border stripe size="small" empty-text="暂无">
              <el-table-column prop="delta" label="经验" width="80" />
              <el-table-column prop="reason" label="来源" min-width="120" />
              <el-table-column prop="createdAt" label="时间" width="170">
                <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
              </el-table-column>
            </el-table>
          </div>
          <div v-if="growth?.badges" class="card mt12">
            <h4>🎖️ 徽章</h4>
            <div class="badge-row">
              <span v-for="b in growth.badges" :key="b.code" class="badge" :title="b.desc" :class="{ dim: !b.obtained }">
                {{ b.icon }} {{ b.name }}
              </span>
            </div>
          </div>
        </div>
      </el-tab-pane>

      <!-- ===== 用量 ===== -->
      <el-tab-pane label="用量" name="usage">
        <el-table v-loading="loading" :data="userUsage" border stripe empty-text="暂无用量" style="width: 100%">
          <el-table-column prop="actionType" label="类型" width="110" />
          <el-table-column prop="deviceId" label="设备" min-width="140">
            <template #default="{ row }">{{ row.deviceId || '-' }}</template>
          </el-table-column>
          <el-table-column prop="videoId" label="引用" width="100">
            <template #default="{ row }">{{ row.videoId ?? '-' }}</template>
          </el-table-column>
          <el-table-column label="数量" width="120">
            <template #default="{ row }">{{ fmtAmount(row.actionType, row.bytes) }}</template>
          </el-table-column>
          <el-table-column prop="createdAt" label="时间" width="170">
            <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
          </el-table-column>
        </el-table>
        <el-pagination v-if="userUsageTotal > 10" background layout="prev, pager, next" class="mt12"
          :total="userUsageTotal" :page-size="10" :current-page="usagePage"
          @current-change="p => { usagePage = p; loadUserUsage() }" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { adminAPI } from '@/api/admin'

const route = useRoute()
const router = useRouter()
const userId = Number(route.params.id)

const activeTab = ref('overview')
const loading = ref(false)
const savingPrefs = ref(false)

const detail = ref(null)
const checkins = ref([])
const checkinMonth = ref('')
const healthProfile = ref({})
const dietList = ref([])
const dietPage = ref(1)
const dietPageSize = 10
const dietTotal = ref(0)
const dietDate = ref('')
const weightData = ref({})
const plans = ref([])
const favorites = ref([])
const prefForm = ref({})
const works = ref([])
const growth = ref({})
const userUsage = ref([])
const userUsageTotal = ref(0)
const usagePage = ref(1)

onMounted(async () => {
  await loadOverview()
  await loadCheckins()
  await loadHealth()
  await loadDiet()
  await loadWeight()
  await loadPlans()
  await loadFavorites()
  await loadPrefs()
  await loadWorks()
  await loadGrowth()
  await loadUserUsage()
})

async function loadOverview() {
  loading.value = true
  try { detail.value = (await adminAPI.getUserDetail(userId)).data || null }
  catch (e) { alert(e?.message || '加载失败') } finally { loading.value = false }
}
async function loadCheckins() {
  loading.value = true
  try { checkins.value = (await adminAPI.getUserCheckins(userId, { month: checkinMonth.value || undefined })).data || [] }
  catch (e) { alert(e?.message || '加载失败') } finally { loading.value = false }
}
async function loadHealth() {
  try { healthProfile.value = (await adminAPI.getUserHealthProfile(userId)).data || {} }
  catch (e) { /* 静默 */ }
}
async function loadDiet() {
  loading.value = true
  try {
    const res = (await adminAPI.getUserDiet(userId, { date: dietDate.value || undefined, page: dietPage.value, size: dietPageSize })).data || {}
    dietList.value = res.items || []
    dietTotal.value = res.total || 0
  } catch (e) { alert(e?.message || '加载失败') } finally { loading.value = false }
}
async function loadWeight() {
  try { weightData.value = (await adminAPI.getUserWeightLogs(userId, { days: 30 })).data || {} }
  catch (e) { /* 静默 */ }
}
async function loadPlans() {
  try { plans.value = (await adminAPI.getUserPlans(userId)).data || [] }
  catch (e) { /* 静默 */ }
}
async function loadFavorites() {
  try { favorites.value = (await adminAPI.getUserFavorites(userId)).data || [] }
  catch (e) { /* 静默 */ }
}
async function loadPrefs() {
  try { prefForm.value = (await adminAPI.getUserPreferences(userId)).data || {} }
  catch (e) { /* 静默 */ }
}
async function loadWorks() {
  try { works.value = (await adminAPI.getUserWorks(userId)).data || [] }
  catch (e) { /* 静默 */ }
}
async function loadGrowth() {
  try { growth.value = (await adminAPI.getUserGrowth(userId)).data || {} }
  catch (e) { /* 静默 */ }
}
async function loadUserUsage() {
  loading.value = true
  try {
    const res = (await adminAPI.getUsage({ userId, page: usagePage.value - 1, size: 10 })).data || {}
    userUsage.value = res.items || []
    userUsageTotal.value = res.total || 0
  } catch (e) { alert(e?.message || '加载失败') } finally { loading.value = false }
}

async function removeCheckin(row) {
  if (!confirm(`软删除打卡「${row.recipeTitle}」(${row.date})？其自动生成的饮食记录一并标记删除（可恢复）。`)) return
  try { await adminAPI.deleteCheckin(row.id); loadCheckins(); loadDiet() } catch (e) { alert(e?.message || '操作失败') }
}
async function removeDiet(row) {
  if (!confirm(`软删除饮食记录「${row.foodName || row.recipeTitle || row.id}」？（可恢复）`)) return
  try { await adminAPI.deleteDiet(row.id); loadDiet() } catch (e) { alert(e?.message || '操作失败') }
}
async function removePlan(p) {
  if (!confirm(`软删除计划「${p.title}」？（可恢复）`)) return
  try { await adminAPI.deletePlan(p.id); loadPlans() } catch (e) { alert(e?.message || '操作失败') }
}
async function removeWork(row) {
  if (!confirm(`软删除作品「${row.title || row.recipeTitle || row.id}」？（磁盘图片保留，可恢复）`)) return
  try { await adminAPI.deleteWork(row.id); loadWorks() } catch (e) { alert(e?.message || '操作失败') }
}
async function savePrefs() {
  savingPrefs.value = true
  try {
    await adminAPI.updateUserPreferences(userId, prefForm.value)
    alert('已保存口味/忌口')
  } catch (e) { alert(e?.message || '保存失败') } finally { savingPrefs.value = false }
}

function goBack() { router.push('/admin/users') }
function fmt(v) { return v ? String(v).replace('T', ' ').slice(0, 19) : '-' }
function fmtAmount(type, bytes) {
  if (type === 'videogen') return `${bytes ?? 0} 秒`
  if (type === 'tts') return `${bytes ?? 0} 字符`
  return formatSize(bytes)
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
.detail-head { margin-bottom: 16px; }
.detail-title { display: flex; align-items: center; gap: 8px; margin: 8px 0; }
.ml8 { margin-left: 8px; }
.meta-line { display: flex; gap: 18px; color: var(--text-secondary); font-size: 13px; }
.meta-line span::before { content: '· '; }
.btn-back { background: none; border: 1px solid #dcdfe6; border-radius: 6px; padding: 4px 12px; cursor: pointer; color: var(--text-secondary); }
.btn-back:hover { color: var(--primary, #409eff); border-color: var(--primary, #409eff); }
.detail-tabs { background: #fff; border-radius: 8px; padding: 8px 16px 16px; box-shadow: 0 1px 4px rgba(0,0,0,0.06); }
.overview-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.overview-grid .card:nth-child(3) { grid-column: 1 / -1; }
.card { background: #fff; border: 1px solid #ebeef5; border-radius: 8px; padding: 14px 16px; }
.card h4 { margin: 0 0 10px; }
.mt12 { margin-top: 12px; }
.level-block .level-num { font-size: 28px; font-weight: 700; color: var(--primary, #409eff); }
.level-num .lvl { font-size: 14px; color: var(--text-secondary); margin-left: 8px; font-weight: 400; }
.exp-line { font-size: 12px; color: var(--text-secondary); margin-top: 6px; }
.badge-row { display: flex; flex-wrap: wrap; gap: 8px; }
.badge { font-size: 13px; background: #f0f9eb; border: 1px solid #e1f3d8; border-radius: 6px; padding: 2px 8px; }
.badge.dim { opacity: 0.35; filter: grayscale(1); }
.filter-bar { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.filter-input { border: 1px solid #dcdfe6; border-radius: 6px; padding: 6px 10px; font-size: 13px; }
.filter-input.narrow { width: 140px; }
.filter-input.w60 { width: 320px; }
.btn-filter { border: 1px solid #dcdfe6; background: #fff; border-radius: 6px; padding: 6px 14px; cursor: pointer; }
.btn-filter:hover { color: var(--primary, #409eff); border-color: var(--primary, #409eff); }
.plan-card { margin-bottom: 12px; }
.plan-head { display: flex; align-items: center; gap: 12px; }
.muted { color: var(--text-secondary); font-size: 12px; }
.prefs-card .form-row { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.form-label { width: 90px; text-align: right; color: var(--text-secondary); font-size: 13px; }
.btn-save { background: var(--primary, #409eff); color: #fff; border: none; border-radius: 6px; padding: 8px 20px; cursor: pointer; }
.btn-save:disabled { opacity: 0.6; cursor: not-allowed; }
.weight-line { display: flex; gap: 18px; font-size: 13px; }
.loading { padding: 40px; text-align: center; color: var(--text-secondary); }
@media (max-width: 900px) { .overview-grid { grid-template-columns: 1fr; } .overview-grid .card:nth-child(3) { grid-column: auto; } }
</style>
