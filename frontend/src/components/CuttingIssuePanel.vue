<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import api from '../api'

const props = defineProps({
  mode: { type: String, required: true }, // order | roll
  order: { type: Object, default: null },
  roll: { type: Object, default: null },
  orders: { type: Array, default: () => [] }
})

const emit = defineEmits(['success', 'failure'])

const lengthM = ref('')
const weightKg = ref('')
const operatorName = ref(localStorage.getItem('cutting-operator') || '')
const requestId = ref(createRequestId())
const submitting = ref(false)
const trace = ref({ issues: [], attempts: [] })
const result = ref(null)
const error = ref('')
const lastSnapshots = ref({})

const effectiveOrder = computed(() => {
  if (props.mode === 'order') return props.order
  if (!props.roll) return null
  return props.orders.find((o) => o.assignedRoll && o.assignedRoll.id === props.roll.id && o.status === '切裁中') || null
})
const effectiveRoll = computed(() => (props.mode === 'order' ? props.order?.assignedRoll : props.roll))
const maxLength = computed(() => Number(effectiveRoll.value?.lengthM ?? 0))
const maxWeight = computed(() => Number(effectiveRoll.value?.weightKg ?? 0))
const currentLength = computed(() => Number(lengthM.value || 0))
const currentWeight = computed(() => Number(weightKg.value || 0))

const blockReason = computed(() => {
  if (!effectiveOrder.value) return props.mode === 'order' ? '订单未排产母卷' : '该母卷未排产给「切裁中」订单'
  if (effectiveOrder.value.status !== '切裁中') return `订单状态为「${effectiveOrder.value.status}」，不能领料`
  if (!effectiveRoll.value) return '缺少已排产母卷'
  if (effectiveRoll.value.status !== '可用') return `母卷状态为「${effectiveRoll.value.status}」，不能领料`
  if (effectiveRoll.value.widthMm < effectiveOrder.value.targetWidthMm) {
    return `规格不匹配：母卷幅宽 ${effectiveRoll.value.widthMm}mm 小于订单 ${effectiveOrder.value.targetWidthMm}mm`
  }
  if (Math.abs(effectiveRoll.value.grammageGsm - effectiveOrder.value.targetGrammage) > 3) {
    return `规格不匹配：定量偏差超过 3g/m²`
  }
  if (maxLength.value <= 0 || maxWeight.value <= 0) return '母卷余量不足'
  return ''
})

const overLimitReason = computed(() => {
  if (!effectiveRoll.value || currentLength.value <= 0 || currentWeight.value <= 0) return ''
  if (currentLength.value > maxLength.value) return `长度超过可领上限 ${maxLength.value} 米`
  if (currentWeight.value > maxWeight.value) return `重量超过可领上限 ${maxWeight.value.toFixed(2)}kg`
  return ''
})

const traceKey = computed(() => {
  if (props.mode === 'order') return effectiveOrder.value?.id || props.order?.id
  return effectiveRoll.value?.id || props.roll?.id
})

function createRequestId() {
  if (window.crypto?.randomUUID) return window.crypto.randomUUID()
  return `issue-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

function snapshotKey() {
  return `${effectiveOrder.value?.id || ''}:${effectiveRoll.value?.id || ''}`
}

function resetForm(force = false) {
  const roll = effectiveRoll.value
  const key = snapshotKey()
  if (force || lastSnapshots.value[key] === undefined) {
    lengthM.value = roll ? String(roll.lengthM) : ''
    weightKg.value = roll ? Number(roll.weightKg).toFixed(2) : ''
    lastSnapshots.value[key] = roll ? `${roll.lengthM}:${roll.weightKg}:${roll.status}` : ''
  }
}

function regenerateForNewTarget() {
  requestId.value = createRequestId()
  result.value = null
  error.value = ''
  resetForm(true)
}

async function loadTrace() {
  if (!traceKey.value) {
    trace.value = { issues: [], attempts: [] }
    return
  }
  try {
    trace.value = props.mode === 'order'
      ? await api.orderIssueTrace(traceKey.value)
      : await api.rollIssueTrace(traceKey.value)
  } catch (e) {
    error.value = e.message
  }
}

async function submitIssue() {
  if (submitting.value) return
  if (!effectiveOrder.value || !effectiveRoll.value) return
  if (!operatorName.value.trim()) {
    error.value = '请先填写操作人'
    return
  }
  submitting.value = true
  error.value = ''
  result.value = null
  localStorage.setItem('cutting-operator', operatorName.value.trim())
  const payload = {
    requestId: requestId.value,
    orderId: effectiveOrder.value.id,
    rollId: effectiveRoll.value.id,
    lengthM: Number(lengthM.value),
    weightKg: Number(Number(weightKg.value).toFixed(2)),
    operatorName: operatorName.value.trim()
  }
  try {
    const r = await api.issueCutting(payload)
    result.value = r
    await loadTrace()
    if (r.success) {
      requestId.value = createRequestId()
      lengthM.value = String(r.remainLengthM)
      weightKg.value = Number(r.remainWeightKg).toFixed(2)
      emit('success', r)
    } else {
      emit('failure', r)
    }
  } catch (e) {
    error.value = e.message
  } finally {
    submitting.value = false
  }
}

function formatTime(t) {
  return t ? t.replace('T', ' ').slice(0, 19) : '-'
}

watch(traceKey, async () => {
  regenerateForNewTarget()
  await loadTrace()
})
watch([lengthM, weightKg, operatorName], () => {
  // 失败后修改任何业务字段都视为新请求；未修改时重复点击则沿用 requestId 返回原结果。
  if (result.value && !result.value.success) requestId.value = createRequestId()
})
onMounted(() => {
  resetForm(true)
  loadTrace()
})
</script>

<template>
  <div class="issue-panel">
    <div class="issue-head">
      <div>
        <div class="issue-title">切裁领料闭环</div>
        <div class="issue-sub" v-if="effectiveOrder && effectiveRoll">
          订单 {{ effectiveOrder.orderNo }} · 母卷 {{ effectiveRoll.rollNo }}
        </div>
      </div>
      <span v-if="submitting" class="lock-badge">提交中，已锁定</span>
    </div>

    <div v-if="blockReason" class="block-reason">{{ blockReason }}</div>
    <div v-if="overLimitReason" class="block-reason">{{ overLimitReason }}</div>

    <div v-if="effectiveRoll" class="limit-grid">
      <div><span>可领长度上限</span><b>{{ maxLength }} 米</b></div>
      <div><span>可领重量上限</span><b>{{ maxWeight.toFixed(2) }} kg</b></div>
      <div><span>余长阈值</span><b>低于 200 米自动已切完</b></div>
    </div>

    <div class="issue-form">
      <label>
        <span>本次长度（米）</span>
        <input v-model="lengthM" type="number" min="1" :max="maxLength" step="1"
               :disabled="submitting || !!blockReason" />
      </label>
      <label>
        <span>本次重量（kg）</span>
        <input v-model="weightKg" type="number" min="0.01" :max="maxWeight" step="0.01"
               :disabled="submitting || !!blockReason" />
      </label>
      <label>
        <span>操作人</span>
        <input v-model="operatorName" maxlength="32" placeholder="请输入姓名"
               :disabled="submitting" />
      </label>
      <button class="issue-btn" :disabled="submitting || !!blockReason || !!overLimitReason" @click="submitIssue">
        {{ submitting ? '提交中…' : '登记领料' }}
      </button>
    </div>

    <div v-if="submitting" class="result processing">同一笔请求正在提交，按钮和输入框已锁定；请勿刷新或重复点击。</div>
    <div v-if="error" class="result failed">{{ error }}</div>
    <div v-if="result && result.success" class="result success">
      领料成功：本次 {{ result.requestedLengthM }} 米 / {{ Number(result.requestedWeightKg).toFixed(2) }}kg，
      新余量 {{ result.remainLengthM }} 米 / {{ Number(result.remainWeightKg).toFixed(2) }}kg
      <span v-if="result.rollStatus === '已切完'">，母卷已转为「已切完」</span>
      <em v-if="result.duplicate">（重复请求，返回原结果）</em>
    </div>
    <div v-if="result && !result.success" class="result failed">
      领料失败：{{ result.failureReason }}。未生成领料记录，母卷余量未改变。
      <em v-if="result.duplicate">（重复请求，返回原失败原因）</em>
    </div>

    <div class="trace-columns">
      <div>
        <div class="trace-title">成功领料记录</div>
        <table class="trace-table">
          <thead><tr><th>时间</th><th>订单</th><th>母卷</th><th>长度</th><th>重量</th><th>新余量</th><th>操作人</th></tr></thead>
          <tbody>
            <tr v-for="x in trace.issues" :key="x.id">
              <td>{{ formatTime(x.operatedAt) }}</td><td>{{ x.orderNo }}</td><td>{{ x.rollNo }}</td>
              <td>{{ x.lengthM }}m</td><td>{{ Number(x.weightKg).toFixed(2) }}kg</td>
              <td>{{ x.afterLengthM }}m / {{ Number(x.afterWeightKg).toFixed(2) }}kg</td>
              <td>{{ x.operatorName }}</td>
            </tr>
          </tbody>
        </table>
        <div v-if="!trace.issues.length" class="empty">暂无成功领料</div>
      </div>
      <div>
        <div class="trace-title">失败请求与原因</div>
        <table class="trace-table">
          <thead><tr><th>时间</th><th>订单</th><th>母卷</th><th>请求</th><th>原因</th><th>操作人</th></tr></thead>
          <tbody>
            <tr v-for="x in trace.attempts.filter(a => a.state === 'failed')" :key="x.requestId">
              <td>{{ formatTime(x.finishedAt || x.createdAt) }}</td><td>{{ x.orderNo || '-' }}</td><td>{{ x.rollNo || '-' }}</td>
              <td>{{ x.lengthM || '-' }}m / {{ x.weightKg ? Number(x.weightKg).toFixed(2) + 'kg' : '-' }}</td>
              <td class="reason">{{ x.failureReason }}</td><td>{{ x.operatorName || '-' }}</td>
            </tr>
          </tbody>
        </table>
        <div v-if="!trace.attempts.some(a => a.state === 'failed')" class="empty">暂无失败请求</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.issue-panel { border: 1px solid var(--line); border-radius: 10px; padding: 12px; background: #fff; }
.issue-head { display: flex; justify-content: space-between; gap: 12px; align-items: flex-start; }
.issue-title { font-weight: 800; color: var(--primary-deep); }
.issue-sub { font-size: 12px; color: var(--muted); margin-top: 2px; }
.lock-badge { font-size: 12px; font-weight: 700; color: #8d5a00; background: #fff3cd; border: 1px solid #ffe08a; border-radius: 999px; padding: 3px 10px; }
.block-reason { margin-top: 10px; padding: 8px 10px; border-radius: 8px; background: #fdf3f2; color: #c62828; font-size: 13px; }
.limit-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; margin: 10px 0; }
.limit-grid div { background: var(--primary-wash); border-radius: 8px; padding: 8px; display: flex; flex-direction: column; gap: 3px; }
.limit-grid span { font-size: 12px; color: var(--muted); }
.limit-grid b { font-size: 14px; color: var(--primary-deep); }
.issue-form { display: grid; grid-template-columns: 1fr 1fr 1fr auto; gap: 8px; align-items: end; }
.issue-form label { display: flex; flex-direction: column; gap: 4px; font-size: 12px; color: var(--muted); }
.issue-form input { padding: 8px; border: 1px solid var(--line); border-radius: 7px; font-size: 14px; }
.issue-btn { padding: 9px 16px; border: none; border-radius: 7px; background: var(--primary); color: #fff; font-weight: 800; cursor: pointer; }
.issue-btn:disabled { opacity: .45; cursor: not-allowed; }
.result { margin-top: 10px; padding: 8px 10px; border-radius: 8px; font-size: 13px; }
.result.success { background: #eef8ef; color: #2e7d32; border: 1px solid #b9dfbd; }
.result.failed { background: #fdf3f2; color: #c62828; border: 1px solid #efc9c5; }
.result.processing { background: #fff8e1; color: #8d5a00; border: 1px solid #ffe08a; }
.result em { display: block; margin-top: 3px; font-style: normal; opacity: .75; }
.trace-columns { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin-top: 12px; }
.trace-title { font-weight: 800; font-size: 13px; margin-bottom: 6px; color: var(--primary-deep); }
.trace-table { width: 100%; border-collapse: collapse; font-size: 12px; }
.trace-table th, .trace-table td { border-bottom: 1px solid var(--line); padding: 6px; text-align: left; vertical-align: top; }
.trace-table th { color: var(--muted); font-weight: 700; background: #fafafa; }
.reason { color: #c62828; min-width: 180px; }
.empty { color: var(--muted); font-size: 12px; padding: 8px; }
@media (max-width: 1100px) {
  .limit-grid, .issue-form, .trace-columns { grid-template-columns: 1fr; }
}
</style>
